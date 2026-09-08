/**
 * 等价性验证：证明对 ObjectOutputStream.writeMoreUTF 与 ModifiedUtf.utfLen
 * 的两处"微优化"改动没有改变任何语义。
 *
 * 为什么需要它：当前环境没有 make / WSL，无法增量重建 java.base 跑完整回归，
 * 所以在构建验证之前，先用穷举把"逻辑等价"这件事钉死——
 * 对全部 char 值（0..0xFFFF）以及随机组合字符串，逐字节对比新旧实现的输出。
 *
 * 运行（任意 JDK 即可，这只是普通 Java 程序）：
 *   java UtfEquivalence.java
 */
public class UtfEquivalence {

    // ================= 原实现 =================

    /** 原 ModifiedUtf.utfLen */
    static int utfLenOld(String str, int countNonZeroAscii) {
        int utflen = str.length();
        for (int i = utflen - 1; i >= countNonZeroAscii; i--) {
            int c = str.charAt(i);
            if (c >= 0x80 || c == 0)
                utflen += (c >= 0x800) ? 2 : 1;
        }
        return utflen;
    }

    /** 原 ModifiedUtf.putChar（返回写入后的 offset） */
    static int putCharOld(byte[] buf, int offset, char c) {
        if (c != 0 && c < 0x80) {
            buf[offset++] = (byte) c;
        } else if (c >= 0x800) {
            buf[offset    ] = (byte) (0xE0 | c >> 12 & 0x0F);
            buf[offset + 1] = (byte) (0x80 | c >> 6  & 0x3F);
            buf[offset + 2] = (byte) (0x80 | c       & 0x3F);
            offset += 3;
        } else {
            buf[offset    ] = (byte) (0xC0 | c >> 6 & 0x1F);
            buf[offset + 1] = (byte) (0x80 | c      & 0x3F);
            offset += 2;
        }
        return offset;
    }

    // ================= 新实现（改动后） =================

    /** 新 ModifiedUtf.utfLen（仅分支顺序调整） */
    static int utfLenNew(String str, int countNonZeroAscii) {
        int utflen = str.length();
        for (int i = utflen - 1; i >= countNonZeroAscii; i--) {
            int c = str.charAt(i);
            if (c >= 0x800) {
                utflen += 2;
            } else if (c >= 0x80 || c == 0) {
                utflen += 1;
            }
        }
        return utflen;
    }

    /**
     * 新 writeMoreUTF 内联写法：先用同一条 csize 表达式算出宽度，
     * 再按 csize 分支写入（不再调用 putChar 二次判断）。
     */
    static int putCharNew(byte[] buf, int offset, char c) {
        int csize = c != 0 && c < 0x80 ? 1 : c >= 0x800 ? 3 : 2;
        if (csize == 1) {
            buf[offset++] = (byte) c;
        } else if (csize == 3) {
            buf[offset    ] = (byte) (0xE0 | c >> 12 & 0x0F);
            buf[offset + 1] = (byte) (0x80 | c >> 6  & 0x3F);
            buf[offset + 2] = (byte) (0x80 | c       & 0x3F);
            offset += 3;
        } else {
            buf[offset    ] = (byte) (0xC0 | c >> 6 & 0x1F);
            buf[offset + 1] = (byte) (0x80 | c      & 0x3F);
            offset += 2;
        }
        return offset;
    }

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  [不一致] " + what);
        }
    }

    public static void main(String[] args) {
        // ---------- 1. 穷举全部 char 值：putChar 新旧逐字节对比 ----------
        System.out.println("1) putChar 新旧实现逐字节对比（全部 65536 个 char）");
        byte[] a = new byte[8];
        byte[] b = new byte[8];
        int maxLenCheck = 0;
        for (int v = 0; v <= 0xFFFF; v++) {
            char c = (char) v;
            java.util.Arrays.fill(a, (byte) 0);
            java.util.Arrays.fill(b, (byte) 0);
            int oa = putCharOld(a, 0, c);
            int ob = putCharNew(b, 0, c);
            if (oa != ob || !java.util.Arrays.equals(a, b)) {
                check(false, "char=0x" + Integer.toHexString(v)
                        + " 旧长度=" + oa + " 新长度=" + ob
                        + " 旧字节=" + java.util.Arrays.toString(a)
                        + " 新字节=" + java.util.Arrays.toString(b));
            }
            maxLenCheck = Math.max(maxLenCheck, oa);
        }
        System.out.println("   单字符最大编码长度 = " + maxLenCheck + " 字节（期望 3）");

        // ---------- 2. 穷举全部 char：utfLen 新旧对比（countNonZeroAscii=0） ----------
        System.out.println("2) utfLen 新旧实现对比（单字符串，countNonZeroAscii=0）");
        for (int v = 0; v <= 0xFFFF; v++) {
            String s = String.valueOf((char) v);
            int lo = utfLenOld(s, 0);
            int ln = utfLenNew(s, 0);
            check(lo == ln, "char=0x" + Integer.toHexString(v)
                    + " 旧=" + lo + " 新=" + ln);
        }

        // ---------- 3. 随机组合字符串（含 ASCII / CJK / emoji / NUL 混合） ----------
        System.out.println("3) utfLen 新旧实现对比（随机混合串 20 万组，含各 countNonZeroAscii）");
        java.util.Random rnd = new java.util.Random(20260908L);
        // 典型字符池：ASCII、NUL、2 字节区(0x80-0x7FF)、3 字节区(含 CJK)、代理对(emoji)
        int[] pool = {0x0000, 0x0041, 0x007F, 0x0080, 0x07FF, 0x0800,
                      0x4E2D, 0x6587, 0xFFFF, 0xD83D, 0xDD25, 0xDE80};
        for (int t = 0; t < 200_000; t++) {
            int n = 1 + rnd.nextInt(24);
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                sb.append((char) pool[rnd.nextInt(pool.length)]);
            }
            String s = sb.toString();
            // 模拟真实调用：countNonZeroAscii 取 0..length 的各种取值
            int cnza = rnd.nextInt(s.length() + 1);
            int lo = utfLenOld(s, cnza);
            int ln = utfLenNew(s, cnza);
            if (lo != ln) {
                check(false, "混合串长度=" + s.length() + " cnza=" + cnza
                        + " 旧=" + lo + " 新=" + ln);
                break;
            }
        }

        // ---------- 4. 关键正确性哨兵：U+0000 必须按 C0 80 编码为 2 字节 ----------
        System.out.println("4) NUL 哨兵：U+0000 必须编码为 C0 80（2 字节），不得是单个 0x00");
        byte[] nulBuf = new byte[4];
        int nulLen = putCharNew(nulBuf, 0, '\u0000');
        check(nulLen == 2, "NUL 编码长度应为 2，实际 " + nulLen);
        check((nulBuf[0] & 0xFF) == 0xC0 && (nulBuf[1] & 0xFF) == 0x80,
                "NUL 字节应为 C0 80，实际 " + Integer.toHexString(nulBuf[0] & 0xFF)
                        + " " + Integer.toHexString(nulBuf[1] & 0xFF));
        System.out.println("   NUL 编码 = " + String.format("%02X %02X",
                nulBuf[0] & 0xFF, nulBuf[1] & 0xFF) + "（长度 " + nulLen + "）");

        // ---------- 5. 增补平面 emoji（代理对）3 字节/char ----------
        System.out.println("5) 增补平面 emoji：每个 char（代理项）应编码为 3 字节");
        for (char c : new char[]{'\uD83D', '\uDD25'}) {
            byte[] eb = new byte[4];
            int el = putCharNew(eb, 0, c);
            check(el == 3, "代理项 0x" + Integer.toHexString(c)
                    + " 应为 3 字节，实际 " + el);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("结论：全部等价性检查通过 —— 两处改动未改变任何语义");
        } else {
            System.out.println("结论：存在 " + failures + " 处不一致，改动必须回退！");
            System.exit(1);
        }
    }
}
