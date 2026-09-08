package lab.jvm.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

/**
 * “体检 + 秤”程序：
 * 1. 打印每个基准载荷序列化后的字节大小；
 * 2. 校验每种载荷都能正确往返（round trip）：写出去再读回来，
 *    值、null、枚举、继承字段、共享引用身份都要对。
 *
 * 为什么需要“语义校验”而不只是“能读回来”？
 * 序列化很容易出现“能读但不正确”的情况，例如：
 * - 循环/共享引用被展开成两份（该共享的对象没共享）；
 * - null 变成空对象、枚举变成普通对象、父类字段丢失。
 * 优化前后都跑一遍这个探针，能快速发现这类“看不见的破坏”。
 *
 * 运行方式（用被测 JDK）：
 *   java -cp target/classes lab.jvm.serialization.PayloadSizeProbe
 */
public final class PayloadSizeProbe {

    /** 工具类：禁止实例化 */
    private PayloadSizeProbe() {
    }

    /** 程序入口：先逐个秤大小，再做一遍完整语义校验 */
    public static void main(String[] args) throws Exception {
        // 第一段：报大小（并顺带验证能读回非 null 对象）
        check("Customer", Payloads.newCustomer());
        check("Order", Payloads.newOrder());
        check("Analytics", Payloads.newAnalytics(1024));
        check("Point(record)", new Payloads.Point(3, 4, "origin"));
        check("StringList", Payloads.newStringList(128));
        check("ConfigMap(HashMap)", Payloads.newConfigMap());
        check("UnicodeList", Payloads.newUnicodeList(64));
        check("Graph", Payloads.newGraph());
        check("MixedArray(Object[])", Payloads.newMixedArray());
        check("EnumHolder", Payloads.newEnumHolder());
        check("Derived(inherit)", Payloads.newDerivedEntity());

        // 第二段（阶段 2 对照组）：等字符数 / 等字节数 / NUL / 跨块
        check("Ascii32List", Payloads.newAscii32List(64));
        check("Cjk32List", Payloads.newCjk32List(64));
        check("Emoji16List", Payloads.newEmoji16List(64));
        check("Ascii96List", Payloads.newAscii96List(64));
        check("Nul32List", Payloads.newNul32List(64));
        check("Ascii1020(块内)", Payloads.newAscii1020());
        check("Ascii1030(跨块)", Payloads.newAscii1030());
        check("Ascii4096(多次跨块)", Payloads.newAscii4096());
        check("Cjk350(跨块中文)", Payloads.newCjk350());

        // 第三段：逐项语义校验（抛异常即代表“体检不过”）
        verifyConfigMap();
        verifyUnicodeList();
        verifyGraph();
        verifyMixedArray();
        verifyEnumHolder();
        verifyDerived();
        verifyNulList();

        System.out.println("所有语义校验通过：null/枚举/继承/共享引用/循环引用/中文往返均正确");
        System.out.println("阶段2 对照载荷：NUL(U+0000) 往返正确（modified UTF-8 的 C0 80 规则未破坏）");
    }

    /**
     * 通用“秤”方法：把对象序列化成字节并打印大小。
     *
     * @param name 载荷显示名（打印用）
     * @param o    要序列化的对象
     */
    private static void check(String name, Object o) throws Exception {
        byte[] bytes = serialize(o);
        // 只做“非 null”冒烟：详细语义由 verifyXxx 负责
        if (deserialize(bytes) == null) {
            throw new AssertionError("round trip returned null for " + name);
        }
        System.out.printf("%-22s streamSize=%-8d class=%s%n",
                name, bytes.length, o.getClass().getName());
    }

    // ============================ 语义校验 ============================
    // 原则：不只比较 equals，还要比较“身份（==）”，因为序列化协议规定
    // 同一个对象被引用两次时，读回来必须是同一个对象（而不是复制两份）。

    /** HashMap：键值要都在、值类型正确、null 值还原成 null */
    private static void verifyConfigMap() throws Exception {
        Map<String, Object> original = Payloads.newConfigMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deserialize(serialize(original));

        eq(copy.size(), original.size(), "ConfigMap size");
        eq(copy.get("tenant"), "kona", "ConfigMap tenant");
        eq(copy.get("remark"), "中文备注：序列化性能测试", "ConfigMap 中文值");
        if (!(copy.get("version") instanceof Integer v && v == 25)) {
            throw new AssertionError("ConfigMap version 类型/值不对");
        }
        if (copy.get("owner") == null || !(copy.get("owner") instanceof Payloads.Customer)) {
            throw new AssertionError("ConfigMap owner 丢失或类型不对");
        }
        if (!copy.containsKey("nil") || copy.get("nil") != null) {
            throw new AssertionError("ConfigMap null 值还原不对");
        }
        System.out.println("ConfigMap：键值/null/嵌套对象校验通过");
    }

    /** 中英文列表：长度、顺序、每个字符串内容都要一致 */
    private static void verifyUnicodeList() throws Exception {
        List<String> original = Payloads.newUnicodeList(64);
        @SuppressWarnings("unchecked")
        List<String> copy = (List<String>) deserialize(serialize(original));

        eq(copy.size(), original.size(), "UnicodeList 长度");
        for (int i = 0; i < original.size(); i++) {
            if (!original.get(i).equals(copy.get(i))) {
                throw new AssertionError("UnicodeList 第 " + i + " 项不一致: "
                        + original.get(i) + " != " + copy.get(i));
            }
        }
        System.out.println("UnicodeList：中文/emoji/混合字符串逐一校验通过");
    }

    /** 对象图：环要还原成环、共享引用要还原成同一个对象（身份 ==） */
    private static void verifyGraph() throws Exception {
        Payloads.Graph copy = (Payloads.Graph) deserialize(serialize(Payloads.newGraph()));

        // 环：n1 -> n2 -> n3 -> n1，走三步应回到同一个 head 对象
        Payloads.GraphNode head = copy.cycleHead;
        if (head == null || head.next == null || head.next.next == null
                || head.next.next.next != head) {
            throw new AssertionError("循环引用没有还原成环（身份不一致）");
        }
        // 共享引用：两个字段必须指向同一个对象
        if (copy.sharedA == null || copy.sharedA != copy.sharedB) {
            throw new AssertionError("共享引用没有还原成同一个对象");
        }
        if (!"shared-leaf".equals(copy.sharedA.tag)) {
            throw new AssertionError("共享节点内容不对");
        }
        System.out.println("Graph：循环引用(3节点环)与共享引用(同一对象)校验通过");
    }

    /** Object[]：长度、null 位置、嵌套数组、嵌套对象都要对 */
    private static void verifyMixedArray() throws Exception {
        Payloads.MixedArray copy =
                (Payloads.MixedArray) deserialize(serialize(Payloads.newMixedArray()));

        Object[] items = copy.items;
        if (items == null || items.length != 9) {
            throw new AssertionError("Object[] 长度不对");
        }
        eq(items[0], "hello ascii", "Object[] 元素0");
        eq(items[1], "中文：你好，世界", "Object[] 中文元素");
        if (!(items[6] instanceof int[] arr && arr.length == 3)) {
            throw new AssertionError("Object[] 嵌套 int[] 丢失或错误");
        }
        if (!(items[7] instanceof Payloads.Customer)) {
            throw new AssertionError("Object[] 嵌套 Customer 丢失");
        }
        if (items[8] != null) {
            throw new AssertionError("Object[] 里的 null 元素没有还原成 null");
        }
        System.out.println("MixedArray：Object[] 长度/嵌套/null 校验通过");
    }

    /** 枚举：必须还原成同一个枚举常量（==），null 字段还原成 null */
    private static void verifyEnumHolder() throws Exception {
        Payloads.EnumHolder copy =
                (Payloads.EnumHolder) deserialize(serialize(Payloads.newEnumHolder()));

        if (copy.light != Payloads.TrafficLight.GREEN) {
            throw new AssertionError("枚举没有还原成同一个常量（期望 GREEN）");
        }
        if (copy.note != null || copy.ghost != null) {
            throw new AssertionError("null 字段没有还原成 null");
        }
        System.out.println("EnumHolder：枚举身份(TrafficLight.GREEN)与 null 字段校验通过");
    }

    /** 继承：父类字段和子类字段都要还原 */
    private static void verifyDerived() throws Exception {
        Payloads.DerivedEntity copy =
                (Payloads.DerivedEntity) deserialize(serialize(Payloads.newDerivedEntity()));

        eq(copy.baseId, 7L, "父类 long 字段");
        eq(copy.baseName, "基础实体-名称", "父类中文 String 字段");
        eq(copy.childCount, 3, "子类 int 字段");
        eq(copy.childNote, "子类备注", "子类中文 String 字段");
        System.out.println("Derived：父类槽与子类槽字段全部校验通过");
    }

    /**
     * NUL 串：U+0000 必须原样往返。
     *
     * 这条校验是阶段 2 的硬约束：modified UTF-8 规定 U+0000 要编成
     * C0 80 两个字节（不能是标准 UTF-8 的单个 0x00）。将来若有人为了提速
     * 把编解码换成标准 UTF-8，这里会立刻失败——所以它是优化的安全阀。
     */
    private static void verifyNulList() throws Exception {
        List<String> original = Payloads.newNul32List(64);
        @SuppressWarnings("unchecked")
        List<String> copy = (List<String>) deserialize(serialize(original));

        eq(copy.size(), original.size(), "Nul32List 长度");
        for (int i = 0; i < original.size(); i++) {
            String o = original.get(i);
            String c = copy.get(i);
            if (!o.equals(c)) {
                throw new AssertionError("Nul32List 第 " + i + " 项不一致");
            }
            if (o.indexOf('\u0000') < 0 || c.indexOf('\u0000') < 0) {
                throw new AssertionError("Nul32List 第 " + i + " 项不含 U+0000");
            }
        }
        System.out.println("Nul32List：U+0000 往返一致（含 NUL 位置校验）");
    }

    // ============================ 公共小工具 ============================

    /** 把对象序列化成字节数组 */
    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(o);
        }
        return out.toByteArray();
    }

    /** 把字节数组反序列化成对象 */
    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    /** 断言两个对象 equals（不等就抛错，报错时带说明） */
    private static void eq(Object actual, Object expected, String what) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(what + " 不一致: expected=" + expected + ", actual=" + actual);
        }
    }

    /** 断言两个 long 相等（基本类型装箱版） */
    private static void eq(long actual, long expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + " 不一致: expected=" + expected + ", actual=" + actual);
        }
    }

    /** 断言两个 int 相等 */
    private static void eq(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + " 不一致: expected=" + expected + ", actual=" + actual);
        }
    }
}
