# jtreg 基线（优化前）

在未做任何序列化改动的 Tencent Kona JDK 25 release 镜像上运行。

- 被测 JDK：`<KONA_ROOT>\build\release\images\jdk`
  （25.0.4-internal，release 配置）
- jtreg：`<JTREG_HOME>`（直接调用 `jtreg.jar`）
- 日期：2026-09-07
- 命令：`powershell task2-serialization/jtreg/run-jtreg-baseline.ps1`
  （`-conc:8 -timeoutFactor:2 -ignore:quiet`，`-v:fail,error`）

## 测试范围（针对序列化实现）

- `test/jdk/java/io/Serializable`（150 个 `@test` 用例）
- `test/jdk/java/io/ObjectInputStream`（4 个用例）
- `test/jdk/java/io/ObjectStreamClass`（6 个用例）

## 结果

**通过 160 / 160，失败 0。**

160 个 jtreg 顶层条目内部包含 6 个 TestNG case 和 481 个 JUnit case。

HTML 报告：[baseline-jtreg/html/report.html](jtreg/results/baseline-jtreg/html/report.html)

该基线是 2.2/2.3 每一步源码改动的功能门槛：每次改动后必须用同一命令
保持 160 个用例全部通过。
