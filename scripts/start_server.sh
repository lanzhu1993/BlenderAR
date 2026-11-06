#!/usr/bin/env bash
# 启动 BlenderAR 局域网服务端(首次运行自动创建 venv 并安装锁定依赖)
#
# 用法:
#   scripts/start_server.sh                 # 默认 0.0.0.0:8000
#   PORT=9000 scripts/start_server.sh
#   PAIRING_CODE=123456 scripts/start_server.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$REPO/server"
VENV="$SERVER_DIR/.venv"
PY_BIN="${PY_BIN:-python3}"

if [[ ! -d "$VENV" ]]; then
  echo "== 首次运行:创建虚拟环境并安装依赖 =="
  "$PY_BIN" -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip
  "$VENV/bin/pip" install --quiet -r "$SERVER_DIR/requirements.txt"
fi

PORT="${PORT:-8000}"
ARGS=(--port "$PORT")
if [[ -n "${PAIRING_CODE:-}" ]]; then ARGS+=(--pairing-code "$PAIRING_CODE"); fi
if [[ -n "${ASSETS_DIR:-}" ]]; then ARGS+=(--assets-dir "$ASSETS_DIR"); fi

exec "$VENV/bin/python" "$SERVER_DIR/main.py" "${ARGS[@]}"
