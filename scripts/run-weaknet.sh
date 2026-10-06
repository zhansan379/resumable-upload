#!/bin/bash
# 弱网注入编排（toxiproxy 版）：toxiproxy 占据公网 8080 → app 容器
#   延迟 = latency toxic (双向 100ms)；丢包/断连 = timeout toxic 按概率杀连接(1%/5%) + ss -K 定时断连；
#   带宽限制 = bandwidth toxic (上行 250KB/s ≈ 2Mbps)
set -u
cd "$(dirname "$0")/.."
KEY=~/.ssh/resumable-upload-deploy
SSH="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new root@60.205.230.218"
OUT=jmeter/results/weaknet
mkdir -p "$OUT"
CSV=$OUT/weaknet-results.csv
HASH=9c42e41deba91004c2d523db2eeb1208
SUITE=${1:-full}
[ "$SUITE" = "full" ] && echo "label,mode,wallSec,bytesSentMB,attempts,chunkFails,restarts,merged,verified,ok" > "$CSV"

PID=$($SSH 'docker inspect -f "{{.State.Pid}}" app-manual')
UP=$($SSH 'docker inspect app-manual --format "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}"')
echo "PID=$PID UP=$UP"
[ -n "$UP" ] || { echo "app-manual 未运行"; exit 1; }

tox_reset() { # 清空全部 toxic（删除并重建代理）
  $SSH "curl -s -X DELETE http://localhost:8474/proxies/app >/dev/null
        curl -s -X POST http://localhost:8474/proxies -H 'Content-Type: application/json' -d '{\"name\":\"app\",\"listen\":\"0.0.0.0:8080\",\"upstream\":\"$UP:8080\",\"enabled\":true}' >/dev/null && echo [toxics-reset]"
}
tox_add() { # $1 = toxic JSON
  $SSH bash -s <<EOF
curl -s -X POST http://localhost:8474/proxies/app/toxics -H 'Content-Type: application/json' -d '$1' >/dev/null && echo "  [toxic] $1"
EOF
}
tox_latency() {
  tox_add '{"name":"lat_down","type":"latency","stream":"downstream","toxicity":1.0,"attributes":{"latency":100,"jitter":0}}'
  tox_add '{"name":"lat_up","type":"latency","stream":"upstream","toxicity":1.0,"attributes":{"latency":100,"jitter":0}}'
}
tox_kill() { # $1 = toxicity (连接级中断概率)
  tox_add "{\"name\":\"kill_down\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":$1,\"attributes\":{\"timeout\":200}}"
  tox_add "{\"name\":\"kill_up\",\"type\":\"timeout\",\"stream\":\"upstream\",\"toxicity\":$1,\"attributes\":{\"timeout\":200}}"
}
tox_bw() {
  tox_add '{"name":"bw_up","type":"bandwidth","stream":"upstream","toxicity":1.0,"attributes":{"rate":250}}'
}
tox_kill1() { tox_kill 0.01; }
tox_kill5() { tox_kill 0.05; }
tox_l5()    { tox_kill 0.05; tox_latency; }
tox_lifetime() { # 断连场景：每条连接存活 1.5s 即被关闭（确定性，模拟 NAT 超时/链路中断）
  tox_add '{"name":"life_down","type":"timeout","stream":"downstream","toxicity":1.0,"attributes":{"timeout":1500}}'
  tox_add '{"name":"life_up","type":"timeout","stream":"upstream","toxicity":1.0,"attributes":{"timeout":1500}}'
  tox_latency
}
kill_start() { # 断连窗口：每 3s 插入 1s 的 8080 RST 拒绝（在途请求立即被重置；ss -K 在该内核静默失效，iptables 可靠）
  $SSH "printf '#!/bin/bash\nfor i in \$(seq 1 200); do iptables -I INPUT 1 -p tcp --dport 8080 -j REJECT --reject-with tcp-reset 2>/dev/null; sleep 1; iptables -D INPUT -p tcp --dport 8080 -j REJECT --reject-with tcp-reset 2>/dev/null; sleep 2; done\n' > /tmp/tp/killloop.sh
        chmod +x /tmp/tp/killloop.sh
        setsid nohup /tmp/tp/killloop.sh >/dev/null 2>&1 < /dev/null & echo \$! > /tmp/tp/killpid
        echo [kill-loop-on: 每3s断连1s]"
}
kill_stop() {
  $SSH '[ -f /tmp/tp/killpid ] && kill $(cat /tmp/tp/killpid) 2>/dev/null; iptables -D INPUT -p tcp --dport 8080 -j REJECT --reject-with tcp-reset 2>/dev/null; rm -f /tmp/tp/killpid; echo [kill-loop-off]'
}

run_case() { # $1 标签  $2 模式  $3 toxic 预置函数名(空=无)  $4 ss-K 断连 y/n
  local label="$1" m="$2" setup="$3" k="$4"
  echo "---- $label (mode=$m, toxics=$setup, ssK=$k) ----"
  $SSH "curl -s -X DELETE http://localhost:8080/api/files/$HASH" >/dev/null  # 防上次残留引发秒传 409
  tox_reset
  [ -n "$setup" ] && $setup
  [ "$k" = "y" ] && kill_start
  local out
  out=$(node scripts/weaknet-test.mjs "$m" "$label" 2>&1 | tail -1)
  [ "$k" = "y" ] && kill_stop
  tox_reset
  echo "$out"
  echo "$out" | node -e "
    const j = JSON.parse(require('fs').readFileSync(0,'utf8').trim().split('\n').pop())
    console.log([j.label,j.mode,j.wallSec,j.bytesSentMB,j.attempts,j.chunkFails,j.restarts,j.merged,j.verified,j.ok].join(','))" >> "$CSV"
  $SSH "curl -s -X DELETE http://localhost:8080/api/files/$HASH" >/dev/null
  sleep 3
}

if [ "$SUITE" = "full" ]; then
  run_case "基准-分片"                 chunks    ""          n
  run_case "基准-整文件"               wholefile ""          n
  run_case "延迟100ms-分片"            chunks    tox_latency n
  run_case "丢包1%-分片"               chunks    tox_kill1   n
  run_case "丢包5%-分片"               chunks    tox_kill5   n
  run_case "丢包5%-整文件"             wholefile tox_kill5   n
  run_case "丢包5+延迟100-分片"        chunks    tox_l5      n
  run_case "丢包5+延迟100-整文件"      wholefile tox_l5      n
  run_case "丢包5+延迟100+断连-分片"   chunks    tox_l5      y
  run_case "丢包5+延迟100+断连-整文件" wholefile tox_l5      y
  run_case "带宽限制250KBps-分片"      chunks    tox_bw      n
  run_case "乱序+重复+丢包5+延迟100"   chaos     tox_l5      n
else
  run_case "断连(连接存活1.5s)-分片"   chunks    tox_lifetime n
  run_case "断连(连接存活1.5s)-整文件" wholefile tox_lifetime n
fi

tox_reset
echo "==== 完成 ===="
cat "$CSV"
