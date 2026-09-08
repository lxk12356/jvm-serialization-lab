package lab.jvm.serialization;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * K 扫描（放大性）基准：区分 per-stream 固定成本与 per-object 成本。
 *
 * 为什么需要它（背景见 reports/optimization-plan.md 阶段 0）：
 * 现有 one-shot 基准每次操作都新建流（header、关闭、toByteArray() 都算在
 * 单次结果里），小对象（如 Customer 247 B）的结果被流生命周期固定成本稀释，
 * 无法判断“改 JDK 内部每对象路径”到底有没有收益。
 *
 * 本类把“一次操作”放大为：一个流里连续写入/读取同一个载荷类型 K 次
 * （K = 1 / 16 / 128，@Param 参数化）。随着 K 增大：
 * - 流的创建/stream header/关闭等固定成本被摊薄到 K 个对象上；
 * - 归一化指标：objects/s = ops/s × K，B/object = B/op ÷ K。
 *
 * 测量语义（与 one-shot 基准一致的口径）：
 * - 写侧：每次操作 new 一个 ByteArrayOutputStream + ObjectOutputStream，
 *   把 K 个【独立实例】（同类型、内容相同）依次 writeObject，close/flush，
 *   最后 toByteArray() 交给 Blackhole。K 个实例各自独立，避免句柄回引
 *   （同一实例连写 K 次只会第一次写全量、之后写 TC_REFERENCE）。
 * - 读侧：setup 时用同样的“连写 K 个”方式预生成一份字节（只含一次 stream
 *   header），每次操作 new 一个流连续 readObject K 次，逐一交给 Blackhole。
 *
 * 数据用途：比较 K=1 与 K=128 的斜率（ops/s、B/op 随 K 的摊薄程度），
 * 按 plan 3.3 的决策规则判断固定流成本 vs per-object 成本谁占主导，
 * 再决定优化刀口落在基准包装层还是 JDK 内部路径。K=1 的结果可与
 * SerializationBench 的 one-shot 结果交叉验证（同机器同 JDK 同命令）。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class KSweepBench {

    /**
     * 每个 fork 准备一次状态。K 是 @Param，JMH 会为 K=1/16/128 各自
     * 创建独立的 Data 实例并分别跑全部引用本状态的基准方法。
     */
    @State(Scope.Benchmark)
    public static class Data {

        /** 放大倍数：一个流里连写的对象个数（也是连读的对象个数） */
        @Param({"1", "16", "128"})
        public int K;

        /** K 个独立 Customer 实例（内容相同、身份不同） */
        Object[] customers;
        /** K 个 Customer 连写进同一流得到的字节（读侧输入） */
        byte[] customerBytes;
        /** 写侧 BAOS 预估容量：与预生成字节等长，避免扩容拷贝干扰 */
        int customerCap;

        Object[] orders;
        byte[] orderBytes;
        int orderCap;

        Object[] unicodeLists;
        byte[] unicodeListBytes;
        int unicodeListCap;

        Object[] analytics;
        byte[] analyticsBytes;
        int analyticsCap;

        Object[] points;
        byte[] pointBytes;
        int pointCap;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            customers = makeK(i -> Payloads.newCustomer());
            customerBytes = concatBytes(customers);
            customerCap = customerBytes.length;

            orders = makeK(i -> Payloads.newOrder());
            orderBytes = concatBytes(orders);
            orderCap = orderBytes.length;

            unicodeLists = makeK(i -> Payloads.newUnicodeList(64));
            unicodeListBytes = concatBytes(unicodeLists);
            unicodeListCap = unicodeListBytes.length;

            analytics = makeK(i -> Payloads.newAnalytics(1024));
            analyticsBytes = concatBytes(analytics);
            analyticsCap = analyticsBytes.length;

            points = makeK(i -> new Payloads.Point(3, 4, "origin"));
            pointBytes = concatBytes(points);
            pointCap = pointBytes.length;
        }

        /** 造 K 个独立实例：工厂每次调用都返回新对象，保证身份互不相同 */
        private Object[] makeK(IntFunction<Object> factory) {
            Object[] objs = new Object[K];
            for (int i = 0; i < K; i++) {
                objs[i] = factory.apply(i);
            }
            return objs;
        }

        /** 把 K 个对象依次写进同一个流，返回整段字节（只含一次 stream header） */
        private static byte[] concatBytes(Object[] objs) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
                for (Object o : objs) {
                    oos.writeObject(o);
                }
            }
            return out.toByteArray();
        }
    }

    // ============================ 写侧 K 扫描 ============================
    // 一次操作 = 新建流 + 连写 K 个同类型对象 + close + toByteArray。
    // 报告的是“批”吞吐（batches/s）；归一化 objects/s = ops/s × K。

    @Benchmark
    public void writeKCustomer(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.customerCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            for (int i = 0; i < d.K; i++) {
                oos.writeObject(d.customers[i]);
            }
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeKOrder(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.orderCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            for (int i = 0; i < d.K; i++) {
                oos.writeObject(d.orders[i]);
            }
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeKUnicodeList(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.unicodeListCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            for (int i = 0; i < d.K; i++) {
                oos.writeObject(d.unicodeLists[i]);
            }
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeKAnalytics(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.analyticsCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            for (int i = 0; i < d.K; i++) {
                oos.writeObject(d.analytics[i]);
            }
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeKPoint(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.pointCap);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            for (int i = 0; i < d.K; i++) {
                oos.writeObject(d.points[i]);
            }
        }
        bh.consume(out.toByteArray());
    }

    // ============================ 读侧 K 扫描 ============================
    // 一次操作 = 新建流 + 连读 K 个对象。输入是 setup 预生成的单流字节。

    @Benchmark
    public void readKCustomer(Data d, Blackhole bh) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(d.customerBytes))) {
            for (int i = 0; i < d.K; i++) {
                bh.consume(ois.readObject());
            }
        }
    }

    @Benchmark
    public void readKOrder(Data d, Blackhole bh) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(d.orderBytes))) {
            for (int i = 0; i < d.K; i++) {
                bh.consume(ois.readObject());
            }
        }
    }

    @Benchmark
    public void readKUnicodeList(Data d, Blackhole bh) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(d.unicodeListBytes))) {
            for (int i = 0; i < d.K; i++) {
                bh.consume(ois.readObject());
            }
        }
    }

    @Benchmark
    public void readKAnalytics(Data d, Blackhole bh) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(d.analyticsBytes))) {
            for (int i = 0; i < d.K; i++) {
                bh.consume(ois.readObject());
            }
        }
    }

    @Benchmark
    public void readKPoint(Data d, Blackhole bh) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(d.pointBytes))) {
            for (int i = 0; i < d.K; i++) {
                bh.consume(ois.readObject());
            }
        }
    }
}
