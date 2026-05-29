@echo off
cd /d "%~dp0"

echo Android UI Automation Console
echo.
echo URL:
echo http://127.0.0.1:8765/
echo.
echo If the browser does not open automatically, copy the URL above.
echo Press Ctrl+C to stop the server.
echo.

start "" "http://127.0.0.1:8765/"
".venv\Scripts\python.exe" -m uvicorn gui_server:app --host 127.0.0.1 --port 8765

echo.
echo Server exited.
pause
