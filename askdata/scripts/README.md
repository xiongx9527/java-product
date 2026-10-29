# askdata/scripts 说明

| 脚本 | 平台 | 用途 |
|------|------|------|
| build.sh / build.bat | Linux/macOS / Windows | 构建 askdata-server 可执行 jar（--with-tests / --offline） |
| run-tests.sh / run-tests.bat | Linux/macOS / Windows | 跑测试：全量（内嵌 PG）或 --live 外部引擎 |
| server.sh / server.bat | Linux/macOS / Windows | jar 进程管理：start / stop / status / restart / log |

## server（jar 管理）

运行前请先导出环境变量（ASKDATA_SECRET_KEY、SPRING_PROFILES_ACTIVE、ASKDATA_DB_*、
ASKDATA_ASSISTANT_ENABLED、ASKDATA_VECTOR_STORE_TYPE 等），start 会原样继承当前环境：

```bash
# Linux/macOS
export ASKDATA_SECRET_KEY=<your-secret>
export SPRING_PROFILES_ACTIVE=mysql
# ... 其它 ASKDATA_* 配置./askdata/scripts/server.sh start
./askdata/scripts/server.sh status
./askdata/scripts/server.sh log      # 实时跟踪日志
./askdata/scripts/server.sh restart
./askdata/scripts/server.sh stop
```

```bat
REM Windows
set ASKDATA_SECRET_KEY=<your-secret>
set SPRING_PROFILES_ACTIVE=mysql
askdata\scripts\server.bat start
askdata\scripts\server.bat status
askdata\scripts\server.bat log
askdata\scripts\server.bat restart
askdata\scripts\server.bat stop
```

PID 文件：askdata/askdata-server.pid；日志：askdata/askdata-server.log。
