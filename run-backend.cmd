@echo off
cd /d "%~dp0backend"
call gradlew.bat bootJar
if errorlevel 1 exit /b %errorlevel%
java -jar build\libs\backend-0.0.1-SNAPSHOT.jar
