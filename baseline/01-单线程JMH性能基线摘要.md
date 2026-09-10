# JMH 序列化基线 v2（优化前）

在未做任何序列化改动的 Tencent Kona JDK 25 release 镜像上测得。
本版是当前唯一的正式基线；早期只有 10 个基准的 v1 数据
（`jmh-baseline-v1-10benches.csv`）仅作备份——两次运行间隔数小时、
机器状态不同，跨会话数值不可直接比较。

- 被测 JDK：`<KONA_ROOT>\build\release\images\jdk`
  （25.0.4-internal，与当前源码一致，无本地改动）
- 机器：12 代 Intel Core i9-12900HX，16 核 / 24 线程，15.7 GB 内存，Windows
- JMH 1.37，fat jar：`jmh/target/serialization-bench.jar`
- 命令（明确使用 Kona JDK 25）：
  `<KONA_ROOT>\build\release\images\jdk\bin\java.exe -jar <LAB_ROOT>\jmh\target\serialization-bench.jar -f 3 -wi 5 -i 8 -w 2s -r 2s -foe true -prof gc -rf csv -rff <LAB_ROOT>\baseline\jmh-baseline.csv`
- 模式：吞吐量（thrpt）、单线程；24 个基准方法 × 3 forks × 8 次正式迭代
  （每基准 24 个样本）；JDK 25 实验性 compiler blackhole 生效

## 测量边界（结果的确切含义）

本套数据更准确的名称是：**内存字节数组场景下的序列化性能基准**。
每个基准方法的“一次操作”定义如下：

- 写侧：`new ByteArrayOutputStream` + `new ObjectOutputStream`
  （构造时写 stream header）+ `writeObject` + 关闭/flush + `toByteArray()`；
- 读侧：`new ByteArrayInputStream` + `new ObjectInputStream` + `readObject`；
- `reuseWrite*`：流实例只构造一次，每轮 `reset()` + `writeObject` + `flush`
  后清空底层缓冲。

因此结果**包含** JDK API 调用、流对象创建/关闭、缓冲管理与
`toByteArray()` 拷贝等生命周期成本，**不代表**剥离一切外壳后的纯
`ObjectOutputStream`/`ObjectInputStream` 内部算法成本。量级证据：
`reuseWriteCustomer` 每 op 分配 288 B，而每次新建流的 `writeCustomer`
为 2 904 B——差额主要就是流对象与缓冲的生命周期开销。

若要测量“纯序列化算法”成本，需要另行设计组件级实验
（预置流、预分配输出、读写底层缓冲等），本基线不宣称代表那种测量。

## 载荷与覆盖的分支

| 载荷 | 模型 | 大小 | 覆盖的序列化分支 |
|---|---:|---:|---|
| Customer | `Payloads$Customer` | 247 B | 基本类型(8 种) + ASCII String |
| Order | `Payloads$Order` | 799 B | 嵌套对象图 + ArrayList\<OrderLine\>(8) |
| Analytics | `Payloads$Analytics` | 29 875 B | int/long/double/String 大数组批量路径 |
| Point | `Payloads$Point`(record) | 111 B | record（canonical 构造器）路径 |
| StringList | `ArrayList<String>` | 1 596 B | 128 个 ASCII 字符串 + 集合自定义 writeObject |
| ConfigMap | `HashMap<String,Object>` | 790 B | HashMap 自定义 writeObject + 键值递归 |
| UnicodeList | `ArrayList<String>` | 2 003 B | 中文(3B)/emoji(6B)/ASCII 混合多字节 UTF-8 |
| Graph | `Payloads$Graph` | 340 B | 循环引用 + 共享引用（TC_REFERENCE 回引） |
| MixedArray | `Payloads$MixedArray` | 676 B | Object[] 引用数组 + 嵌套数组 + null 元素 |
| EnumHolder | `Payloads$EnumHolder` | 274 B | TC_ENUM 枚举 + TC_NULL |
| Derived | `Payloads$DerivedEntity` | 241 B | 父类+子类双数据槽（继承） |

大小与往返语义已由 `PayloadSizeProbe` 校验（值、null、枚举身份、继承字段、
共享引用 `==`、循环成环、中文/emoji 逐项一致）。

## 结果（ops/s，越大越好；±% 为 99.9% 置信区间相对误差）

### 读（反序列化）

| 基准 | ops/s | ±% | B/op |
|---|---:|---:|---:|
| readCustomer | 321 387 | 23.3% | 5 000 |
| readPoint | 512 829 | 12.9% | 3 437 |
| readDerived | 121 898 | 5.6% | 4 424 |
| readEnumHolder | 136 619 | 33.6% | 4 408 |
| readGraph | 80 918 | 12.8% | 5 944 |
| readConfigMap | 66 162 | 14.2% | 14 128 |
| readMixedArray | 27 478 | 14.0% | 12 152 |
| readOrder | 46 293 | 27.5% | 11 112 |
| readStringList | 76 410 | 15.9% | 17 720 |
| readUnicodeList | 37 401 | 12.2% | 17 568 |
| readAnalytics | 7 813 | 38.5% | 129 313 |

### 写（序列化）

| 基准 | ops/s | ±% | B/op |
|---|---:|---:|---:|
| writeCustomer | 889 647 | 7.5% | 2 904 |
| writePoint | 1 304 832 | 7.4% | 2 736 |
| writeDerived | 875 433 | 4.6% | 3 664 |
| writeEnumHolder | 1 124 391 | 2.4% | 3 144 |
| writeGraph | 506 142 | 8.7% | 5 048 |
| writeConfigMap | 380 958 | 6.3% | 8 056 |
| writeMixedArray | 266 714 | 9.5% | 7 840 |
| writeOrder | 223 872 | 2.3% | 8 240 |
| writeStringList | 64 627 | 10.6% | 15 592 |
| writeUnicodeList | 82 479 | 7.8% | 14 192 |
| writeAnalytics | 11 981 | 40.0% | 90 408 |

### 复用流（写侧：同一 ObjectOutputStream + reset 模式）

| 基准 | ops/s | ±% | B/op |
|---|---:|---:|---:|
| reuseWriteCustomer | 1 243 834 | 12.5% | 288 |
| reuseWriteOrder | 229 570 | 8.4% | 1 096 |

机器可读完整结果：[jmh-baseline.csv](jmh-baseline.csv)；
CSV 每列含义见 [03-JMH结果CSV列字段说明.md](03-JMH结果CSV列字段说明.md)。

## 关键观察

1. **多数载荷的读吞吐明显低于写吞吐**，且每次分配约为写侧 1.5~2 倍。小对象
   （Customer/Point/Derived/EnumHolder）固定开销占比高；
2. **多字节 UTF-8 成本明显**：同样是 64~128 个字符串，Unicode 读吞吐
   （37.4K）约是纯 ASCII 读（76.4K）的一半，说明中文/emoji 的 3/6 字节
   解码分支是真实热点；
3. **复用流的收益显著**：reuseWriteCustomer 比“每次新建流”的
   writeCustomer 快约 40%，每次分配从 2 904 B 降到 288 B（约 1/10）；
   这印证了“每次新建流 vs 复用流是两种不同性能特征”的论断；
4. **大数组载荷吞吐低且噪声大**：Analytics 每次分配 90~129 KB，
   GC 主导，误差高达 38~40%。这类基准的判别应以
   `gc.alloc.rate.norm`（误差仅 ±0.1 B）为准，吞吐数值只看趋势；
5. **误差控制仍是首要问题**：多数基准相对误差在 5~15%，少数（Analytics、
   readEnumHolder、readOrder、readCustomer）超过 20%。对这些方法，
   几个百分点的吞吐提升无法被当前基线判别。结论只能走两条路：
   (a) 使用误差仅 ±0.1 B 的 `gc.alloc.rate.norm` 作判据；
   (b) 对吞吐分数要求“提升 ≥ 3×误差”，或先加大样本把误差压到
   可判别范围（如误差 >15% 的方法将迭代数加倍重测）再比；
6. 本轮运行时机、顺序固定，且没有后台构建任务，比 v1 安静；
   跨会话对比仍需在 A/B 紧邻时间内顺序执行。

以上数据即 2.2/2.3 优化循环的正式基准参照。
