#!/usr/bin/env bash
set -euo pipefail
# 运行测试：全量（内嵌 PG）或可选 live 外部引擎测试
# 用法: ./askdata/scripts/run-tests.sh [--live] [--offline]
OFFLINE=""
LIVE=0
for a in "$@"; do
  case "$a" in
    --live) LIVE=1 ;;
    --offline) OFFLINE="-o" ;;
  esac
done
if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME 未设置，将使用 PATH 中的 java"
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi
cd "$(dirname "$0")/../.."
if [ "$LIVE" -eq 1 ]; then
  echo "==> running LIVE tests (require local PG/MySQL/Redis + SQLBot source)"
  export ASKDATA_PG_HOST="${ASKDATA_PG_HOST:-127.0.0.1}"
  export ASKDATA_PG_PORT="${ASKDATA_PG_PORT:-5432}"
  export ASKDATA_PG_USER="${ASKDATA_PG_USER:-xiongxiao}"
  export ASKDATA_PG_PASSWORD="${ASKDATA_PG_PASSWORD:-x}"
  export ASKDATA_PG_DB="${ASKDATA_PG_DB:-postgres}"
  export ASKDATA_PG_SCHEMA="${ASKDATA_PG_SCHEMA:-public}"
  export ASKDATA_MYSQL_HOST="${ASKDATA_MYSQL_HOST:-127.0.0.1}"
  export ASKDATA_MYSQL_PORT="${ASKDATA_MYSQL_PORT:-3306}"
  export ASKDATA_MYSQL_USER="${ASKDATA_MYSQL_USER:-askdata_verify}"
  export ASKDATA_MYSQL_PASSWORD="${ASKDATA_MYSQL_PASSWORD:-x}"
  SQLBOT_ROOT="${SQLBOT_ROOT:-/Users/xiongxiao/Workspace/Idea/codes/SQLBot/backend}"
  REDIS_PORT="${REDIS_PORT:-6379}"
  mvn $OFFLINE -pl askdata/askdata-server test -Dmaven.test.skip=false -DskipTests=false \
    -Dtest=PgLiveDialectTest,MySqlLiveDialectTest,AuthWhitelistParityTest,DebounceRedisCrossInstanceTest \
    -Daskdata.test.redis.port="$REDIS_PORT" \
    -Dsqlbot.root="$SQLBOT_ROOT"
else
  echo "==> running FULL suite (embedded PostgreSQL)"
  mvn $OFFLINE -pl askdata/askdata-server -am test -Dmaven.test.skip=false -DskipTests=false
fi
