package lab.jvm.serialization;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JMH 序列化基准要用的“测试对象”，专业术语叫 payload（载荷）。
 *
 * 给小白解释一下几个词：
 * - 序列化（serialize）：把内存里的 Java 对象变成一串字节，方便存文件或发网络；
 * - 反序列化（deserialize）：把这串字节再还原成 Java 对象；
 * - 载荷：就是这串被序列化/反序列化的对象本身。
 *
 * 为什么这里要设计好几种对象？
 * 因为 JDK 序列化对不同类型数据的处理方式不同、开销也不同，比如：
 * int 是固定 4 字节，String 是变长 UTF-8，数组/集合要逐个元素处理，
 * 普通对象图还要记录“同一个对象被引用了多次”之类的关系。
 * 所以下面的每个类都故意混合不同类型字段，尽量把各种热点都覆盖到。
 *
 * 这些类都只实现了 Serializable 标记接口、没有自定义 writeObject/readObject，
 * 也就是说它们走 JDK 默认序列化路径——我们测的正是 JDK 自带实现的性能。
 */
public final class Payloads {

    /** 工具类：禁止实例化（只有静态方法，不需要对象） */
    private Payloads() {
    }

    /**
     * 载荷 1：小业务对象“客户 Customer”。
     *
     * 字段故意覆盖 8 种基本类型 + 2 个 String：
     * - 基本类型测 1/2/4/8 字节的定长编码与字节序处理；
     * - String 测 UTF-8 变长编码和对象引用记录。
     * 序列化后整条流约 247 字节，属于“小而高频”的业务对象。
     */
    public static final class Customer implements Serializable {
        /**
         * 序列化版本号（serialVersionUID）。
         * Serializable 类最好显式声明它：版本号不一致时 JDK 会拒绝反序列化，
         * 这是防止“老数据”被“新代码”误读的安全机制。
         */
        private static final long serialVersionUID = 1L;

        /** 客户 ID（long，8 字节）：测大整数/时间戳类字段 */
        public long id;
        /** 客户姓名（String）：测字符串的 UTF-8 编码 */
        public String name;
        /** 客户邮箱（String）：同一个类里第二个引用字段，测重复处理引用字段的开销 */
        public String email;
        /** 年龄（int，4 字节） */
        public int age;
        /** 是否 VIP（boolean，1 字节） */
        public boolean vip;
        /** 账户余额（double，8 字节浮点） */
        public double balance;
        /** 折扣（float，4 字节浮点） */
        public float discount;
        /** 地区编号（short，2 字节） */
        public short region;
        /** 会员等级（byte，1 字节） */
        public byte tier;
        /** 性别（char，2 字节字符） */
        public char gender;
        /** 最近登录时间戳（long）：时间戳通常很大，测大整数写入 */
        public long lastLogin;

        /** 默认构造器：JDK 反序列化在多数情况下不调用它，但保留它便于代码自解释 */
        public Customer() {
        }
    }

    /**
     * 地址对象：在 Order 里只被引用一次，用来测“嵌套对象”路径。
     */
    public static final class Address implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 国家（String） */
        public String country;
        /** 城市（String） */
        public String city;
        /** 街道（String） */
        public String street;
        /** 邮编（String）：邮编经常以 0 开头，只能用字符串存 */
        public String zip;
        /** 门牌号（int） */
        public int door;
        /** 是否已核验（boolean） */
        public boolean verified;

        public Address() {
        }
    }

    /**
     * 订单行：一个商品条目，会被放进 ArrayList 里。
     */
    public static final class OrderLine implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 商品编码（String，形如 "SKU-1000"） */
        public String sku;
        /** 数量（int） */
        public int qty;
        /** 单价（double） */
        public double unitPrice;

        public OrderLine() {
        }
    }

    /**
     * 载荷 2：嵌套对象图“订单 Order”。
     *
     * 它同时包含：
     * - 基本类型字段；
     * - 引用类型字段（Address）；
     * - 集合字段（ArrayList<OrderLine>，8 个订单行）。
     *
     * “对象图”指对象之间互相引用的网状结构。序列化对象图比序列化单个对象
     * 复杂：要一层层往下走，还要记住哪些对象已经写过（防止重复写、循环引用）。
     * 序列化后约 799 字节，测的是典型业务聚合根。
     */
    public static final class Order implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 订单号（long） */
        public long orderId;
        /** 下单客户名（String） */
        public String customerName;
        /** 收货地址（引用对象，Address） */
        public Address address;
        /** 订单行集合：ArrayList 有自己的 writeObject 逻辑，测“自定义集合”路径 */
        public List<OrderLine> lines;
        /** 下单时间戳（long） */
        public long createdEpochMillis;
        /** 订单总额（double） */
        public double total;
        /** 是否已支付（boolean） */
        public boolean paid;

        public Order() {
        }
    }

    /**
     * 载荷 3：数组密集型“统计数据 Analytics”。
     *
     * 业务上常有一批批量数据（埋点、指标、时间序列），它们往往就是大数组。
     * 这里用 4 个长度相同的数组（int/long/double/String 各 1024 个），
     * 重点测 JDK 对基本类型数组的“批量读写”路径：
     * 如果只是逐元素慢慢读写，性能会很差；优化目标是尽量一次处理一大段。
     * 序列化后约 29.9 KB，是所有载荷里最大的。
     */
    public static final class Analytics implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 会话 ID（long） */
        public long sessionId;
        /** 计数数组（int[1024]）：4 字节元素批量读写 */
        public int[] counts;
        /** 时间戳数组（long[1024]）：8 字节元素批量读写 */
        public long[] timestamps;
        /** 数值数组（double[1024]）：8 字节浮点批量读写 */
        public double[] values;
        /** 标签数组（String[1024]）：引用数组，元素是字符串对象 */
        public String[] tags;

        public Analytics() {
        }
    }

    /**
     * 载荷 4：Java record（坐标点）。
     *
     * record 是 Java 16+ 的“不可变数据类”，语法更简洁，编译器自动生成
     * 构造器/equals/hashCode/toString。它和普通类最大的区别是：
     * 反序列化时 JDK 会调用 canonical（标准）构造器来重建对象，
     * 所以 record 走的是另一条代码路径，需要单独测。
     */
    public record Point(int x, int y, String label) implements Serializable {
        public Point {
            // 紧凑构造器：保持序列化形态稳定（不加校验，避免干扰性能测试）
        }
    }

    /** 创建一个内容固定的 Customer（数字都写死，保证每次基准测的是同一份数据） */
    public static Customer newCustomer() {
        Customer c = new Customer();
        c.id = 9007199254740991L;              // 2^53-1：典型大 ID
        c.name = "Ada Lovelace";
        c.email = "ada@example.com";
        c.age = 36;
        c.vip = true;
        c.balance = 184467.44073709551d;       // 带小数，测浮点编码
        c.discount = 0.85f;
        c.region = 4401;                       // 邮编区号一类数值
        c.tier = 7;
        c.gender = 'F';
        c.lastLogin = 1756893600000L;          // 毫秒时间戳
        return c;
    }

    /** 创建一个内容固定的 Order 对象图（1 个 Address + 8 个订单行） */
    public static Order newOrder() {
        Address addr = new Address();
        addr.country = "CN";
        addr.city = "Shanghai";
        addr.street = "999 Xiu Yan Road";
        addr.zip = "200126";
        addr.door = 17;
        addr.verified = true;

        // 用 for 循环造 8 个订单行，行数固定，避免每次运行生成不同数据
        List<OrderLine> lines = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            OrderLine line = new OrderLine();
            line.sku = "SKU-" + (1000 + i * 7);
            line.qty = i + 1;
            line.unitPrice = 19.99d + i * 3.25d;
            lines.add(line);
        }

        Order o = new Order();
        o.orderId = 1234567890123456789L;
        o.customerName = "Ada Lovelace";
        o.address = addr;
        o.lines = lines;
        o.createdEpochMillis = 1756893600000L;
        o.total = 99999.99d;
        o.paid = true;
        return o;
    }

    /**
     * 创建一个数组内容确定的 Analytics。
     *
     * @param n 每个数组的元素个数（基准里传 1024）
     */
    public static Analytics newAnalytics(int n) {
        Analytics a = new Analytics();
        a.sessionId = 42424242424242L;
        a.counts = new int[n];
        a.timestamps = new long[n];
        a.values = new double[n];
        a.tags = new String[n];
        for (int i = 0; i < n; i++) {
            a.counts[i] = i * 31;
            a.timestamps[i] = 1756893600000L + i * 1000L;
            a.values[i] = i * 1.5d;
            a.tags[i] = "tag" + i;
        }
        return a;
    }

    /**
     * 生成一串“互不相同的字符串”，模拟真实业务里的字符串列表。
     *
     * 注意要点：字符串必须互不相同。
     * 如果都相同，JVM 可能把它们合并成同一个对象（字符串驻留/引用共享），
     * 那就测不出真实编码成本了。这里用 i 混合一个哈希值制造不同后缀。
     *
     * @param n 字符串个数（基准里传 128）
     */
    public static List<String> newStringList(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add("key-" + i + "-" + Integer.toHexString(i * 2654435761L > 0 ? i : i + 1));
        }
        return list;
    }

    // ============================ 以下为补充载荷 ============================
    // 补充动机（针对前面基准没覆盖到的序列化分支）：
    // 1) HashMap：真实业务最常用的容器之一，走“自定义 writeObject + 逐键值递归”路径；
    // 2) 中文/emoji 多字节字符串：走 modified UTF-8 的 2/3/6 字节编码分支；
    // 3) 循环引用/共享引用：第二次遇到同一对象时走句柄表 TC_REFERENCE 回引分支；
    // 4) Object[]：引用数组，与基本类型数组（int[] 等）是完全不同的代码分支；
    // 5) null / 枚举：各自是 TC_NULL / TC_ENUM 独立分支；
    // 6) 继承：父类+子类各自有数据槽，读侧要按层级循环处理。

    /**
     * 枚举：红灯/黄灯/绿灯。
     * 枚举序列化只写名字（TC_ENUM + 类描述 + 常量名），反序列化用
     * valueOf 找回同一个常量对象，和普通对象完全不同。
     */
    public enum TrafficLight implements Serializable {
        RED, YELLOW, GREEN
    }

    /**
     * 枚举 + null 载体。
     * note/ghost 故意为 null：null 对象字段序列化时只写 1 个 TC_NULL 标记，
     * 不写任何内容，是独立且常见的分支。
     */
    public static final class EnumHolder implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 枚举字段（GREEN）：测 TC_ENUM 分支 */
        public TrafficLight light;
        /** 为 null 的 String 字段：测 TC_NULL 分支 */
        public String note;
        /** 为 null 的 Object 字段：再测一次 TC_NULL */
        public Object ghost;

        public EnumHolder() {
        }
    }

    /**
     * 父类（可序列化基类）。
     * 继承测试的关键：父类字段和子类字段在序列化流里分成两个“数据槽”，
     * 读侧按层级逐个还原。把基类字段命名为 baseXxx 便于识别。
     */
    public static class BaseEntity implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 父类字段：long 基本类型 */
        public long baseId;
        /** 父类字段：含中文的 String */
        public String baseName;

        public BaseEntity() {
        }
    }

    /**
     * 子类：继承 BaseEntity。
     * 序列化 DerivedEntity 时会同时写出父类槽和子类槽两段数据。
     */
    public static final class DerivedEntity extends BaseEntity {
        private static final long serialVersionUID = 1L;

        /** 子类字段：int */
        public int childCount;
        /** 子类字段：含中文的 String */
        public String childNote;

        public DerivedEntity() {
        }
    }

    /**
     * 图中的节点：每个节点有一个指向下一节点的引用（next）。
     * 用它构造“环”：a → b → c → a，第三次写 a 时触发 TC_REFERENCE 回引。
     */
    public static final class GraphNode implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 节点名（String） */
        public String tag;
        /** 指向下一个节点的引用；把引用接成环就是循环引用 */
        public GraphNode next;

        public GraphNode() {
        }
    }

    /**
     * 对象图根：一次覆盖“循环引用 + 共享引用”两个分支。
     *
     * - cycleHead：一个 3 节点环（tail.next 指回 head），测循环引用；
     * - sharedA / sharedB：两个字段指向同一个节点，测共享引用
     *   （第二个字段写的时候只写“第几号对象”的回引标记，不再重复写内容）。
     */
    public static final class Graph implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 图的名字（String） */
        public String name;
        /** 环的入口节点：3 个节点首尾相接 */
        public GraphNode cycleHead;
        /** 共享节点引用 1 */
        public GraphNode sharedA;
        /** 共享节点引用 2（与 sharedA 是同一个对象） */
        public GraphNode sharedB;

        public Graph() {
        }
    }

    /**
     * Object[] 混合数组载体。
     * Object[]（引用数组）序列化时逐元素递归 writeObject0，和 int[]/long[]
     * 那种“批量拷贝”路径完全不同，所以必须单独测。
     */
    public static final class MixedArray implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 混合元素数组：字符串/数字/包装类型/嵌套数组/对象/null 全放进去 */
        public Object[] items;

        public MixedArray() {
        }
    }

    /** 创建一个枚举 + null 载体对象（light=GREEN，note/ghost 都是 null） */
    public static EnumHolder newEnumHolder() {
        EnumHolder h = new EnumHolder();
        h.light = TrafficLight.GREEN;
        h.note = null;   // 故意留空
        h.ghost = null;  // 故意留空
        return h;
    }

    /** 创建一个带继承层次的对象（父类 2 个字段 + 子类 2 个字段） */
    public static DerivedEntity newDerivedEntity() {
        DerivedEntity d = new DerivedEntity();
        d.baseId = 7L;
        d.baseName = "基础实体-名称";
        d.childCount = 3;
        d.childNote = "子类备注";
        return d;
    }

    /**
     * 创建一个同时含“循环引用”和“共享引用”的对象图。
     *
     * @return Graph 根对象
     */
    public static Graph newGraph() {
        // 3 个节点接成环：n1 → n2 → n3 → n1
        GraphNode n1 = new GraphNode();
        GraphNode n2 = new GraphNode();
        GraphNode n3 = new GraphNode();
        n1.tag = "node-1";
        n2.tag = "node-2";
        n3.tag = "node-3";
        n1.next = n2;
        n2.next = n3;
        n3.next = n1; // 回引：形成循环引用

        // 共享节点：sharedA 和 sharedB 指向同一个对象
        GraphNode shared = new GraphNode();
        shared.tag = "shared-leaf";

        Graph g = new Graph();
        g.name = "reference-graph";
        g.cycleHead = n1;
        g.sharedA = shared;
        g.sharedB = shared;
        return g;
    }

    /** 创建一个 Object[] 混合数组（9 个元素，含 null 和嵌套数组） */
    public static MixedArray newMixedArray() {
        MixedArray m = new MixedArray();
        m.items = new Object[]{
                "hello ascii",            // 0: 纯 ASCII 字符串
                "中文：你好，世界",        // 1: 中文（modified UTF-8 3 字节/字）
                Integer.valueOf(42),       // 2: 包装类型 Integer
                Long.valueOf(9007199254740991L), // 3: 包装类型 Long
                Double.valueOf(3.14159d),  // 4: 包装类型 Double
                Boolean.TRUE,              // 5: 包装类型 Boolean
                new int[]{1, 2, 3},        // 6: 嵌套的基本类型数组（数组套数组）
                newCustomer(),             // 7: 嵌套一个普通业务对象
                null                       // 8: null 元素 → TC_NULL
        };
        return m;
    }

    /**
     * 生成一串“含中文/emoji/纯 ASCII 混合”的字符串列表。
     *
     * 任务要求覆盖“中英文场景”，所以特意包含：
     * - 纯中文：每个汉字在 modified UTF-8 里占 3 字节；
     * - ASCII+中文混合；
     * - emoji（增补平面字符）：会拆成两个代理项，modified UTF-8 里占 6 字节，
     *   走最长的编码/解码分支。
     * 这些字符串都各不相同，避免 JVM 字符串驻留把成本抹掉。
     *
     * @param n 字符串个数（基准里传 64）
     */
    public static List<String> newUnicodeList(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int r = i % 4;
            if (r == 0) {
                list.add("中文订单编号：" + i + "，蓝牙耳机与键盘");      // 纯中文
            } else if (r == 1) {
                list.add("SKU-" + (1000 + i) + " 特价商品 2026");       // ASCII + 中文混合
            } else if (r == 2) {
                list.add("emoji🔥测试" + i + "🚀");                     // 含增补平面字符（emoji）
            } else {
                list.add("plain-key-" + i);                             // 纯 ASCII 对照
            }
        }
        return list;
    }

    /**
     * 创建一个“业务配置型” HashMap<String,Object>。
     *
     * HashMap 的键值类型故意混合：String/Integer/Long/Double/Boolean、
     * 中文串、ArrayList<String>、嵌套 Customer，还有一个 null 值。
     * HashMap 自带 writeObject（要遍历桶数组逐键值写），所以它测的是
     * “集合自定义序列化 + 键值递归”路径，非常贴近真实配置/缓存类对象。
     */
    public static Map<String, Object> newConfigMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("tenant", "kona");
        m.put("region", "cn-shanghai");
        m.put("env", "prod");
        m.put("version", Integer.valueOf(25));
        m.put("instances", Long.valueOf(3));
        m.put("ratio", Double.valueOf(0.618d));
        m.put("enabled", Boolean.TRUE);
        m.put("remark", "中文备注：序列化性能测试");
        m.put("tags", new ArrayList<>(List.of("java", "序列化", "jmh")));
        m.put("owner", newCustomer());  // 值里嵌一个完整业务对象
        m.put("nil", null);             // null 值 → TC_NULL
        return m;
    }

    // ============================ 阶段 2 对照载荷 ============================
    // 要回答的问题：v2 基线里"Unicode 读吞吐只有 ASCII 一半"是不是字符集造成的？
    // 现有载荷答不了：UnicodeList 是 64 个元素约 2 003 B，StringList 是 128 个
    // 元素约 1 596 B——元素数和字节量都不同，两个变量混在一起。
    //
    // 所以这里按"单变量"原则设计几组严格对照：
    //
    // 组 1（等 UTF-16 长度，隔离编码宽度）
    //   ascii32 / cjk32 / emoji16 每串都是 32 个 char，但 modified UTF-8 下一个
    //   码点分别占 1B / 3B / 6B，于是单串字节数为 32B / 96B / 96B。
    //   - ascii32 vs cjk32：字符数相同，字节量 3 倍；
    //   - cjk32   vs emoji16：字节量相同（96B），解码单元不同
    //     （32 个 3 字节序列 vs 16 个 6 字节代理对）。
    //
    // 组 2（等字节量，隔离解码分支）
    //   ascii96 与 cjk32、emoji16 单串都是 96B，只有字符集不同，用来判断
    //   "搬同样多字节时，不同解码分支差多少"。
    //
    // 组 3（特殊字符，兼作正确性约束）
    //   nul32 含 U+0000。modified UTF-8 必须把它编成 C0 80 两个字节，绝不能
    //   写成标准 UTF-8 的单个 0x00——这条规则是"不许拿标准 UTF-8 顶替"的硬约束，
    //   专门用来验证优化后有没有被破坏。
    //
    // 组 4（跨块边界）
    //   序列化内部块缓冲 MAX_BLOCK_SIZE = 1024，长字符串会被切成 span 分块写。
    //   长度落在 1024 前后的串用来观察 span 分割与块头写入的成本。

    /** 等长度组每串的 UTF-16 长度（统一 32 个 char） */
    public static final int EQ_CHARS = 32;
    /** 等字节组每串的目标字节数（96 B，与 cjk32、emoji16 对齐） */
    public static final int EQ_BYTES_PER_ITEM = 96;

    /** 造一个随序号变化的十六进制尾巴，保证同一批字符串互不相同 */
    private static String tail(int i) {
        return Long.toHexString(i * 2654435761L + i);
    }

    /** 把字符串补齐/截断到指定长度；filler 用来控制字符所属字符集 */
    private static String pad(String s, int target, char filler) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < target) {
            sb.append(filler);
        }
        return sb.length() > target ? sb.substring(0, target) : sb.toString();
    }

    /** 纯 ASCII：n 串 × 32 字符（32 B/串） */
    public static List<String> newAscii32List(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(pad("ascii-key-" + i + "-" + tail(i), EQ_CHARS, 'x'));
        }
        return list;
    }

    /** 把 0~99 写成汉字（壹贰叁…）：保证中文载荷里不混入 ASCII 数字，字节量才精确 */
    private static String cjkNum(int i) {
        char[] d = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};
        if (i < 10) {
            return String.valueOf(d[i]);
        }
        return String.valueOf(d[i / 10]) + d[i % 10];
    }

    /**
     * 纯中文：n 串 × 32 汉字（96 B/串，modified UTF-8 下 3 B/字）。
     * 序号也用汉字写，避免混入 1 B 的 ASCII 数字导致与 ascii96 / emoji16
     * 的"等字节"对照失准——三组必须字节数完全相同才有可比性。
     */
    public static List<String> newCjk32List(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(pad("中文订单编号" + cjkNum(i) + "号蓝牙耳机与键盘鼠标", EQ_CHARS, '中'));
        }
        return list;
    }

    /**
     * 纯增补平面 emoji：n 串 × 16 个 emoji（= 32 char，96 B/串；
     * modified UTF-8 下一个增补平面字符占 6 B，拆成两个代理项）。
     */
    public static List<String> newEmoji16List(int n) {
        String[] pool = {"\uD83D\uDD25", "\uD83D\uDE80", "\uD83C\uDF89", "\uD83D\uDCA1"};
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < EQ_CHARS / 2; j++) {
                sb.append(pool[(i + j) % pool.length]);
            }
            list.add(sb.toString());
        }
        return list;
    }

    /** 等字节 ASCII 对照：n 串 × 96 字符（96 B/串，与 cjk32、emoji16 等字节） */
    public static List<String> newAscii96List(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(pad("ascii-key-" + i + "-" + tail(i), EQ_BYTES_PER_ITEM, 'x'));
        }
        return list;
    }

    /** 含 U+0000 的 ASCII 串：n 串 × 32 字符，每个串中间插一个 NUL */
    public static List<String> newNul32List(int n) {
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(pad("key-" + i + "-" + tail(i), EQ_CHARS - 1, 'x'));
            sb.insert(EQ_CHARS / 2, '\u0000');
            list.add(sb.toString());
        }
        return list;
    }

    /** 块内长串：1020 个 ASCII 字符（块边界 1024，不跨块） */
    public static String newAscii1020() {
        return pad("blk-" + tail(1), 1020, 'a');
    }

    /** 跨块长串：1030 个 ASCII 字符（刚好越过 1024 块边界） */
    public static String newAscii1030() {
        return pad("blk-" + tail(2), 1030, 'b');
    }

    /** 多次跨块长串：4096 个 ASCII 字符 */
    public static String newAscii4096() {
        return pad("blk-" + tail(3), 4096, 'c');
    }

    /** 跨块中文长串：350 个汉字 = 1050 B（越过 1024 块边界） */
    public static String newCjk350() {
        return pad("跨块中文负载", 350, '中');
    }
}
