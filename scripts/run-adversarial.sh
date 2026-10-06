#!/bin/bash
# 对抗性中断测试编排：S1a 上传中强杀 / S1b 合并中强杀×2 / S2 磁盘满
set -u
cd "$(dirname "$0")/.."
KEY=~/.ssh/resumable-upload-deploy
SSH="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new root@60.205.230.218"
OUT=jmeter/results/adversarial
mkdir -p "$OUT"
IDX=$OUT/adversarial-index.csv
HASH=a8fad976dd20d28c3118ec6f4874f624
row() { echo "$(date +%s%3N),$1,$2" >> "$IDX"; }

wait_healthy() {
  for i in $(seq 1 60); do
    code=$($SSH 'curl -s -o /dev/null -w %{http_code} http://localhost:8080/api/files' 2>/dev/null | tail -1)
    if [ "$code" = "200" ]; then echo "[healthy after $((i*3))s]"; return 0; fi
    sleep 3
  done
  echo "[NOT HEALTHY after 180s]"; return 1
}
kill_and_wait() {
  row "KILL" "$1"
  $SSH 'docker kill resumable-upload-app-1 >/dev/null && echo killed'
  # docker kill 属手动停止，unless-stopped 策略不生效，需显式拉起（SIGKILL 语义不变）
  $SSH 'docker start resumable-upload-app-1 >/dev/null && echo started'
  wait_healthy
  $SSH 'docker inspect resumable-upload-app-1 --format "started={{.State.StartedAt}} oom={{.State.OOMKilled}}"'
}
inspect_state() {
  echo "--- 磁盘状态 ---"
  $SSH "docker exec resumable-upload-app-1 sh -c '
    echo chunks_parts=\$(ls /data/upload/chunks/$HASH/ 2>/dev/null | wc -l)
    ls -la /data/upload/files/*/ 2>/dev/null | grep $HASH | awk \"{print \\\"target_file=\\\" \\\$5, \\\$9}\"
    echo index_entries=\$(grep -c $HASH /data/upload/index.json 2>/dev/null)
  '"
}

echo "==== S1a: 上传中强杀 ===="
row "START" "S1a-kill-during-upload"
node scripts/adversarial-test.mjs cancel
node scripts/adversarial-test.mjs upload 0 30 6
kill_and_wait "S1a-upload-30of100"
echo "--- 强杀后断点状态 ---"
node scripts/adversarial-test.mjs check
node scripts/adversarial-test.mjs upload 30 100 6
node scripts/adversarial-test.mjs merge
node scripts/adversarial-test.mjs verify 90
node scripts/adversarial-test.mjs delete
row "END" "S1a"

echo "==== S1b-1: 合并中强杀（先正常合并测时长）===="
row "START" "S1b0-timed-merge"
node scripts/adversarial-test.mjs upload 0 100 6
node scripts/adversarial-test.mjs merge
node scripts/adversarial-test.mjs verify 90
node scripts/adversarial-test.mjs delete
row "END" "S1b0"

echo "==== S1b-2: 合并中强杀（中途）===="
row "START" "S1b2-kill-mid-merge"
node scripts/adversarial-test.mjs upload 0 100 6
node scripts/adversarial-test.mjs merge &
MERGE_PID=$!
sleep 1.2
kill_and_wait "S1b2-mid-merge"
wait $MERGE_PID 2>/dev/null
inspect_state
echo "--- 恢复：再次 merge（分片应完整保留）---"
node scripts/adversarial-test.mjs merge
node scripts/adversarial-test.mjs verify 90
node scripts/adversarial-test.mjs delete
row "END" "S1b2"

echo "==== S1b-3: 合并后、异步校验中强杀 ===="
row "START" "S1b3-kill-during-verify"
node scripts/adversarial-test.mjs upload 0 100 6
node scripts/adversarial-test.mjs merge
sleep 0.5
kill_and_wait "S1b3-during-verify"
inspect_state
echo "--- 重启后 check（预期 finished=true, verified=false 卡在校验中）---"
node scripts/adversarial-test.mjs check
node scripts/adversarial-test.mjs verify 30
node scripts/adversarial-test.mjs delete
row "END" "S1b3"

echo "==== S2: 上传中磁盘满 ===="
row "START" "S2-disk-full"
node scripts/adversarial-test.mjs cancel
node scripts/adversarial-test.mjs upload 0 100 6
FREE=$($SSH "df --output=avail -B1 / | tail -1" | tr -d '\r ')
FILLER=$((FREE - 400 * 1024 * 1024))
echo "可用 ${FREE}B，填充 ${FILLER}B（留约 400MB）"
$SSH "fallocate -l $FILLER /data-filler && df -h / | tail -1"
echo "--- 磁盘满时 merge（预期 500 合并失败）---"
node scripts/adversarial-test.mjs merge
inspect_state
$SSH 'df -h / | tail -1; rm -f /data-filler && echo filler-removed; df -h / | tail -1'
echo "--- 释放后再次 merge（观察 exists 恢复路径）---"
node scripts/adversarial-test.mjs merge
node scripts/adversarial-test.mjs check
node scripts/adversarial-test.mjs verify 90
node scripts/adversarial-test.mjs delete
node scripts/adversarial-test.mjs cancel
inspect_state
row "END" "S2"
echo "==== 编排结束 ===="
