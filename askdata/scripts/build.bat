@echo off
REM 构建 askdata-server 可执行 jar
REM 用法: askdata\scripts\build.bat [--with-tests] [--offline]
setlocal
set OFFLINE=
set TESTS=-DskipTests

:parse
if "%1"=="" goto endparse
if "%1"=="--with-tests" set TESTS=-Dmaven.test.skip=false -DskipTests=false
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
echo ==^> building askdata-server (OFFLINE=%OFFLINE% TESTS=%TESTS%)
mvn %OFFLINE% -pl askdata/askdata-server -am clean package %TESTS%
echo ==^> artifact: askdata/askdata-server/target/askdata-server-*.jar
endlocal
