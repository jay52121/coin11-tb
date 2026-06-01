@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
powershell -NoProfile -Command "try { Invoke-WebRequest 'http://127.0.0.1:8765/api/status' -UseBasicParsing -TimeoutSec 1 | Out-Null; exit 0 } catch { exit 1 }"
if %errorlevel%==0 (
  echo launcher already running %date% %time%>>"logs\gui_server.log"
  exit /b 0
)
echo launcher start %date% %time%>>"logs\gui_server.log"
set TJB_DISABLE_AUTO_START=1
set PYTHONIOENCODING=utf-8
set PYTHONUTF8=1
".venv\Scripts\python.exe" -m uvicorn gui_server:app --host 127.0.0.1 --port 8765 >>"logs\gui_server.log" 2>&1
