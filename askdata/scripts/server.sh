#!/usr/bin/env bash
set -euo pipefail
# askdata-server jar 管理：start / stop / status / restart / log
# 用法: ./askdata/scripts/server.sh {start|stop|status|restart|log}
# 配置：脚本同目录的 server.env（若存在）会被自动加载（集中设置 ASKDATA_* 等环境变量）。
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/server.env"
JAR="$(ls "$APP_HOME"/askdata-server/target/askdata-server-*.jar 2>/dev/null | head -1)"
PID_FILE="$APP_HOME/askdata-server.pid"
LOG_FILE="$APP_HOME/askdata-server.log"

# 自动加载同目录配置文件（若存在）
if [ -f "$ENV_FILE" ]; then
  echo "加载配置: $ENV_FILE"
  set -a; . "$ENV_FILE"; set +a
fi

if [ -z "$JAR" ]; then
  echo "未找到 jar（先执行 askdata/scripts/build.sh 构建）：期望 $APP_HOME/askdata-server/target/askdata-server-*.jar" >&2
  exit 1
fi

pid_of() { [ -f "$PID_FILE" ] && cat "$PID_FILE"; }
is_running() { local p; p="$(pid_of)"; [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }

start() {
  if is_running; then echo "已在运行 (pid $(pid_of))"; return 0; fi
  if [ -z "${JAVA_HOME:-}" ]; then echo "JAVA_HOME 未设置，使用 PATH 中的 java"; else export PATH="$JAVA_HOME/bin:$PATH"; fi
  nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "已启动 pid $(pid_of)；日志: $LOG_FILE"
}
stop() {
  if is_running; then
    local p; p="$(pid_of)"
    kill "$p" 2>/dev/null && echo "正在停止 pid $p"
    rm -f "$PID_FILE"
  else echo "未在运行"; fi
}
status() { if is_running; then echo "运行中 (pid $(pid_of))"; else echo "未运行"; fi; }
restart() { stop; sleep 2; start; }
log() { if [ -f "$LOG_FILE" ]; then tail -f "$LOG_FILE"; else echo "无日志文件: $LOG_FILE"; fi; }

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  restart) restart ;;
  log) log ;;
  *) echo "用法: $0 {start|stop|status|restart|log}"; exit 1 ;;
esac
