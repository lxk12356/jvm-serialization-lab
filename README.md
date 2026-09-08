<p align="center">
  <img src="https://img.shields.io/badge/Status-Completed-brightgreen" alt="Status">
  <img src="https://img.shields.io/badge/jtreg-160%2F160-brightgreen" alt="jtreg">
  <img src="https://img.shields.io/badge/Read%20B%2Fop--17.1%25-orange" alt="Read B/op -17.1%">
  <img src="https://img.shields.io/badge/Code-%2B27%2F%E2%88%924%20lines-blue" alt="+27/-4 lines">
  <img src="https://img.shields.io/badge/Commits-2-blue" alt="2 commits">
</p>

<h1 align="center">📦 任务二：序列化加速</h1>
<p align="center"><b>腾讯 Kona JDK 25 · <code>java.io</code> 对象序列化性能优化</b></p>
<p align="center">不改变序列化协议与对象语义 · 全流程测量驱动 · 回归红线通过</p>

---

## 🏆 核心成果

| 指标 | 结果 | 判据 |
|---|---:|---|
| **读侧分配（单线程全量 24 基准）** | **−17.1%** | B/op（±0.1 B，跨时段可比） |
| **读侧分配（并发 8 线程 / 12 方法）** | **−10.5%** | B/op，与单线程逐项一致 ⇒ 无并发回退 |
| 字符串类载荷读分配 | −22% ~ **−54%** | readCjk32 31 944 → 14 916 B/op |
| 写侧中文(CJK)吞吐 | **+3.0%** | 同会话紧邻 A/B，误差不重叠 |
| 回归红线 | **jtreg 160/160** · 语义穷举等价 · 字节级兼容 | 见「验证」 |

**改动规模**：2 个提交 · **+27 / −4 行** · 3 个 JDK 源文件（改动以 `code-patches/*.patch` 随仓库交付）

---

## 🧭 快速入口

| 想做什么 | 去哪里 |
|---|---|
| 👀 我是**审查者**，从哪看起 | **[0-审查者导读](reports/0-审查者导读.md)**（阅读路径 / 代码审法 / 证据地图，全跳转） |
| 📄 看**最终结论** | [5-最终交付报告](reports/5-最终交付报告.md)（思路 → 发现 → 改动 → 测试 → 复现） |
| 🧪 看**每一步实验** | [4-优化实验全记录](reports/4-优化实验全记录.md)（动机→做法→验证→数据→结论） |
| 📝 看**代码 diff** | [code-patches/0001-...patch](code-patches/0001-Round1-micro-optimize-non-ASCII-UTF-write-path-in-OO.patch) · [0002-...patch](code-patches/0002-Read-path-reuse-StringBuilder-in-ObjectInputStream.r.patch) |
| 📊 看**原始数据** | [baseline CSV](baseline/jmh-baseline.csv) · [优化后 CSV](reports/fullbench-opt/jmh-fullbench-opt.csv) |

---

## ✅ 任务书完成情况

| 任务书条目 | 状态 | 关键结果 / 位置 |
|---|---|:---|
| **2. 序列化加速** | 🟢 整体完成 | 两处优化落地并验证，见下 |
| **2.1 获取测试基准** | 🟢 完成 | 构建 Kona JDK · jtreg **160/160**（[摘要](baseline/04-jtreg功能回归基线摘要.md)）· JMH **24 基准 + 并发 t8**（[01](baseline/01-单线程JMH性能基线摘要.md) / [02](baseline/02-并发8线程JMH基线摘要.md)） |
| **2.2 使用 CodeBuddy 优化** | 🟢 完成 | 规划（[1](reports/1-优化规划方案.md) → [2](reports/2-优化实施方案.md)）→ 实现 ×2 并验证（见下「改动一览」） |
| **2.3 ① 执行 JMH 得优化性能** | 🟢 完成 | 读侧 −17.1%（单线程）· −10.5%（并发 t8）· [CSV](reports/fullbench-opt/jmh-fullbench-opt.csv) |
| **2.3 ② 分析性能差异** | 🟢 完成 | 降幅与载荷字符串含量严格相关；写侧 0.00% 符合代码性质（[§5](reports/4-优化实验全记录.md)） |
| **2.3 ③ 根据分析进一步改进** | 🟡 结论已出 | 读侧分配近下限、写侧为结构性成本 ⇒ 收口；候选 FieldReflector **待拍板** |

---

## 🔧 改动一览（两处，均满足"先方案 → 后实施 → 每步验证"）

### ① 写侧 UTF 微优化 — `fecbb593236`
- `writeMoreUTF` 内联 `putChar`：消除每字符**重复两次**的宽度判断；`utfLen` 分支序调整
- 收益：`writeCjk32` **+3.0%**（紧邻 A/B 显著）· ASCII 底线无回退 · jtreg 154/154
- 记录：[3-第一轮实施记录](reports/3-第一轮实施记录.md)

### ② 读侧 StringBuilder 复用 — `08bc7650aa5`
- `readUTFBody` 用字段级 StringBuilder + `setLength(0)` 复用，消除**每读一个字符串就 new 一次**的分配（JFR 定位占读侧分配 27~39%）
- 收益：字符串读 B/op **−22%~−54%** · jtreg 160/160 · 语义穷举等价
- 记录：[4-优化实验全记录 §4](reports/4-优化实验全记录.md)

<details>
<summary><b>🗺 文档地图（全仓库导航）</b></summary>

```
task2-serialization/
├── README.md                        ← 本文件（总入口）
├── baseline/                        ← 改动前基线（对比基准）
│   ├── 01-单线程JMH性能基线摘要.md · 02-并发8线程JMH基线摘要.md
│   ├── 03-JMH结果CSV列字段说明.md · 04-jtreg功能回归基线摘要.md
│   ├── 采用该测试方法的原因.md       ← 测试方法设计理由
│   └── *.csv                         ← 基线原始数据
├── reports/                          ← 报告（编号 = 阅读顺序）
│   ├── 0-审查者导读.md               ← 从这里开始
│   ├── 1-优化规划方案.md → 2-优化实施方案.md → 3-第一轮实施记录.md
│   ├── 4-优化实验全记录.md           ← 逐步实验档案（最细）
│   ├── 5-最终交付报告.md             ← 最终结论（总纲）
│   ├── stage0-ksweep/  stage2-strings/  fullbench-opt/   ← 阶段报告 + 原始数据
├── jmh/src/                          ← JMH 基准源码（含 KSweepBench/StringCharsetBench）
├── verify/                           ← 语义/等价/往返验证程序（Java）
├── code-patches/                     ← 两个改动的 patch（可 git am / 直接看 diff）
└── scripts/                          ← 构建与 A/B 脚本
```

> 注：`1-优化规划方案.md` / `2-优化实施方案.md` 为**历史规划文档**（2026-09-07/08），
> 正文"待/拟"为当时措辞，均已实施完成（文件头有状态横幅）。
</details>

<details>
<summary><b>⚙️ 固定环境与测试口径</b></summary>

| 项 | 值 |
|---|---|
| 被测 JDK | 腾讯 Kona JDK 25 release 镜像（`TencentKona-25/build/release/images/jdk`） |
| JDK 源码 | `TencentKona-25`（分支 `task2-serialization-accel`） |
| 构建环境 | Cygwin（仅跑 `make`；git 一律用 Git Bash） |
| 功能回归 | jtreg：`test/jdk/java/io/{Serializable,ObjectInputStream,ObjectStreamClass}` |
| 性能判据 | **B/op**（`gc.alloc.rate.norm`，±0.1 B）为主判据，跨时段可比；吞吐仅采信同会话紧邻 A/B |

性能测试一律使用 Kona JDK 25 release 镜像；测试方法设计理由见
[baseline/采用该测试方法的原因.md](baseline/采用该测试方法的原因.md)。
</details>

<details>
<summary><b>📜 详细过程时间线（2.1 ~ 2.3 完整记录）</b></summary>

**2.1 获取测试基准（2026-09-07）**
- JDK 构建：release 镜像增量重建并核验；jtreg 160/160；JMH 基线 v2：24 基准、
  3 forks、5w+8i×2s、`-prof gc`；并发梯度 12 方法 × 8 线程。
- 测量定义：内存字节数组场景下的端到端序列化（含流生命周期成本，非剥离外壳的纯算法成本）。

**2.2.1 阶段 0（K 扫描判别力，2026-09-08）**
- 新增 `KSweepBench`（K=1/16/128）；结论：小对象一次性读写 94~98% 为 per-stream 固定成本
  ⇒ 否决默认刀口 FieldValues 池化，第一刀锁定 modified UTF-8 编解码。
- 报告：[阶段0-K扫描判别力报告](reports/stage0-ksweep/阶段0-K扫描判别力报告.md)

**2.2.2 阶段 2 字符串对照基线（2026-09-08）**
- 等字节金标准对照（Ascii96/Cjk32/Emoji16 均 6 394 B）：写侧中文/emoji 慢 **1.88×** 且
  B/op 相同 ⇒ 纯 CPU 编码问题；跨 1024 块边界仅慢 2~4% ⇒ 块缓冲候选下调。
- 报告：[阶段2-字符串对照基线报告](reports/stage2-strings/阶段2-字符串对照基线报告.md)

**2.2.3 优化实施方案（2026-09-08）** → [2-优化实施方案](reports/2-优化实施方案.md)

**2.2.4 第一轮：写侧 UTF 微优化（2026-09-08~09）** → commit `fecbb593236`
- 语义穷举等价 + jtreg 154/154；干净机器紧邻 A/B：writeCjk32 **+3.0%**。
- 事故记录：Cygwin git 误操作损坏 `.git`，已从用户 fork 完整恢复（教训：Cygwin 只跑 make）。

**2.2.5 第二轮：读侧 StringBuilder 复用（2026-09-09）** → commit `08bc7650aa5`
- JFR 定位每串 new StringBuilder；复用后 readCjk32 B/op **−53.3%**、吞吐 +6.4%（显著）。

**2.3 优化性能与差异分析（2026-09-09）**
- 全量 24 基准：读侧 −17.1%、总量 −9.9%；并发 t8：−10.5% 无回退。
- 差异分析：降幅与字符串含量相关；类描述符（类名/字段名）同路径受益。
- 进一步改进结论：读侧近下限、写侧结构性 ⇒ 收口；候选 FieldReflector 待确认。
</details>

<details>
<summary><b>🔗 关联仓库</b></summary>

| 仓库 | 说明 |
|---|---|
| [jvm-crash-lab](https://github.com/lxk12356/jvm-crash-lab) | 任务 1：JVM 崩溃日志分析（独立仓库） |
| [Tencent/TencentKona-25](https://github.com/Tencent/TencentKona-25) | 被优化 JDK 上游（官方） |
</details>

---

## 📅 更新记录

| 日期 | 内容 |
|---|---|
| 2026-09-09 | 任务 2 全流程完成：两处优化落地验证、性能差异分析收口、交付文档齐全 |
| 2026-09-08 | 阶段 0 判别、阶段 2 对照基线、两轮实现与验证 |
| 2026-09-07 | 2.1 基线：jtreg 160/160 + JMH 24 基准 + 并发梯度 |
