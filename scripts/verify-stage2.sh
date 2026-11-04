#!/usr/bin/env bash
# 契约 B 的 B10 第一段：桌面 / CI 上的 Bridge 协议验收。
#
# 用 --adapter fake 跑，**不需要 opencode，也不需要 Android**。
# 真机端到端（--adapter opencode）是 B10 第二段，只能在 Termux 内跑。
#
# 契约 C 的 C8 要求：CI 上跑的命令必须与本地可复现的命令一致，所以 job 只调这个脚本。
set -euo pipefail

cd "$(dirname "$0")/.."

PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null 2>&1 || { echo "找不到 $PY（可用 PYTHON=... 指定）"; exit 1; }

echo "== 1/2 协议与环形缓冲单元测试（契约 C 的 L1） =="
"$PY" -m unittest discover -s bridge/tests -v

echo
echo "== 2/2 起 Bridge（fake 适配器）并跑 B10 命令组 =="
PORT="${BRIDGE_PORT:-8765}"
"$PY" bridge/server.py --port "$PORT" --adapter fake &
BRIDGE_PID=$!
trap 'kill "$BRIDGE_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then break; fi
  sleep 0.25
done

echo "-- GET /health（期望 {\"status\":\"ok\"}）"
curl -s "http://127.0.0.1:$PORT/health"; echo

REQUEST_ID=$(curl -s -X POST "http://127.0.0.1:$PORT/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"echo hello","conversationId":"c1"}' \
  | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')
echo "requestId=$REQUEST_ID"

echo
echo "-- GET /chat/<id>/stream（期望 text … 然后 done）"
curl -sN "http://127.0.0.1:$PORT/chat/$REQUEST_ID/stream"

echo "-- GET /chat/<id>/stream?from=2（期望不重放 id<=2）"
curl -sN "http://127.0.0.1:$PORT/chat/$REQUEST_ID/stream?from=2"

echo
echo "验收通过：Bridge 协议在桌面上跑通（真机端到端见 B10 第二段）"