#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
紧邻 A/B 对比：原版(A) vs 优化版(B)。

用法：
  python ab_compare.py round1-A-orig.csv round1-B-opt.csv

判定规则（与 optimization-plan 一致）：
- 先看 B/op（gc.alloc.rate.norm，误差通常极小）——分配不应上升；
- 再看吞吐：只有当两侧误差区间不重叠时才判定"提升/回退"；
- 误差重叠 = 不显著，不得宣称收益；
- ASCII 载荷是"不得回退"的底线（纯 ASCII 不走改动的非 ASCII 路径）。
"""
import csv
import sys


def load(path):
    d = {}
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            name = r["Benchmark"].split(":")[0].split(".")[-1]
            unit = r["Unit"]
            e = d.setdefault(name, {})
            if unit == "ops/s":
                e["s"] = float(r["Score"])
                e["e"] = float(r["Score Error (99.9%)"])
            elif unit == "B/op":
                e["b"] = float(r["Score"])
    return d


def fmt(x):
    return f"{x:,.0f}"


def main():
    a_path = sys.argv[1] if len(sys.argv) > 1 else "round1-A-orig.csv"
    b_path = sys.argv[2] if len(sys.argv) > 2 else "round1-B-opt.csv"
    A, B = load(a_path), load(b_path)

    print(f"# 紧邻 A/B：原版 {a_path}  vs  优化版 {b_path}\n")
    print("| 基准 | 原版 ops/s | 优化版 ops/s | 变化 | 原版± | 优化版± | 判定 |")
    print("|---|---:|---:|---:|---:|---:|---|")
    up = down = flat = 0
    for k in sorted(A):
        if k not in B or "s" not in A[k] or "s" not in B[k]:
            continue
        a, ea = A[k]["s"], A[k]["e"]
        b, eb = B[k]["s"], B[k]["e"]
        ch = (b - a) / a * 100
        overlap = not ((b + eb) < (a - ea) or (b - eb) > (a + ea))
        if overlap:
            v, flat = "不显著（误差重叠）", flat + 1
        elif ch > 0:
            v, up = "**提升**", up + 1
        else:
            v, down = "**回退**", down + 1
        print(f"| {k} | {fmt(a)} | {fmt(b)} | {ch:+.1f}% | "
              f"±{ea/a*100:.1f}% | ±{eb/b*100:.1f}% | {v} |")
    print(f"\n汇总：提升 {up} / 回退 {down} / 不显著 {flat}\n")

    print("| 基准 | 原版 B/op | 优化版 B/op | 变化 |")
    print("|---|---:|---:|---:|")
    for k in sorted(A):
        if k not in B or "b" not in A[k] or "b" not in B[k]:
            continue
        a, b = A[k]["b"], B[k]["b"]
        flag = " ⚠ 分配上升" if b > a * 1.01 else ""
        print(f"| {k} | {fmt(a)} | {fmt(b)} | {(b-a)/a*100:+.2f}%{flag} |")

    # ASCII 底线检查
    print("\n## ASCII 底线（纯 ASCII 不走改动路径，不得回退）")
    for k in sorted(A):
        if "Ascii" not in k or k not in B or "s" not in A[k]:
            continue
        a, b = A[k]["s"], B[k]["s"]
        ch = (b - a) / a * 100
        print(f"- {k}: {ch:+.1f}%" + ("  ← 回退，需关注" if ch < -5 else "  正常"))


if __name__ == "__main__":
    main()
