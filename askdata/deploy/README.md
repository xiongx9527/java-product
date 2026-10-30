# askdata 部署目录

本目录集中存放 askdata-server 的部署产物与说明。三种部署方式共享同一份运行配置（环境变量）。

## 运行配置（所有方式通用）

环境变量集中配置在 askdata/scripts/server.env（Linux/macOS）/ server.env.bat（Windows），
模板含数据源、Redis、对象存储、AI 模型（问数/助手）、embedding 模型与向量存储等示例值。
修改示例值后，jar 的 server 脚本会自动加载；docker-compose 与 k8s 另在各清单中注入。

- LLM / 远程 embedding 的真实连接信息（apiDomain、baseModel、apiKey）在「系统 -> AI 模型」界面配置（sys_ai_model），不在此硬编码。

## 三种部署方式

### 1. jar 直接运行（零容器）
- 构建：askdata/scripts/build.sh（Linux/macOS）/ build.bat（Windows）。
- 进程管理：askdata/scripts/server.sh|server.bat 提供 start / stop / status / restart / log。
- 生产常驻（Linux systemd）：deploy/systemd/askdata-server.service，日志走 journalctl。
- 说明见 docs/deployment.md §4。

### 2. Docker
- 镜像构建：docker build -f askdata/deploy/docker/Dockerfile -t askdata-server:latest .（上下文为仓库根）。
- 一键联调：askdata/deploy/docker/docker-compose.yml（应用 + MySQL + Redis + MinIO + qdrant）。
- 说明见 docs/deployment.md §5。

### 3. Kubernetes
- 清单：deploy/k8s/{configmap,secret,deployment,service,qdrant}.yaml。
- 说明见 docs/deployment.md §6。

## 目录结构

    deploy/
      docker/      Dockerfile + docker-compose.yml
      k8s/         configmap / secret / deployment / service / qdrant
      systemd/     askdata-server.service（jar 系统服务）
      （业务系统对接文档见 askdata/integration/：MCP 与嵌入）
