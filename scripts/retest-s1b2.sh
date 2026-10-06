#!/bin/bash
# S1b-2 修复验证：合并中强杀 → 重试 merge（应删除残缺文件并真实重合并）→ MD5 校验应通过
set -u
cd "$(dirname "$0")/.."
KEY=~/.ssh/resumable-upload-deploy
SSH="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new root@60.205.230.218"
OUT=jmeter/results/adversarial
IDX=$OUT/adversarial-index.csv
HASH=a8fad976dd20d28c3118ec6f4874f624
row() { echo "$(date +%s%3N),$1,$2" >> "$IDX"; }

wait_healthy() {
  for i in $(seq 1 60); do
    code=$($SSH 'curl -s -o /dev/null -w %{http_code} http://localhost:8080/api/files' 2>/dev/null | tail -1)
    [ "$code" = "200" ] && { echo "[healthy after $((i*3))s]"; return 0; }
    sleep 3
  done
  echo "[NOT HEALTHY]"; return 1
}

echo "==== 回归：小文件全链路（修复后正常路径）===="
node scripts/adversarial-test.mjs cancel 2>/dev/null
node -e "
const crypto=require('node:crypto')
const BASE='http://60.205.230.218:8080/api'
const buf=Buffer.alloc(1048576);crypto.randomFillSync(buf)
const h=crypto.createHash('md5').update(buf).digest('hex')
const fd=new FormData()
fd.append('fileHash',h);fd.append('chunkIndex','0');fd.append('totalChunks','1')
fd.append('chunkSize','1048576');fd.append('totalSize','1048576');fd.append('fileName','sanity.bin');fd.append('chunk',new Blob([buf]))
;(async()=>{
  let r=await fetch(BASE+'/upload/chunk',{method:'POST',body:fd});console.log('sanity chunk',r.status)
  r=await fetch(BASE+'/upload/merge',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({fileHash:h,fileName:'sanity.bin',totalSize:1048576,totalChunks:1,chunkSize:1048576})});console.log('sanity merge',r.status)
  for(let i=0;i<20;i++){const c=await (await fetch(BASE+'/upload/check',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({fileHash:h,fileName:'sanity.bin',totalSize:1048576,totalChunks:1,chunkSize:1048576})})).json();if(c.verified===true){console.log('sanity verify TRUE');break}await new Promise(r=>setTimeout(r,1000))}
  r=await fetch(BASE+'/files/'+h,{method:'DELETE'});console.log('sanity delete',r.status)
})()"

echo "==== S1b-2 重测：合并中强杀 → 恢复 ===="
row "START" "S1b2-retest-after-fix"
node scripts/adversarial-test.mjs cancel
node scripts/adversarial-test.mjs upload 0 100 6
node scripts/adversarial-test.mjs merge &
MERGE_PID=$!
sleep 1.2
row "KILL" "S1b2-retest-mid-merge"
$SSH 'docker kill resumable-upload-app-1 >/dev/null && echo killed'
$SSH 'docker start resumable-upload-app-1 >/dev/null && echo started'
wait_healthy
wait $MERGE_PID 2>/dev/null
echo "--- 强杀后磁盘状态（预期: 100分片 + 残缺target + 无索引）---"
$SSH "docker exec resumable-upload-app-1 sh -c '
  echo chunks_parts=\$(ls /data/upload/chunks/$HASH/ 2>/dev/null | wc -l)
  ls -la /data/upload/files/*/ 2>/dev/null | grep $HASH | awk \"{print \\\"partial_target=\\\" \\\$5}\"
  echo index_entries=\$(grep -c $HASH /data/upload/index.json 2>/dev/null)'"
echo "--- 恢复：重试 merge（修复后应删除残缺文件并真实重合并，耗时 ~7s 而非 0.3s）---"
node scripts/adversarial-test.mjs merge
echo "--- 关键断言：MD5 校验应通过（修复前: 失败）---"
node scripts/adversarial-test.mjs verify 120
echo "--- 终态文件大小 ---"
$SSH "docker exec resumable-upload-app-1 sh -c 'ls -la /data/upload/files/*/ 2>/dev/null | grep $HASH | awk \"{print \\\"final_target=\\\" \\\$5}\"'"
$SSH 'docker logs resumable-upload-app-1 --since 10m 2>&1 | grep -E "目标文件已存在|MD5 校验" | tail -5'
node scripts/adversarial-test.mjs delete
row "END" "S1b2-retest-after-fix"
echo "==== 重测结束 ===="
