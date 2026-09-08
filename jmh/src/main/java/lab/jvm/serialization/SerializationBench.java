package lab.jvm.serialization;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 序列化性能 JMH 基准。
 *
 * JMH 是 Java 官方的微基准（micro-benchmark）工具。微基准的难点在于
 * JIT 编译器会“优化掉”没用的代码，或把方法内联得面目全非，
 * 所以 JMH 规定了几件事，下面用大白话解释：
 *
 * 1. @Fork(2)：让每个基准在 2 个全新 JVM 进程里各跑一遍。
 *    不同进程互不共享编译缓存，能避免某个基准“蹭”到另一个基准的
 *    编译结果，结果更可信。
 *
 * 2. @Warmup(iterations = 5, time = 2)：正式计分前先跑 5×2 秒“热身”。
 *    Java 程序是边跑边编译的（JIT），前几秒往往还没编译好；
 *    热身的意义就是让热点代码编译完成、缓存预热完，正式测量才稳定。
 *
 * 3. @Measurement(iterations = 6, time = 2)：热身完再正式跑 6×2 秒，
 *    这些数据才进统计结果。
 *
 * 4. Blackhole（黑洞）：把“计算结果”交出去。如果不这么做，编译器可能
 *    发现这次序列化产生的字节没人用，直接把整段代码优化掉，
 *    测出来的速度就毫无意义。
 *
 * 5. 每次操作都 new 一个流（ObjectOutputStream/InputStream）：
 *    模拟真实应用“写完一次就关掉”的用法，而不是复用同一个流来刷成绩。
 *
 * 本文件只声明“测什么”，跑多少轮由命令行参数控制（见 README/基线命令）。
 */
@BenchmarkMode(Mode.Throughput)                    // 用“吞吐量”衡量：每秒能完成多少次
@OutputTimeUnit(TimeUnit.SECONDS)                  // 单位：ops/s（每秒操作次数）
@Warmup(iterations = 5, time = 2)                  // 类级默认：5 次热身，每次 2 秒
@Measurement(iterations = 6, time = 2)             // 类级默认：6 次正式测量，每次 2 秒
@Fork(2)                                           // 类级默认：2 个独立 JVM
public class SerializationBench {

    /**
     * 基准“状态”：每个 fork（独立 JVM）准备一次数据，所有迭代共用。
     *
     * Scope.Benchmark 的意思是：这份状态在整个 JVM 内只创建一份、所有线程共享。
     * 我们的基准是单线程的，因此等价于“每个 JVM 一份”。
     *
     * 里面有两类字段，用途不同：
     * - xxx：真实 Java 对象，给“写”基准用（写之前得有个对象）；
     * - xxxBytes：事先序列化好的字节数组，给“读”基准用（读之前得有字节）。
     * 字节在 @Setup 阶段预先算好：读基准只测“反序列化”本身，
     * 不把“先序列化一次”的开销混进来。
     */
    @State(Scope.Benchmark)
    public static class Data {

        /** 客户对象：给 writeCustomer 用 */
        Payloads.Customer customer;
        /** 客户对象对应的字节：给 readCustomer 用（预序列化得到） */
        byte[] customerBytes;

        /** 订单对象图：给 writeOrder 用 */
        Payloads.Order order;
        /** 订单对象图对应的字节：给 readOrder 用 */
        byte[] orderBytes;

        /** 统计数组对象：给 writeAnalytics 用 */
        Payloads.Analytics analytics;
        /** 统计数组对象对应的字节：给 readAnalytics 用 */
        byte[] analyticsBytes;

        /** record 对象：给 writePoint 用 */
        Payloads.Point point;
        /** record 对象对应的字节：给 readPoint 用 */
        byte[] pointBytes;

        /** 字符串列表：给 writeStringList 用 */
        List<String> stringList;
        /** 字符串列表对应的字节：给 readStringList 用 */
        byte[] stringListBytes;

        /** 业务配置型 HashMap：给 writeConfigMap 用 */
        Map<String, Object> configMap;
        /** 对应的字节：给 readConfigMap 用 */
        byte[] configMapBytes;

        /** 中英文/emoji 混合字符串列表：给 writeUnicodeList 用 */
        List<String> unicodeList;
        /** 对应的字节：给 readUnicodeList 用 */
        byte[] unicodeListBytes;

        /** 含循环引用+共享引用的对象图：给 writeGraph 用 */
        Payloads.Graph graph;
        /** 对应的字节：给 readGraph 用 */
        byte[] graphBytes;

        /** Object[] 混合数组载体：给 writeMixedArray 用 */
        Payloads.MixedArray mixedArray;
        /** 对应的字节：给 readMixedArray 用 */
        byte[] mixedArrayBytes;

        /** 枚举+null 载体：给 writeEnumHolder 用 */
        Payloads.EnumHolder enumHolder;
        /** 对应的字节：给 readEnumHolder 用 */
        byte[] enumHolderBytes;

        /** 继承层次对象（父类+子类字段）：给 writeDerived 用 */
        Payloads.DerivedEntity derived;
        /** 对应的字节：给 readDerived 用 */
        byte[] derivedBytes;

        /** 复用流基准的底层缓冲：每轮写完后 reset() 清空，但容量不重新分配 */
        ByteArrayOutputStream reuseBuf;
        /** 复用流基准的流实例：只构造一次（stream header 只写一次） */
        ObjectOutputStream reuseOos;

        /**
         * Level.Trial = 每个 fork 只执行一次（Trial 指“整个这一轮 JVM 测试”）。
         * 在这里把对象建好、把“读基准要用的字节”提前序列化好。
         */
        @Setup(Level.Trial)
        public void setup() throws Exception {
            customer = Payloads.newCustomer();
            customerBytes = roundTrip(customer);

            order = Payloads.newOrder();
            orderBytes = roundTrip(order);

            analytics = Payloads.newAnalytics(1024);
            analyticsBytes = roundTrip(analytics);

            point = new Payloads.Point(3, 4, "origin");
            pointBytes = roundTrip(point);

            stringList = Payloads.newStringList(128);
            stringListBytes = roundTrip(stringList);

            configMap = Payloads.newConfigMap();
            configMapBytes = roundTrip(configMap);

            unicodeList = Payloads.newUnicodeList(64);
            unicodeListBytes = roundTrip(unicodeList);

            graph = Payloads.newGraph();
            graphBytes = roundTrip(graph);

            mixedArray = Payloads.newMixedArray();
            mixedArrayBytes = roundTrip(mixedArray);

            enumHolder = Payloads.newEnumHolder();
            enumHolderBytes = roundTrip(enumHolder);

            derived = Payloads.newDerivedEntity();
            derivedBytes = roundTrip(derived);

            // 复用流基准：先构造好“流 + 底层缓冲”，基准里只 reset/复用它们
            reuseBuf = new ByteArrayOutputStream(1024);
            reuseOos = new ObjectOutputStream(reuseBuf);
        }

        /**
         * 小工具：把对象完整写一遍再读出来？不——这里只需要“写出来的字节”，
         * 供 read 基准当输入。写入用 ByteArrayOutputStream（内存输出流）。
         *
         * @param o 要序列化的对象
         * @return 序列化得到的字节数组
         */
        private static byte[] roundTrip(Object o) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16); // 预留 64KB，减少扩容
            try (ObjectOutputStream oos = new ObjectOutputStream(out)) {    // 用完自动关闭
                oos.writeObject(o);
            }
            return out.toByteArray();
        }
    }

    // ============================ 写（序列化）基准 ============================
    // 统一套路：new 一个 ByteArrayOutputStream → 包上 ObjectOutputStream →
    // writeObject 把对象写进内存字节 → 把字节交给 Blackhole 防止被 JIT 优化掉。

    /** 测“写一个小客户对象”一次要多久（约 247 字节/次） */
    @Benchmark
    public void writeCustomer(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256); // 预估容量 256B，减少扩容
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.customer);
        }
        bh.consume(out.toByteArray()); // “消费”结果，防止编译器认为写字节没有意义
    }

    /** 测“写一个嵌套订单对象图”一次要多久（约 799 字节/次） */
    @Benchmark
    public void writeOrder(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.order);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个数组密集型对象”一次要多久（约 29.9 KB/次），重点看批量数组路径 */
    @Benchmark
    public void writeAnalytics(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.analytics);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个 record”一次要多久（约 111 字节/次），record 走独立路径 */
    @Benchmark
    public void writePoint(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.point);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个 128 字符串的 ArrayList”一次要多久（约 1.6 KB/次），重点看 UTF-8 编码 */
    @Benchmark
    public void writeStringList(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.stringList);
        }
        bh.consume(out.toByteArray());
    }

    // ---- 补充载荷：写基准 ----

    /** 测“写一个业务配置型 HashMap”一次要多久（自定义 writeObject + 键值递归路径） */
    @Benchmark
    public void writeConfigMap(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.configMap);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一列中文/emoji 字符串”一次要多久（modified UTF-8 多字节分支） */
    @Benchmark
    public void writeUnicodeList(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.unicodeList);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个含循环/共享引用的对象图”一次要多久（句柄表回引分支） */
    @Benchmark
    public void writeGraph(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.graph);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个 Object[] 混合数组”一次要多久（引用数组逐元素递归分支） */
    @Benchmark
    public void writeMixedArray(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.mixedArray);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个枚举+null 对象”一次要多久（TC_ENUM / TC_NULL 分支） */
    @Benchmark
    public void writeEnumHolder(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.enumHolder);
        }
        bh.consume(out.toByteArray());
    }

    /** 测“写一个带父类字段的继承对象”一次要多久（多数据槽层级路径） */
    @Benchmark
    public void writeDerived(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.derived);
        }
        bh.consume(out.toByteArray());
    }

    // ---- 复用流场景（写侧） ----
    //
    // 真实世界里有两种用法：
    // 1) “一次性”：写完一个对象就关闭流（上面所有 writeXxx 测的就是这种）；
    // 2) “长连接/批量导出”：同一个 ObjectOutputStream 反复写多个对象，
    //    类描述符只写一次、句柄表与底层缓冲被反复复用。
    // 下面两个基准测第二种用法。每轮先 oos.reset() 清句柄表（合法语义，
    // 防止对象被当成“已经写过的引用”），再写对象并 flush。
    // 说明：为了控制内存，底层 ByteArrayOutputStream 每轮会被 reset()，
    // 所以这里的字节片段不以“能被读回”为目标，只衡量复用的写路径开销。

    /** 复用流写 Customer：对比 writeCustomer 可看出“构造新流”本身的成本占比 */
    @Benchmark
    public void reuseWriteCustomer(Data d, Blackhole bh) throws Exception {
        d.reuseOos.reset();                    // 清句柄表（每次写独立对象）
        d.reuseOos.writeObject(d.customer);    // 写对象（类描述符每轮重写，但流/缓冲复用）
        d.reuseOos.flush();                    // 把内部缓冲推到底层 reuseBuf
        bh.consume(d.reuseBuf.toByteArray());  // 消费本轮字节，防 JIT 优化
        d.reuseBuf.reset();                    // 清空底层缓冲，下一轮继续复用容量
    }

    /** 复用流写 Order：对象图更复杂，能更明显看出句柄表/缓冲复用的影响 */
    @Benchmark
    public void reuseWriteOrder(Data d, Blackhole bh) throws Exception {
        d.reuseOos.reset();
        d.reuseOos.writeObject(d.order);
        d.reuseOos.flush();
        bh.consume(d.reuseBuf.toByteArray());
        d.reuseBuf.reset();
    }

    // ============================ 读（反序列化）基准 ============================
    // 统一套路：把预先生成的字节包进 ByteArrayInputStream → 包上 ObjectInputStream →
    // readObject 还原对象 → 直接 return，JMH 会把返回值当成“被使用”，防止优化掉。
    // 注意读基准还要新建对象、分配字符串、递归还原引用，因此通常比写慢很多。

    /** 测“把一个客户对象从字节还原”一次要多久 */
    @Benchmark
    public Object readCustomer(Data d) throws Exception {
        return readOne(d.customerBytes);
    }

    /** 测“把一个嵌套订单对象图从字节还原”一次要多久 */
    @Benchmark
    public Object readOrder(Data d) throws Exception {
        return readOne(d.orderBytes);
    }

    /** 测“把一个数组密集型对象从字节还原”一次要多久 */
    @Benchmark
    public Object readAnalytics(Data d) throws Exception {
        return readOne(d.analyticsBytes);
    }

    /** 测“把一个 record 从字节还原”一次要多久（会走 canonical 构造器） */
    @Benchmark
    public Object readPoint(Data d) throws Exception {
        return readOne(d.pointBytes);
    }

    /** 测“把一个字符串列表从字节还原”一次要多久 */
    @Benchmark
    public Object readStringList(Data d) throws Exception {
        return readOne(d.stringListBytes);
    }

    // ---- 补充载荷：读基准 ----

    /** 测“把一个 HashMap 从字节还原”一次要多久 */
    @Benchmark
    public Object readConfigMap(Data d) throws Exception {
        return readOne(d.configMapBytes);
    }

    /** 测“把一列中文/emoji 字符串从字节还原”一次要多久 */
    @Benchmark
    public Object readUnicodeList(Data d) throws Exception {
        return readOne(d.unicodeListBytes);
    }

    /** 测“把含循环/共享引用的对象图从字节还原”一次要多久（回引要还原成同一对象） */
    @Benchmark
    public Object readGraph(Data d) throws Exception {
        return readOne(d.graphBytes);
    }

    /** 测“把一个 Object[] 混合数组从字节还原”一次要多久 */
    @Benchmark
    public Object readMixedArray(Data d) throws Exception {
        return readOne(d.mixedArrayBytes);
    }

    /** 测“把一个枚举+null 对象从字节还原”一次要多久 */
    @Benchmark
    public Object readEnumHolder(Data d) throws Exception {
        return readOne(d.enumHolderBytes);
    }

    /** 测“把一个继承对象从字节还原”一次要多久（要按层级还原父类+子类槽） */
    @Benchmark
    public Object readDerived(Data d) throws Exception {
        return readOne(d.derivedBytes);
    }

    /**
     * 反序列化公共实现：字节 → 内存流 → ObjectInputStream → 还原对象。
     *
     * @param bytes 之前序列化好的字节
     * @return 还原出的对象（由 JMH 当作基准返回值“消费”掉）
     */
    private static Object readOne(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
