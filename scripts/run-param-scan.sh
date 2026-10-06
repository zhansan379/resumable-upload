#!/bin/bash
# 参数扫描驱动：2/10/20MB 分片 × 并发 1/3/6，对远程服务器跑 07 负载组。
# 依赖：外层已启动 MemorySampler（JVM 内存 CSV）与服务器 /tmp/mem-stats.sh（容器内存）。
set -u
cd "$(dirname "$0")/.."

HOST=60.205.230.218
PORT=8080
OUT=jmeter/results/scan-params
IDX=$OUT/scan-index.csv
JMETER="D:/DeveloperTools/apache-jmeter-5.6.3/bin/jmeter.bat"
export JVM_ARGS="-Dfile.encoding=UTF-8"

row() { echo "$(date +%s%3N),$1,$2" >> "$IDX"; }

echo "==== PARAM SCAN START $(date -Iseconds) ===="
for size in 2 10 20; do
  for k in 1 3 6; do
    chunks=$((104857600 / (size * 1048576)))
    node tmp/split-chunks.mjs "$size" >/dev/null
    row "START" "c${size}m-k${k}"
    "$JMETER" -n -t jmeter/resumable-upload-scan.jmx \
      -l "$OUT/jtl/c${size}m-k${k}.jtl" \
      -Jhost=$HOST -Jport=$PORT -JloadThreads=$k \
      -JchunkSize=$((size * 1048576)) -JtotalChunks=$chunks -JloadRamp=2 \
      > "$OUT/jtl/c${size}m-k${k}.log" 2>&1
    rc=$?
    row "END" "c${size}m-k${k} rc=$rc"
    echo "[$(date -Iseconds)] c${size}m-k${k} done rc=$rc"
    sleep 30
  done
done
row "MARK" "recovery-90s-begin"
sleep 90
row "MARK" "recovery-90s-end"
echo "==== PARAM SCAN DONE $(date -Iseconds) ===="
