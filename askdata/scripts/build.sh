#!/usr/bin/env bash
set -euo pipefail
# 构建 askdata-server 可执行 jar
# 用法: ./askdata/scripts/build.sh [--with-tests] [--offline]
OFFLINE=""
TESTS="-DskipTests"
for a in "$@"; do
  case "$a" in
    --with-tests) TESTS="-Dmaven.test.skip=false -DskipTests=false" ;;
    --offline) OFFLINE="-o" ;;
  esac
done
if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME 未设置，将使用 PATH 中的 java"
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi
cd "$(dirname "$0")/../.."
echo "==> building askdata-server (OFFLINE=$OFFLINE TESTS=$TESTS)"
mvn $OFFLINE -pl askdata/askdata-server -am clean package $TESTS
echo "==> artifact: askdata/askdata-server/target/askdata-server-*.jar"
