/**
 * 持续负载热墙探测 v2：x 累计到段外变量（阻止 JIT 消除循环），
 * 每段约 4 亿次依赖链运算，打印各段耗时，观察是否随时间下滑。
 */
public class CpuProbeLong {
    public static void main(String[] args) throws Exception {
        long w = 0;
        for (int i = 0; i < 50_000_000; i++) w += i;

        int SEG = 400_000_000;
        long acc = 0;
        double totalSec = 0;
        for (int seg = 0; seg < 10; seg++) {
            long x = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < SEG; i++) {
                x += i * 3L + (x >> 1);
            }
            long t1 = System.nanoTime();
            acc += x;
            double ms = (t1 - t0) / 1e6;
            totalSec += ms / 1000.0;
            System.out.printf("seg %2d: 4e8 ops = %6.1f ms (%.2f ns/op)  cumulative %5.1f s%n",
                    seg, ms, ms * 1e6 / SEG, totalSec);
        }
        System.out.println("acc=" + acc + " (结果逃逸，防优化)");
    }
}
