@echo off
setlocal
cd /d "%~dp0"
echo 正在启动 LUE ERP……
echo 启动结果会保留在本窗口中，请查看成功提示或错误信息。
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NoExit -ExecutionPolicy Bypass -File "%~dp0scripts\start-erp.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
if not "%RESULT%"=="0" echo 启动未完成。请阅读上方错误信息或 logs 目录中的日志。
echo 按任意键关闭此启动器窗口。
pause >nul
endlocal
exit /b %RESULT%
