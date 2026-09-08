# 任务二：序列化加速（Tencent Kona JDK 25）

本目录存放序列化加速工作的测试、基准与报告。

> **审查者请先读**：[reports/0-审查者导读.md](reports/0-审查者导读.md)
> （含阅读路径、代码改动查看方式、证据地图与跳转链接）。
> **最终结论**：[reports/5-最终交付报告.md](reports/5-最终交付报告.md)。
> 报告按编号阅读：`reports/0-审查者导读 → 1-优化规划方案 → 2-优化实施方案 →
> 3-第一轮实施记录 → 4-优化实验全记录 → 5-最终交付报告`。

## 固定环境

- JDK 镜像：`D:/kona/TencentKona-25/build/release/images/jdk`
- JDK 源码：`D:/kona/TencentKona-25`
- jtreg：`D:/kona/jtreg`
- JMH 工程：`jmh/`

性能测试一律使用上面的 Kona JDK 25 release 镜像。

## 当前状态（2026-09-09：2.2 优化已完成，2.3 优化性能与差异分析已完成）

## ✅ 任务书逐条完成情况（速览，按任务书原文 2.x 结构）

> 详细过程见下方各节与 [0-审查者导读](reports/0-审查者导读.md)；
> 最终结论见 [5-最终交付报告](reports/5-最终交付报告.md)。

**2. 序列化加速 —— ✅ 整体已完成**（不改变协议与对象语义，两处源码优化均已落地验证）

- **2.1 获取测试基准 —— ✅ 已完成（2026-09-07）**
  - 构建 Kona JDK：✅ release 镜像增量构建并核验（`TencentKona-25/build/release/images/jdk`）。
  - 执行相关 jtreg 测试得到基准测试：✅ **160/160 通过**（Serializable + ObjectInputStream +
    ObjectStreamClass），见 [04-jtreg功能回归基线摘要](baseline/04-jtreg功能回归基线摘要.md)。
  - 编写 JMH 程序测试序列化性能并得到基准性能：✅ **24 个 JMH 基准**（单线程主矩阵，
    6 类语义场景 × 读写）+ **12 代表方法 × 8 线程**（并发梯度），见
    [01-单线程JMH性能基线摘要](baseline/01-单线程JMH性能基线摘要.md)、
    [02-并发8线程JMH基线摘要](baseline/02-并发8线程JMH基线摘要.md) 及对应 CSV。

- **2.2 使用 CodeBuddy 优化 —— ✅ 已完成（2026-09-08~09）**
  - 规划方案：✅ [1-优化规划方案](reports/1-优化规划方案.md)（总规划，2026-09-07）+
    [2-优化实施方案](reports/2-优化实施方案.md)（动刀前细化到文件行，2026-09-08）。
    两文件均为历史文档，文件头已标注"实际已实施完成"。
  - 实现代码并执行测试：✅ **两处实现、均已提交**：
    - 写侧 UTF 微优化（`fecbb593236`）：`writeMoreUTF` 内联 + `utfLen` 分支序，
      writeCjk32 **+3.0%**（紧邻 A/B 显著）；jtreg 154/154；
    - 读侧 StringBuilder 复用（`08bc7650aa5`）：`readUTFBody` 消除每串 new StringBuilder，
      字符串读 B/op **−22%~−54%**；jtreg 160/160、语义穷举等价。
    - 全程记录：[3-第一轮实施记录](reports/3-第一轮实施记录.md) +
      [4-优化实验全记录](reports/4-优化实验全记录.md)。

- **2.3 进一步改进 —— ✅ ①② 已完成，③ 分析结论已出、是否再动一刀待决策**
  - 对优化实现执行 JMH 得到优化性能：✅ 优化后镜像重跑全量 24 基准
    （[jmh-fullbench-opt.csv](reports/fullbench-opt/jmh-fullbench-opt.csv)：读侧 B/op **−17.1%**、
    总 **−9.9%**）+ 并发 t8（[jmh-concurrency-t8-opt.csv](reports/fullbench-opt/jmh-concurrency-t8-opt.csv)：
    **−10.5%** 无回退）。
  - 使用 CodeBuddy 分析性能差异：✅ 差异归因完成——降幅与载荷字符串含量严格相关；
    无字符串载荷也受益（类描述符同路径）；写侧 0.00% 符合代码性质；
    吞吐仅采信同会话紧邻 A/B。详见 [4-优化实验全记录 §5](reports/4-优化实验全记录.md)、
    [5-最终交付报告 §8](reports/5-最终交付报告.md)。
  - 根据分析结果做进一步的改进：✅ 分析给出明确结论——读侧 per-string 分配已近下限
    （CJK 与 ASCII 优化后仅差 ~9%）、写侧 1.88× 为结构性成本（微观到上限），
    **据此收口**；唯一剩余候选 FieldReflector 字段直写（预期 ≤3-5%）证据弱于已完成项，
    **是否再开一刀待用户拍板**（见下方 2.4）。

### 2.1 获取测试基准 —— 已完成

- JDK 构建：release 镜像已由当前源码增量重建并核验（工作树无本地改动，
  分支 `task2-serialization-accel` 内容与 HEAD 一致）。
- jtreg 基线：`test/jdk/java/io/Serializable` +
  `test/jdk/java/io/ObjectInputStream` + `test/jdk/java/io/ObjectStreamClass`
  共 **160/160 通过**。报告见
  [jtreg/results/baseline-jtreg/](jtreg/results/baseline-jtreg/)，
  摘要见 [baseline/04-jtreg功能回归基线摘要.md](baseline/04-jtreg功能回归基线摘要.md)。
- JMH 工程：`jmh/`（pom 与源码在 `jmh/src/main/java/...`，可执行 fat jar
  为 `jmh/target/serialization-bench.jar`）。
  共 24 个基准方法：11 种载荷 × 读写（Customer/Order/Analytics/Point/
  StringList/ConfigMap/UnicodeList/Graph/MixedArray/EnumHolder/Derived）
  外加 `reuseWriteCustomer / reuseWriteOrder`（复用流场景）。
- JMH 基线 v2：24 个基准、3 forks、5w+8i×2s、`-prof gc`。结果见
  [baseline/jmh-baseline.csv](baseline/jmh-baseline.csv)，
  摘要见 [baseline/01-单线程JMH性能基线摘要.md](baseline/01-单线程JMH性能基线摘要.md)，
  CSV 字段解释见 [baseline/03-JMH结果CSV列字段说明.md](baseline/03-JMH结果CSV列字段说明.md)。
  注：测量定义为“内存字节数组场景下的序列化性能”，结果含流创建/关闭与
  `toByteArray()` 拷贝等生命周期成本，不是剥离外壳的纯算法成本。
- 并发梯度（8 线程，12 个代表方法）：已跑完，结果见
  [baseline/jmh-concurrency-t8.csv](baseline/jmh-concurrency-t8.csv) 与
  [baseline/02-并发8线程JMH基线摘要.md](baseline/02-并发8线程JMH基线摘要.md)。
  仅作优化前后同命令回归参考；单/多线程跨时段数值不可比，不计算扩展倍数。
- 未做（结论见讨论）：延迟采样（p50/p99）不作为判据；文件/网络端到端
  只做定位说明，不参与 A/B。

### 2.2 使用 CodeBuddy 优化 —— 已完成（两轮实现 + 验证，2026-09-08~09）

- 总规划：[reports/1-优化规划方案.md](reports/1-优化规划方案.md)；
  实施顺序：[2-优化实施方案.md](reports/2-优化实施方案.md) →
  [3-第一轮实施记录.md](reports/3-第一轮实施记录.md) →
  [4-优化实验全记录.md](reports/4-优化实验全记录.md)，最终结论见
  [5-最终交付报告.md](reports/5-最终交付报告.md)。
- 过程说明：曾按要求将早期探索性改动全部回退并重建纯净基线（记录于 1-优化规划方案），
  此后按"先方案、后实施、每步验证"推进，最终落地 2 个优化提交
  （见 2.2.4 / 2.2.5），当前 JDK 工作树含这两处改动。

### 2.2.1 阶段 0（判别力）—— 已完成（2026-09-08）

- 新增 K 扫描基准 `jmh/src/main/java/lab/jvm/serialization/KSweepBench.java`
  （K=1/16/128，5 类载荷 × 读写），结果与结论见
  [reports/stage0-ksweep/阶段0-K扫描判别力报告.md](reports/stage0-ksweep/阶段0-K扫描判别力报告.md)。
- 核心结论：小对象（Customer/Point）读写 ~94–98% 是 per-stream 固定成本，
  **第一刀应落在 modified UTF-8 编解码（阶段 2）**；FieldValues 池化缩小到
  Order 类嵌套读做受控归因；Analytics 本轮不动。

### 2.2.2 阶段 2 字符串对照基线 —— 已完成（2026-09-08）

- 对照载荷：`Payloads` 阶段 2 组（等字节 Ascii96/Cjk32/Emoji16 均 6 394 B、
  等字符 Ascii32、Nul32、跨块 Ascii1020/1030/4096、Cjk350）；
  基准 `StringCharsetBench.java`（18 个方法），语义由 `PayloadSizeProbe` 校验。
- 结果与结论：[reports/stage2-strings/阶段2-字符串对照基线报告.md](reports/stage2-strings/阶段2-字符串对照基线报告.md)。
- 核心结论：等字节下写侧中文/emoji 比 ASCII 慢 **1.88×**，而 B/op 三组完全相同
  （18 584 B）⇒ 纯 CPU 编码问题；机制是 `writeUTFInternal` 对非 ASCII 要走
  `utfLen` + `writeMoreUTF` 两次逐字符循环（ASCII 有 bulk 快路径可跳过）。
  **第一刀 = `BlockDataOutputStream.writeUTFInternal` 的非 ASCII 路径**。
- 负面结论：跨 1024 块边界只慢 2–4% ⇒ 阶段 3 块缓冲优先级下调。

### 2.2.3 优化实施方案 —— 已批准并实施（2026-09-08）

- 文档：[reports/2-优化实施方案.md](reports/2-优化实施方案.md)。
- P0 = `writeUTFInternal` 非 ASCII 路径（P0-a `utfLen` 向量化、P0-b `writeMoreUTF`
  内联/批量化）；P1 = 读侧 `readUTFSpan`；明确不做：FieldValues 池化（小对象）、
  块缓冲（证据不足）、Analytics（per-byte 主导）。
- 当前状态：**第一轮改动已落代码**（见 2.2.4），性能收益待构建后验证。

### 2.2.4 第一轮实施（写侧 UTF 微优化）—— 已验证：writeCjk32 +3.0%（commit `fecbb593236`）

- 记录：[reports/3-第一轮实施记录.md](reports/3-第一轮实施记录.md)、
  实验档案 [reports/4-优化实验全记录.md](reports/4-优化实验全记录.md) §2。
- 改动 1：`ObjectOutputStream.writeMoreUTF` 内联 `putChar`，消除每字符重复宽度判断；
  改动 2：`ModifiedUtf.utfLen` 分支顺序调整（多字节字符前置）。
- 语义等价穷举证明（`verify/UtfEquivalence.java`：65 536 char 逐字节一致、20 万随机串一致、
  NUL 仍 `C0 80`）；jtreg Serializable+ObjectInputStream 154 全过。
- **性能（干净机器紧邻 A/B）**：writeCjk32 **+3.0%（显著，误差不重叠）**、
  writeEmoji16 +2.4%（方向一致）；writeAscii96 −0.5%（无回退）；B/op 0.00% 不变。
- 备注：期间发生 Cygwin git 误操作损坏 `.git`（pack 丢失），已从用户 fork
  `lxk12356/TencentKona-25`（`add-whitebox-controlled-crash`）完整恢复历史；
  教训：Cygwin 只跑 make、绝不跑 git（git 一律用 Git Bash 自带版本）。

### 2.2.5 第二轮实施（读侧 StringBuilder 复用）—— 已验证：字符串读 B/op −22~54%（commit `08bc7650aa5`）

- 记录：实验档案 [reports/4-优化实验全记录.md](reports/4-优化实验全记录.md) §4。
- JFR 分配定位发现：读 String 每串 `new StringBuilder`（占读侧分配 27~39%，ASCII/CJK 均有）。
- 改动：`ObjectInputStream$BlockDataInputStream.readUTFBody` 用字段级 StringBuilder +
  `setLength(0)` 复用，按需扩容；解码逻辑零改动。
- 验证：`PayloadSizeProbe` + `verify/RoundTripCheck`（3000+ 组跨块/超长/NUL/代理对）全过；
  jtreg **160/160**（1 次初始失败系 JTwork 目录污染，换干净 `-w` 即过）。
- **性能（B/op 判据，±0.1B）**：readCjk32 −53.3%、readEmoji16 −54.5%、readAscii96 −33.8%；
  紧邻 A/B 吞吐 readCjk32/readEmoji16 **+6.4%/+6.5%（显著）**，写侧无回退。

### 2.3 优化性能与差异分析 —— 已完成（2026-09-09）

- 用优化后镜像（HEAD = 上述两 commit）重跑 v2 全量 24 基准，与
  [baseline/jmh-baseline.csv](baseline/jmh-baseline.csv) 对比，原始数据见
  [reports/fullbench-opt/jmh-fullbench-opt.csv](reports/fullbench-opt/jmh-fullbench-opt.csv)，
  分析见实验档案 [4-优化实验全记录.md](reports/4-优化实验全记录.md) §5。
- **B/op 主判据（跨时段可比）**：读侧 12 项全降（UnicodeList −34.8% / StringList −22.1% /
  Analytics −19.2% / Order −7.9% / … / Point −2.5%），**读侧合计 −17.1%、总量 −9.9%**；
  写侧 0.00%（两 commit 均不碰写侧分配，符合预期）。
- **差异分析**：降幅与载荷字符串含量严格相关；无字符串载荷（Point 等）也降 2.5~4.6%
  —— 因类描述符（类名/字段名）也走被优化的 readUTFBody。
- **并发 t8 复测（12 代表方法 × 8 线程，与基线同参数）**：读侧 B/op 变化与单线程
  逐项一致（UnicodeList −30.7% / Analytics −19.2% / Order −8.1% / …），合计 **−10.5%**，
  写侧 0.00% —— 字段级 sbuf 为每流实例，无并发回退（数据见
  [reports/fullbench-opt/jmh-concurrency-t8-opt.csv](reports/fullbench-opt/jmh-concurrency-t8-opt.csv)）。
- 吞吐列跨时段不作证据（写侧分配零变化却 +82~163%，纯机器状态差）；吞吐仅采信同会话
  紧邻 A/B（round3 readCjk32 +6.4%）。
- **进一步改进**：读侧 per-string 分配已近下限（CJK 与 ASCII 仅差 ~9%，接近 String
  本质表示差异）；写侧 1.88× 为结构性多字节编码成本，微观已到上限。候选
  FieldReflector 直写路径可做受控 A/B，但证据弱于已完成项，需用户确认是否继续。

- **最终交付报告**：[reports/5-最终交付报告.md](reports/5-最终交付报告.md)
  （优化思路、发现过程、改动明细、测试矩阵、判据纪律、复现步骤、评审关注点对照）。

### 2.4 下一步 —— 待用户决策
