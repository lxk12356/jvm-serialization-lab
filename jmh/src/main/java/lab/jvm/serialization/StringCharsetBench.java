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
import java.util.concurrent.TimeUnit;

/**
 * 阶段 2 对照实验：字符集 / NUL / 跨块边界对序列化成本的影响。
 *
 * 为什么要单独做这一组？
 * v2 基线观察到"UnicodeList 读吞吐只有 StringList 的一半"，但要把它归因到
 * 字符集，必须先排除干扰：UnicodeList 是 64 个元素约 2 003 B，StringList 是
 * 128 个元素约 1 596 B——元素数和字节量都不同，两个变量混在一起。
 *
 * 本类用"单变量"对照把结论钉死：
 *
 * 组 1 等 UTF-16 长度（每串 32 char，隔离编码宽度）
 *   ascii32（32 B/串） / cjk32（96 B/串） / emoji16（96 B/串）
 *   - ascii32 vs cjk32   ：字符数相同，字节量 3 倍；
 *   - cjk32   vs emoji16 ：字节量相同，解码单元不同（3 B 序列 vs 6 B 代理对）。
 *
 * 组 2 等字节量（每串 96 B，隔离解码分支）
 *   ascii96 / cjk32 / emoji16 三者字节数相同，只有字符集不同。
 *
 * 组 3 NUL（正确性约束）
 *   nul32 含 U+0000，modified UTF-8 必须编成 C0 80。它同时是"优化后不许
 *   换成标准 UTF-8"的哨兵——语义由 PayloadSizeProbe.verifyNulList 校验。
 *
 * 组 4 跨块边界（MAX_BLOCK_SIZE = 1024）
 *   ascii1020 块内 / ascii1030 刚好越界 / ascii4096 多次越界 / cjk350 中文越界。
 *
 * 测量口径：与 SerializationBench 的 one-shot 完全一致（每次操作新建流）。
 * 之所以可以用 one-shot：阶段 0 已证明 UnicodeList 这类载荷的 per-stream
 * 固定成本只占 8.3%，成本主要在每个对象自身，信号不会被建流成本淹没。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class StringCharsetBench {

    /** 每个 fork 准备一次：对象给写侧用，预序列化字节给读侧用 */
    @State(Scope.Benchmark)
    public static class Data {

        /** 等长度组：纯 ASCII，64 串 × 32 字符 */
        List<String> ascii32;
        byte[] ascii32Bytes;

        /** 等长度组：纯中文，64 串 × 32 汉字 */
        List<String> cjk32;
        byte[] cjk32Bytes;

        /** 等长度组：纯增补平面 emoji，64 串 × 16 个 emoji（= 32 char） */
        List<String> emoji16;
        byte[] emoji16Bytes;

        /** 等字节组：纯 ASCII，64 串 × 96 字符（与 cjk32/emoji16 同字节量） */
        List<String> ascii96;
        byte[] ascii96Bytes;

        /** NUL 组：64 串 × 32 字符，每串含一个 U+0000 */
        List<String> nul32;
        byte[] nul32Bytes;

        /** 跨块组：1020 字符（块内） */
        String ascii1020;
        byte[] ascii1020Bytes;

        /** 跨块组：1030 字符（刚好越过 1024） */
        String ascii1030;
        byte[] ascii1030Bytes;

        /** 跨块组：4096 字符（多次越界） */
        String ascii4096;
        byte[] ascii4096Bytes;

        /** 跨块组：350 汉字 = 1050 B（中文越界） */
        String cjk350;
        byte[] cjk350Bytes;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            ascii32 = Payloads.newAscii32List(64);
            ascii32Bytes = roundTrip(ascii32);

            cjk32 = Payloads.newCjk32List(64);
            cjk32Bytes = roundTrip(cjk32);

            emoji16 = Payloads.newEmoji16List(64);
            emoji16Bytes = roundTrip(emoji16);

            ascii96 = Payloads.newAscii96List(64);
            ascii96Bytes = roundTrip(ascii96);

            nul32 = Payloads.newNul32List(64);
            nul32Bytes = roundTrip(nul32);

            ascii1020 = Payloads.newAscii1020();
            ascii1020Bytes = roundTrip(ascii1020);

            ascii1030 = Payloads.newAscii1030();
            ascii1030Bytes = roundTrip(ascii1030);

            ascii4096 = Payloads.newAscii4096();
            ascii4096Bytes = roundTrip(ascii4096);

            cjk350 = Payloads.newCjk350();
            cjk350Bytes = roundTrip(cjk350);
        }

        /** 预序列化：读侧只测反序列化本身，不把"先序列化一次"的开销混进来 */
        private static byte[] roundTrip(Object o) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
                oos.writeObject(o);
            }
            return out.toByteArray();
        }
    }

    // ============================ 写（序列化） ============================

    @Benchmark
    public void writeAscii32(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.ascii32);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeCjk32(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.cjk32);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeEmoji16(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.emoji16);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeAscii96(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.ascii96);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeNul32(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.nul32);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeAscii1020(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.ascii1020);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeAscii1030(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.ascii1030);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeAscii4096(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.ascii4096);
        }
        bh.consume(out.toByteArray());
    }

    @Benchmark
    public void writeCjk350(Data d, Blackhole bh) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(d.cjk350);
        }
        bh.consume(out.toByteArray());
    }

    // ============================ 读（反序列化） ============================

    @Benchmark
    public Object readAscii32(Data d) throws Exception {
        return readOne(d.ascii32Bytes);
    }

    @Benchmark
    public Object readCjk32(Data d) throws Exception {
        return readOne(d.cjk32Bytes);
    }

    @Benchmark
    public Object readEmoji16(Data d) throws Exception {
        return readOne(d.emoji16Bytes);
    }

    @Benchmark
    public Object readAscii96(Data d) throws Exception {
        return readOne(d.ascii96Bytes);
    }

    @Benchmark
    public Object readNul32(Data d) throws Exception {
        return readOne(d.nul32Bytes);
    }

    @Benchmark
    public Object readAscii1020(Data d) throws Exception {
        return readOne(d.ascii1020Bytes);
    }

    @Benchmark
    public Object readAscii1030(Data d) throws Exception {
        return readOne(d.ascii1030Bytes);
    }

    @Benchmark
    public Object readAscii4096(Data d) throws Exception {
        return readOne(d.ascii4096Bytes);
    }

    @Benchmark
    public Object readCjk350(Data d) throws Exception {
        return readOne(d.cjk350Bytes);
    }

    private static Object readOne(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }
}
