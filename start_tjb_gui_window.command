#!/usr/bin/env bash
set -u

cd "$(dirname "$0")"
mkdir -p logs

URL="http://127.0.0.1:8765/?no_auto_start=1"

if curl -fsS --max-time 1 "http://127.0.0.1:8765/api/status" >/dev/null 2>&1; then
  echo "GUI service is already running."
  echo "$URL"
  open "$URL"
  echo
  echo "You can close this window."
  exit 0
fi

if [[ -x ".venv/bin/python" ]]; then
  PYTHON_BIN=".venv/bin/python"
elif [[ -x "/Users/yzmac/taojinbi/.venv/bin/python" ]]; then
  PYTHON_BIN="/Users/yzmac/taojinbi/.venv/bin/python"
else
  PYTHON_BIN="$(command -v python3 || true)"
fi

if [[ -z "${PYTHON_BIN}" ]]; then
  echo "ERROR: python3 not found."
  exit 1
fi

echo "Starting Android UI Automation Console"
echo
echo "URL:"
echo "$URL"
echo
echo "Python:"
"$PYTHON_BIN" --version
echo
echo "Press Ctrl+C in this window to stop the GUI service."
echo

(
  for _ in {1..30}; do
    if curl -fsS --max-time 1 "http://127.0.0.1:8765/api/status" >/dev/null 2>&1; then
      open "$URL"
      exit 0
    fi
    sleep 1
  done
) &

export TJB_DISABLE_AUTO_START=1
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

while true; do
  "$PYTHON_BIN" -m uvicorn gui_server:app --host 127.0.0.1 --port 8765
  code=$?
  if [[ "$code" == "23" ]]; then
    echo
    echo "GUI service restarting in this window..."
    echo
    continue
  fi
  echo
  echo "GUI service exited with code ${code}."
  exit "$code"
done
