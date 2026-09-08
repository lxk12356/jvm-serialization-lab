#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 0：K 扫描 CSV 解析与归一化分析
输入：jmh-ksweep.csv（JMH thrpt + -prof gc 导出）
输出：归一化表（objects/s、B/object、斜率）、决策所需关键指标

判定逻辑（optimization-plan.md 3.3）：
- 比较 K=1 与 K=128：
  * 若 K=128 的 objects/s 相对 K=1 显著更高（固定成本摊薄收益大），
    说明小 K 时 per-stream 固定成本主导；
  * 若 objects/s 随 K 基本持平/微增，说明 per-object 成本主导。
- B/object：K 增大后仍居高不下 ⇒ per-object 分配主导（FieldValues 等值得归因）。
"""
import csv
import sys
from collections import defaultdict

SRC = sys.argv[1] if len(sys.argv) > 1 else "jmh-ksweep.csv"

rows = []
with open(SRC, newline="", encoding="utf-8-sig") as f:
    for r in csv.DictReader(f):
        rows.append(r)

# K 位于独立列 "Param: K"；gc 附加行的方法名带 ":gc.xxx" 后缀
def parse(r):
    bench = r["Benchmark"]
    unit = r.get("Unit", "")
    if unit not in ("ops/s", "B/op"):
        return None, None
    base = bench.split(":")[0]  # 去掉 :gc.alloc.rate.norm 等后缀
    name = base.split(".")[-1]
    k = int(r.get("Param: K", "1"))
    return name, k

data = defaultdict(dict)  # (method, K) -> {score, err, norm}
for r in rows:
    name, k = parse(r)
    if name is None:
        continue
    unit = r.get("Unit", "")
    score = float(r["Score"])
    if unit == "ops/s":
        data[(name, k)]["score"] = score
        data[(name, k)]["err"] = float(r["Score Error (99.9%)"])
    elif unit == "B/op":
        data[(name, k)]["norm"] = score
    # 其它行(gc.alloc.rate/gc.count/gc.time)暂不取

KS = [1, 16, 128]
methods = sorted({m for m, _ in data if data[(m, _)].get("score") is not None and m in data})
# 按写/读分组排序：保留出现顺序
order = []
for m, _ in data:
    if m not in order:
        order.append(m)
methods = [m for m in order if any(data[(m, k)].get("score") is not None for k in KS)]
methods = sorted(set(methods))

def pct_err(rel):
    # 把百分比字符串转 float（去掉 %）
    return rel

print(f"# K 扫描归一化结果（源：{SRC}）\n")
print("| 方向 | 方法 | K | ops/s(批) | ±% | objects/s | B/op(批) | B/object |")
print("|---|---|---:|---:|---:|---:|---:|---:|")
for m in methods:
    for k in KS:
        d = data.get((m, k))
        if not d or "score" not in d:
            continue
        score = d["score"]
        err = d.get("err", 0.0)
        norm = d.get("norm")
        objs_per_s = score * k
        errpct = (err / score * 100) if score else float("nan")
        bop = norm if norm is not None else float("nan")
        bobj = (norm / k) if norm is not None else float("nan")
        direction = "写" if m.startswith("write") else "读"
        print(f"| {direction} | {m} | {k} | {score:.0f} | {errpct:.1f}% | "
              f"{objs_per_s:.0f} | {bop:.0f} | {bobj:.1f} |")

# 关键决策指标：objects/s 与 B/object 在 K=1 vs K=128 的比值
print("\n## 决策指标：K=128 vs K=1\n")
print("| 方法 | objects/s 提升倍数(K128/K1) | B/object 下降倍数(K1时/K128时) | 判读 |")
print("|---|---:|---:|---|")
for m in methods:
    d1 = data.get((m, 1)); d128 = data.get((m, 128))
    if not d1 or not d128 or "score" not in d1 or "score" not in d128:
        continue
    obj1 = d1["score"] * 1
    obj128 = d128["score"] * 128
    ratio = obj128 / obj1 if obj1 else float("nan")
    b1 = d1.get("norm"); b128 = d128.get("norm")
    # B/object 比值 = (K=1 时单对象分配) / (K=128 时单对象分配)，>1 表示放大后单对象分配更低
    b1_obj = b1 / 1 if b1 else float("nan")
    b128_obj = (b128 / 128) if b128 else float("nan")
    bratio = (b1_obj / b128_obj) if (b1_obj == b1_obj and b128_obj == b128_obj and b128_obj > 0) else float("nan")
    verdict = ""
    if ratio > 3:
        verdict = "objects/s 大幅提升 ⇒ per-stream 固定成本在小 K 时主导"
    elif ratio > 1.3:
        verdict = "objects/s 明显提升 ⇒ 固定成本占比显著"
    else:
        verdict = "objects/s 基本持平 ⇒ per-object 成本主导"
    print(f"| {m} | {ratio:.2f}× | {bratio:.2f}× | {verdict} |")
