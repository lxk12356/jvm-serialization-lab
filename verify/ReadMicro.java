import java.io.*;
import java.util.*;
import lab.jvm.serialization.Payloads;

/**
 * 独立读基准：绕开 JMH 启动噪声，用大迭代量让测量段分配占绝对主导，
 * 供 JFR allocation-by-site 精确定位读 CJK 的分配点。
 * 用法：
 *   java -XX:StartFlightRecording=filename=read.jfr,settings=profile \
 *        -cp "jmh/target/classes;verify" ReadMicro.java  cjk|ascii
 */
public class ReadMicro {
    static byte[] bytes;

    static Object readOne() throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    public static void main(String[] args) throws Exception {
        String kind = args.length > 0 ? args[0] : "cjk";
        Object payload = kind.equals("cjk") ? Payloads.newCjk32List(64)
                                            : Payloads.newAscii96List(64);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(payload);
        }
        bytes = out.toByteArray();
        System.out.println("payload=" + kind + " size=" + bytes.length);

        // 预热（JIT + 类加载）
        for (int i = 0; i < 100_000; i++) {
            readOne();
        }
        System.out.println("warmup done, entering measurement...");
        // 测量段：200 万次 → 分配量级数百 GB，启动噪声占比可忽略
        for (int i = 0; i < 2_000_000; i++) {
            readOne();
        }
        System.out.println("measurement done");
    }
}
