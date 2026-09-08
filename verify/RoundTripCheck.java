import java.io.*;
import java.util.*;

/**
 * 针对 readUTFBody StringBuilder 复用改动的往返校验：
 * 覆盖跨块(1024B)、超长(>256 char)、NUL、代理对、随机混合串。
 * 反复构造"恰跨块边界"的串，确保复用 StringBuilder 后内容仍逐字节正确。
 */
public class RoundTripCheck {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] args) throws Exception {
        Random rnd = new Random(20260909L);
        String[] cjk = {"中", "文", "编", "码", "性", "能"};
        String[] emoji = {"\uD83D\uDD25", "\uD83D\uDE80", "\uD83C\uDF89"};
        int[] lens = {1, 2, 31, 32, 33, 95, 96, 97, 255, 256, 257, 1023, 1024, 1025,
                      2000, 5000};  // 覆盖 cbuf(256) 与块边界(1024) 两侧

        for (int len : lens) {
            for (int t = 0; t < 200; t++) {
                // 随机组成：ASCII / CJK / emoji / NUL / surrogate 混合
                StringBuilder sb = new StringBuilder();
                int remaining = len;
                while (remaining > 0) {
                    int kind = rnd.nextInt(5);
                    String add;
                    if (kind == 0) add = String.valueOf((char) ('a' + rnd.nextInt(26)));
                    else if (kind == 1) add = cjk[rnd.nextInt(cjk.length)];
                    else if (kind == 2) add = emoji[rnd.nextInt(emoji.length)];
                    else if (kind == 3) add = "\u0000";
                    else add = String.valueOf((char) (0x800 + rnd.nextInt(0x100)));
                    sb.append(add);
                    remaining -= add.length();
                }
                String s = sb.length() > len ? sb.substring(0, len) : sb.toString();
                byte[] b = ser(s);
                Object o = deser(b);
                String back = (String) o;
                if (!s.equals(back)) {
                    // 只报前 3 个失败，避免刷屏
                    if (fails < 3) System.out.println("FAIL len=" + len + " orig=[" + s + "] back=[" + back + "]");
                    fails++;
                }
            }
        }

        // 边界专项：一长串恰好跨多个块
        for (int t = 0; t < 500; t++) {
            int extra = rnd.nextInt(100);
            String base = repeat("中文\u0000\uD83D\uDE80ab", 20) + "x".repeat(extra);
            byte[] b = ser(base);
            String back = (String) deser(b);
            if (!base.equals(back)) {
                if (fails < 5) System.out.println("FAIL boundary t=" + t);
                fails++;
            }
        }

        if (fails == 0) {
            System.out.println("RoundTripCheck PASS: 全部跨块/超长/NUL/代理对/随机串往返一致");
        } else {
            System.out.println("RoundTripCheck FAILED: " + fails + " 处不一致");
            System.exit(1);
        }
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static byte[] ser(Object o) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) { oos.writeObject(o); }
        return out.toByteArray();
    }

    static Object deser(byte[] b) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(b))) {
            return ois.readObject();
        }
    }
}
