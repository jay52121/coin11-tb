@echo off
cd /d "%~dp0"

set "TARGET=%~dp0start_tjb_gui_window.bat"

echo Installing local protocol: tjb-gui://
echo Target:
echo %TARGET%
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "$base='HKCU:\Software\Classes\tjb-gui'; New-Item -Path $base -Force | Out-Null; Set-ItemProperty -Path $base -Name '(default)' -Value 'URL:Taojinbi GUI Launcher'; New-ItemProperty -Path $base -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null; New-Item -Path ($base + '\shell\open\command') -Force | Out-Null; Set-ItemProperty -Path ($base + '\shell\open\command') -Name '(default)' -Value 'cmd.exe /k \"\"%TARGET%\" \"%%1\"\"'"

echo Done.
echo You can now open:
echo tjb-gui://start
echo.
pause
