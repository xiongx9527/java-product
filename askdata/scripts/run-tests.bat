@echo off
REM 运行测试：全量（内嵌 PG）或可选 live 外部引擎测试
REM 用法: askdata\scripts\run-tests.bat [--live] [--offline]
setlocal
set OFFLINE=
set LIVE=0

:parse
if "%1"=="" goto endparse
if "%1"=="--live" set LIVE=1
if "%1"=="--offline" set OFFLINE=-o
shift
goto parse
:endparse

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME 未设置，将使用 PATH 中的 java
) else (
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

cd /d "%~dp0..\.."

if "%LIVE%"=="1" (
  echo ==^> running LIVE tests (require local PG/MySQL/Redis + SQLBot source)
  if "%ASKDATA_PG_HOST%"=="" set ASKDATA_PG_HOST=127.0.0.1
  if "%ASKDATA_PG_PORT%"=="" set ASKDATA_PG_PORT=5432
  if "%ASKDATA_PG_USER%"=="" set ASKDATA_PG_USER=xiongxiao
  if "%ASKDATA_PG_PASSWORD%"=="" set ASKDATA_PG_PASSWORD=x
  if "%ASKDATA_PG_DB%"=="" set ASKDATA_PG_DB=postgres
  if "%ASKDATA_PG_SCHEMA%"=="" set ASKDATA_PG_SCHEMA=public
  if "%ASKDATA_MYSQL_HOST%"=="" set ASKDATA_MYSQL_HOST=127.0.0.1
  if "%ASKDATA_MYSQL_PORT%"=="" set ASKDATA_MYSQL_PORT=3306
  if "%ASKDATA_MYSQL_USER%"=="" set ASKDATA_MYSQL_USER=askdata_verify
  if "%ASKDATA_MYSQL_PASSWORD%"=="" set ASKDATA_MYSQL_PASSWORD=x
  if "%SQLBOT_ROOT%"=="" set SQLBOT_ROOT=C:\path\to\SQLBot\backend
  if "%REDIS_PORT%"=="" set REDIS_PORT=6379
  mvn %OFFLINE% -pl askdata/askdata-server test -Dmaven.test.skip=false -DskipTests=false -Dtest=PgLiveDialectTest,MySqlLiveDialectTest,AuthWhitelistParityTest,DebounceRedisCrossInstanceTest -Daskdata.test.redis.port=%REDIS_PORT% -Dsqlbot.root=%SQLBOT_ROOT%
) else (
  echo ==^> running FULL suite (embedded PostgreSQL)
  mvn %OFFLINE% -pl askdata/askdata-server -am test -Dmaven.test.skip=false -DskipTests=false
)
endlocal
