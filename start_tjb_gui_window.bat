@echo off
cd /d "%~dp0"
if not exist logs mkdir logs

powershell -NoProfile -Command "try { Invoke-WebRequest 'http://127.0.0.1:8765/api/status' -UseBasicParsing -TimeoutSec 1 | Out-Null; exit 0 } catch { exit 1 }"
if %errorlevel%==0 (
  echo GUI service is already running.
  echo http://127.0.0.1:8765/?no_auto_start=1
  start "" "http://127.0.0.1:8765/?no_auto_start=1"
  pause
  exit /b 0
)

echo Starting Android UI Automation Console
echo.
echo URL:
echo http://127.0.0.1:8765/?no_auto_start=1
echo.
echo Press Ctrl+C in this window to stop the GUI service.
echo.

set TJB_DISABLE_AUTO_START=1
set PYTHONIOENCODING=utf-8
set PYTHONUTF8=1
".venv\Scripts\python.exe" -m uvicorn gui_server:app --host 127.0.0.1 --port 8765

echo.
echo GUI service exited.
pause
