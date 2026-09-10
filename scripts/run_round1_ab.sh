#!/bin/bash
# 路径占位符：<KONA_ROOT> = TencentKona-25 源码/构建根；<LAB_ROOT> = 本仓库根（task2-serialization）。
# 运行前替换为本机实际路径（Cygwin 下形如 /cygdrive/<盘符>/<目录>/TencentKona-25）。
set -e
cd "<KONA_ROOT>"
B="<LAB_ROOT>/backup-round1"
O="<LAB_ROOT>/patchsrc_orig"
F1=src/java.base/share/classes/java/io/ObjectOutputStream.java
F2=src/java.base/share/classes/jdk/internal/util/ModifiedUtf.java

JDK="<KONA_ROOT>/build/release/images/jdk/bin/java.exe"
JAR="<LAB_ROOT>/jmh/target/serialization-bench.jar"
OUT="<LAB_ROOT>/reports/stage2-strings"
BENCH='StringCharsetBench\.(write|read)(Ascii96|Cjk32|Emoji16|Ascii32)'

echo "[1/6] switch to ORIG"
cp "$O/java.base/java/io/ObjectOutputStream.java" "$F1"
cp "$O/java.base/jdk/internal/util/ModifiedUtf.java" "$F2"
cmp -s "$F1" "$O/java.base/java/io/ObjectOutputStream.java" && echo "  ORIG ObjectOutputStream ok"
cmp -s "$F2" "$O/java.base/jdk/internal/util/ModifiedUtf.java" && echo "  ORIG ModifiedUtf ok"
make CONF=release images > /tmp/ab_build_a.log 2>&1 || { echo BUILD-A-FAILED; tail -20 /tmp/ab_build_a.log; exit 1; }
echo "[2/6] A build done"
"$JDK" -jar "$JAR" "$BENCH" -f 2 -wi 3 -i 6 -w 1s -r 2s -foe true -prof gc -rf csv -rff "$OUT\\round1-A-orig.csv" > /tmp/ab_test_a.log 2>&1 || { echo TEST-A-FAILED; tail -20 /tmp/ab_test_a.log; exit 1; }
echo "[3/6] A test done"

echo "[4/6] switch to OPT"
cp "$B/ObjectOutputStream.java" "$F1"
cp "$B/ModifiedUtf.java" "$F2"
cmp -s "$F1" "$B/ObjectOutputStream.java" && echo "  OPT ObjectOutputStream ok"
cmp -s "$F2" "$B/ModifiedUtf.java" && echo "  OPT ModifiedUtf ok"
make CONF=release images > /tmp/ab_build_b.log 2>&1 || { echo BUILD-B-FAILED; tail -20 /tmp/ab_build_b.log; exit 1; }
echo "[5/6] B build done"
"$JDK" -jar "$JAR" "$BENCH" -f 2 -wi 3 -i 6 -w 1s -r 2s -foe true -prof gc -rf csv -rff "$OUT\\round1-B-opt.csv" > /tmp/ab_test_b.log 2>&1 || { echo TEST-B-FAILED; tail -20 /tmp/ab_test_b.log; exit 1; }
echo "[6/6] B test done ALL DONE"
