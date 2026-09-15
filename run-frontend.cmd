@echo off
cd /d "%~dp0frontend"
call npm.cmd ci
if errorlevel 1 exit /b %errorlevel%
call npm.cmd run dev
