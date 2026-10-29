@echo off
REM askdata-server jar 管理：start / stop / status / restart / log
REM 用法: askdata\scripts\server.bat {start^|stop^|status^|restart^|log}
REM 运行前请先设置环境变量（ASKDATA_SECRET_KEY / SPRING_PROFILES_ACTIVE / ASKDATA_DB_* 等），start 会继承。
setlocal
set "APP_HOME=%~dp0.."
set "LOG=%APP_HOME%\askdata-server.log"
set "PID=%APP_HOME%\askdata-server.pid"

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME 未设置，使用 PATH 中的 java
) else (
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

if "%1"=="start" goto do_start
if "%1"=="stop" goto do_stop
if "%1"=="status" goto do_status
if "%1"=="restart" goto do_restart
if "%1"=="log" goto do_log
echo 用法: %0 {start^|stop^|status^|restart^|log}
exit /b 1

:do_start
if exist "%PID%" (
  set /p P=<"%PID%"
  tasklist /fi "PID eq %P%" | find "java" >nul && (echo 已在运行 (pid %P%) & exit /b 0)
)
for /f "delims=" %%j in ('dir /b "%APP_HOME%\askdata-server\target\askdata-server-*.jar" 2^>nul') do set "JAR=%%j"
if not defined JAR (echo 未找到 jar（先执行 build.bat 构建） & exit /b 1)
start "" /B java -jar "%APP_HOME%\askdata-server\target\%JAR%" > "%LOG%" 2>&1
for /f "tokens=2" %%a in ('tasklist /fi "imagename eq java.exe" /fo list ^| find "PID:"') do set "JAVAPID=%%a"
echo %JAVAPID% > "%PID%"
echo 已启动 pid %JAVAPID%；日志: %LOG%
exit /b 0

:do_stop
if not exist "%PID%" (echo 未在运行 & exit /b 0)
set /p P=<"%PID%"
taskkill /PID %P% /F >nul 2>&1 && echo 已停止 %P%
del "%PID%" >nul 2>&1
exit /b 0

:do_status
if not exist "%PID%" (echo 未运行 & exit /b 0)
set /p P=<"%PID%"
tasklist /fi "PID eq %P%" | find "java" >nul && (echo 运行中 (pid %P%)) || (echo 未运行)
exit /b 0

:do_restart
call :do_stop
timeout /t 2 >nul
goto do_start

:do_log
if exist "%LOG%" (powershell -NoProfile -Command "Get-Content -Tail 50 -Wait '%LOG%'") else (echo 无日志文件: %LOG%)
exit /b 0
