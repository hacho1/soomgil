@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0scripts\configure-gmail.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
