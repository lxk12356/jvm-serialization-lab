/**
 * CPU 实际算力探测：单线程整数运算，粗略推算当前有效频率。
 * 仅用于判断机器是否处于睿频正常状态（对比：i9-12900HX 单核睿频 ~5GHz）。
 */
public class CpuProbe {
    public static void main(String[] args) throws Exception {
        // warmup JIT
        long w = 0;
        for (int i = 0; i < 50_000_000; i++) w += i;
        System.out.println("warmup done");

        // 3 轮测量：固定 4 亿次依赖链加法
        for (int r = 0; r < 3; r++) {
            long x = 0;
            int N = 400_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                x += i * 3L + (x >> 1);  // 依赖链，防完全向量化
            }
            long t1 = System.nanoTime();
            double ms = (t1 - t0) / 1e6;
            System.out.printf("round %d: 4e8 依赖加法链 = %.1f ms → %.2f ns/op (x=%d)%n",
                    r, ms, ms * 1e6 / N, x);
        }
    }
}
