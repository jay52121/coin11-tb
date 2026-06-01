@echo off
cd /d "%~dp0"

set "TARGET=%~dp0淘金币启动.bat"

echo Installing local protocol: tjb-gui://
echo Target:
echo %TARGET%
echo.

reg add "HKCU\Software\Classes\tjb-gui" /ve /d "URL:Taojinbi GUI Launcher" /f >nul
reg add "HKCU\Software\Classes\tjb-gui" /v "URL Protocol" /d "" /f >nul
reg add "HKCU\Software\Classes\tjb-gui\shell" /f >nul
reg add "HKCU\Software\Classes\tjb-gui\shell\open" /f >nul
reg add "HKCU\Software\Classes\tjb-gui\shell\open\command" /ve /d "\"%TARGET%\" \"%%1\"" /f >nul

echo Done.
echo You can now open:
echo tjb-gui://start
echo.
pause
