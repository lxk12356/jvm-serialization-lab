#!/usr/bin/env python3
"""对比 v2 全量基准：基线(baseline/jmh-baseline.csv) vs 优化后(fullbench csv)。
主判据: gc.alloc.rate.norm (B/op, ±0.1B, 跨时段可比)
参考:   吞吐 ops/s (跨时段受机器状态影响, 仅方向参考)
用法: python compare_fullbench.py <opt.csv>
"""
import csv, sys

def load(path):
    d = {}
    for r in csv.DictReader(open(path, newline='', encoding='utf-8-sig')):
        name = r['Benchmark'].split(':')[0]
        if name not in d:
            d[name] = {}
        u = r['Unit']
        if u == 'ops/s':
            d[name]['s'] = float(r['Score'])
            d[name]['se'] = float(r['Score Error (99.9%)'])
        elif u == 'B/op':
            d[name]['b'] = float(r['Score'])
    return d

base = load('D:/kona/task2-serialization/baseline/jmh-baseline.csv')
opt = load(sys.argv[1])

rows = []
for k in base:
    if k not in opt:
        continue
    b, o = base[k], opt[k]
    rows.append((k, b, o))

def side(k): return 'W' if k.split('.')[-1].startswith('write') else 'R'

print("## v2 全量 24 基准: 基线 vs 优化后\n")
print("| 方法 | 侧 | 基线 B/op | 优化 B/op | B/op 变化 | 基线 ops/s | 优化 ops/s | 吞吐变化(参考) |")
print("|---|---|---:|---:|---:|---:|---:|---:|")
tot_b = tot_o = 0
for k, b, o in sorted(rows):
    db = ((o['b'] - b['b']) / b['b'] * 100) if b.get('b') and o.get('b') else float('nan')
    ds = ((o['s'] - b['s']) / b['s'] * 100) if b.get('s') and o.get('s') else float('nan')
    if b.get('b') and o.get('b'):
        tot_b += b['b']; tot_o += o['b']
    short = k.split('.')[-1]
    print(f"| {short} | {side(k)} | {b.get('b',0):,.0f} | {o.get('b',0):,.0f} | {db:+.1f}% | {b.get('s',0):,.0f} | {o.get('s',0):,.0f} | {ds:+.1f}% |")
print(f"\n合计 B/op: 基线 {tot_b:,.0f} -> 优化 {tot_o:,.0f} ({ (tot_o-tot_b)/tot_b*100:+.1f}%)")

# 分侧小计: 只统计含 B/op 的方法
for label, filt in (('读侧', lambda s: s == 'R'), ('写侧', lambda s: s == 'W')):
    tb = sum(b['b'] for k, b, o in rows if filt(side(k)) and 'b' in b)
    to = sum(o['b'] for k, b, o in rows if filt(side(k)) and 'b' in o)
    if tb:
        print(f"{label} B/op 合计: {tb:,.0f} -> {to:,.0f} ({(to-tb)/tb*100:+.1f}%)")
