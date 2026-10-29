# askdata/scripts 说明

| 脚本 | 平台 | 用途 |
|------|------|------|
| build.sh / build.bat | Linux/macOS / Windows | 构建 askdata-server 可执行 jar（--with-tests / --offline） |
| run-tests.sh / run-tests.bat | Linux/macOS / Windows | 跑测试：全量（内嵌 PG）或 --live 外部引擎 |
| server.sh / server.bat | Linux/macOS / Windows | jar 进程管理：start / stop / status / restart / log |

## server（jar 管理）

配置：脚本同目录的 server.env（Linux/macOS）/ server.env.bat（Windows）会被自动加载，用于集中设置 ASKDATA_* 等运行环境变量；也可不创建该文件，改为在调用前自行 export / set 覆盖。server.env 已附模板（含数据源、Redis、对象存储、AI 模型/向量存储等示例值）。

```bash
# Linux/macOS
# 可选：编辑 server.env 填好 ASKDATA_SECRET_KEY / 数据源等，再启动
./askdata/scripts/server.sh start
./askdata/scripts/server.sh status
./askdata/scripts/server.sh log      # 实时跟踪日志
./askdata/scripts/server.sh restart
./askdata/scripts/server.sh stop
```

```bat
REM Windows
REM 可选：编辑 server.env.bat 填好配置，再启动
askdata\scripts\server.bat start
askdata\scripts\server.bat status
askdata\scripts\server.bat log
askdata\scripts\server.bat restart
askdata\scripts\server.bat stop
```

PID 文件：askdata/askdata-server.pid；日志：askdata/askdata-server.log。
