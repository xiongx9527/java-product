# askdata-server 部署文档

askdata-server 是 Spring Boot 3.x 应用（JDK 17），可执行 jar 由 spring-boot-maven-plugin 产出。本文覆盖三种部署方式：jar 直接运行、Docker、Kubernetes，以及运行所需配置。

## 1. 前置依赖

- JDK 17（构建与运行均需；本机 zulu-17 路径：/Library/Java/JavaVirtualMachines/zulu-17.jdk）。
- Maven 3.8+（构建用；离线构建加 -o）。
- 元数据库（三选一，按 profile 切换）：
  - H2（默认，文件库 ./data/askdata，零外部依赖，适合单机/演示）；
  - MySQL（application-mysql.yaml，profile=mysql）；
  - PostgreSQL（profile=postgresql）。
  Flyway 在 application.yaml 中开启，按 db/migration/{common,mysql,postgresql,h2} 自动建表与种子。
- Redis（可选）：仅在开启 redis 缓存/会话/集群锁时需要（askdata.cache.type=redis 或集群 profile）。
- 对象存储（可选）：本地磁盘（默认）或 MinIO / Aliyun OSS / 腾讯云 COS / S3 兼容（FILE_STORAGE_PLATFORM）。

应用端口：8000。健康检查：GET /health。API 文档：/doc.html（Knife4j）、/v3/api-docs（OpenAPI 3）、/swagger-ui。

## 2. 关键配置（环境变量）

下列变量在 application.yaml / application-mysql.yaml / application-cluster.yaml 中以「环境变量 + 默认值回退」形式读取（写法为 VAR:默认值）；未设置时使用默认值。

必填/重要：

- ASKDATA_SECRET_KEY：落库加密密钥。生产必须显式设置且所有实例一致；缺省时空跑随机密钥（重启后历史密文不可解，启动告警）。
- SPRING_PROFILES_ACTIVE：mysql / postgresql / cluster / cluster,postgresql。默认空（H2）。

数据源（MySQL/PG profile）：

- ASKDATA_DB_URL、ASKDATA_DB_DRIVER、ASKDATA_DB_USERNAME、ASKDATA_DB_PASSWORD。

Redis（开启 redis 时）：

- ASKDATA_REDIS_HOST（默认 127.0.0.1）、ASKDATA_REDIS_PORT（6379）、ASKDATA_REDIS_PASSWORD、ASKDATA_REDIS_DATABASE。

缓存与会话：

- ASKDATA_CACHE_TYPE：memory（默认）/ redis / none。
- ASKDATA_SA_TOKEN_STORE：memory（默认）/ redis。

集群：

- ASKDATA_CLUSTER_ENABLED、ASKDATA_CLUSTER_FAIL_FAST。

鉴权：

- ASKDATA_TOKEN_TIMEOUT：访问 token 有效期（秒，默认 691200=8 天）。
- Token 头：X-SQLBOT-TOKEN: Bearer <token>（与 SQLBot 前端兼容）。

对象存储：FILE_STORAGE_PLATFORM（local/minio/aliyun-oss/amazon-s3/tencent-cos）及对应的 MINIO_*/ALIYUN_OSS_*/AMAZON_S3_*/TENCENT_COS_*。

许可证与嵌入：ASKDATA_LICENSE_ENFORCEMENT（默认 bypass）、ASKDATA_LICENSE_SIGN_KEY、ASKDATA_EMBEDDED_AES_KEY。

## 2.5 LLM 对话 / 问数模型（问数、Predict、智能助手）

askdata-server 的「问数（NL2SQL）/ Predict / 智能助手」依赖一个大模型底座。该底座**不在环境变量里硬编码**，而是运行时通过管理界面配置：

- 进入「系统 → AI 模型」，新建/编辑一个对话模型并设为默认（写入 sys_ai_model 表）。需填写：
  - 接口地址 apiDomain（OpenAI 兼容 base，例如 https://api.openai.com、http://<ollama-host>:11434/v1、https://dashscope.aliyuncs.com/compatible-mode/v1）；
  - 模型名 baseModel（例如 gpt-4o-mini、qwen-plus、llama3）；
  - API Key（本地 Ollama 可留空，框架以 sk-local 占位）；
  - 温度 temperature（可选）。
- 框架按 OpenAI 兼容协议调用（自动补齐 /v1），因此任何 OpenAI 兼容网关（OpenAI、Ollama、阿里云百炼/DashScope、智谱、自建 vLLM 等）均可直接接入。
- 问数主链路与 MCP 问数工具（question_answer / mcp_question / mcp_assistant）共用该默认对话模型；未配置时相关接口返回可读错误，不影响其它功能启动。

> 说明：测试类 LlmNl2SqlLiveTest 等「live」用例需要真实 LLM 端点，通过 ASKDATA_LLM_DOMAIN 指定（仅测试用，非生产配置；本环境因无可用模型权重/网络未能实跑，主链路已由 ChatPipelineTest 以桩 LLM 覆盖）。

## 2.6 向量模型与向量存储（语义召回）

语义召回（术语 / SQL 示例 / 表级 / 数据源级）需要 embedding 模型与（可选的）向量库：

- Embedding 模型：
  - 本地模型（默认）：ONNX 版 shibing624/text2vec-base-chinese（768 维、已归一化），权重目录由 askdata.embedding.model-path 指定（默认 /opt/askdata/models，需自行放置 {model-path}/embedding/shibing624_text2vec-base-chinese/，约 793MB，不进 git）。
  - 远程模型：在「系统 → AI 模型」配置 model_type=1 的远程 embedding（OpenAI 兼容 embedding 端点）后优先使用；本地缺失或不可用时自动降级为词法召回，问数主链路不报错。
- 向量存储后端 askdata.vector-store.type（环境变量 ASKDATA_VECTOR_STORE_TYPE）：
  - none（默认）：向量存元数据库 JSON 列 + 应用内余弦，零外部依赖；
  - qdrant：ASKDATA_QDRANT_URL / ASKDATA_QDRANT_API_KEY / ASKDATA_QDRANT_GRPC_PORT（gRPC 缺省 6334）；
  - milvus：ASKDATA_MILVUS_HOST / ASKDATA_MILVUS_PORT（19530）/ ASKDATA_MILVUS_TOKEN / ASKDATA_MILVUS_DATABASE 等；
  - weaviate：ASKDATA_WEAVIATE_SCHEME / ASKDATA_WEAVIATE_HOST / ASKDATA_WEAVIATE_PORT / ASKDATA_WEAVIATE_API_KEY；
  - pgvector：ASKDATA_PGVECTOR_HOST / ASKDATA_PGVECTOR_PORT / ASKDATA_PGVECTOR_DATABASE / ASKDATA_PGVECTOR_USER / ASKDATA_PGVECTOR_PASSWORD（需 PG 已安装 pgvector 扩展）；
  - elasticsearch：ASKDATA_ELASTICSEARCH_URL / ASKDATA_ELASTICSEARCH_API_KEY / ASKDATA_ELASTICSEARCH_USERNAME / ASKDATA_ELASTICSEARCH_PASSWORD；
  - redis：ASKDATA_VECTOR_REDIS_HOST / ASKDATA_VECTOR_REDIS_PORT / ASKDATA_VECTOR_REDIS_PASSWORD 等（需 Redis Stack 支持向量检索）。
  - 在线库不可达时自动回落 DB 路径，问数主链路不报错。
- 召回阈值 askdata.vector.recall-threshold（ASKDATA_VECTOR_RECALL_THRESHOLD，默认 0.4）。

## 3. 构建

    # 仅构建（默认跳过测试；maven.test.skip=true）
    mvn -pl askdata/askdata-server -am clean package -DskipTests

    # 构建并跑测试
    mvn -pl askdata/askdata-server -am clean package -Dmaven.test.skip=false -DskipTests=false

产物：askdata/askdata-server/target/askdata-server-<version>.jar（可执行）。

也可使用仓库脚本：askdata/scripts/build.sh（Linux/macOS，封装上述命令，支持离线 -o）或 askdata/scripts/build.bat（Windows）。

## 4. 方式一：jar 直接运行

    export ASKDATA_SECRET_KEY=<your-secret>
    export SPRING_PROFILES_ACTIVE=mysql
    export ASKDATA_DB_URL=jdbc:mysql://127.0.0.1:3306/askdata?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true
    export ASKDATA_DB_USERNAME=askdata
    export ASKDATA_DB_PASSWORD=<db-pwd>

    # —— AI 模型（问数/助手/语义召回）——
    # 1) 本地 embedding 模型目录（约 793MB，需自行放置；缺失自动降级词法召回）
    export ASKDATA_EMBEDDING_MODEL_PATH=/opt/askdata/models
    # 2) 向量存储后端：qdrant（需先起 qdrant）/ none（默认，DB JSON 列 + 应用内余弦）
    export ASKDATA_VECTOR_STORE_TYPE=qdrant
    export ASKDATA_QDRANT_URL=http://127.0.0.1:6333
    export ASKDATA_QDRANT_GRPC_PORT=6334
    export ASKDATA_VECTOR_RECALL_THRESHOLD=0.4
    # 注：LLM / 远程 embedding 的真实连接信息（apiDomain、baseModel、apiKey）在「系统 -> AI 模型」界面配置，写入 sys_ai_model，不在此硬编码。

    java -jar askdata/askdata-server/target/askdata-server-*.jar

启动后访问 http://<host>:8000/health 与 http://<host>:8000/doc.html。问数/助手可用前，请到「系统 -> AI 模型」配置默认对话模型（见 §2.5）。

> jar 部署的进程管理脚本与 systemd 单元、以及三种方式的总览见仓库 askdata/deploy/ 目录（README.md、systemd/askdata-server.service）。

## 5. 方式二：Docker

仓库已提供 askdata/deploy/docker/Dockerfile（多阶段构建，先在 maven 阶段编译，再拷 jar 到 eclipse-temurin:17-jre 运行）。构建时需以仓库根为上下文：docker build -f askdata/deploy/docker/Dockerfile -t askdata-server:latest .

    # 构建镜像（需能拉取基础镜像与 maven 依赖；CI 环境建议挂载 ~/.m2 缓存）
    docker build -t askdata-server:latest .

    # 运行（示例：MySQL profile + 环境变量）
    docker run -d --name askdata -p 8000:8000 \
      -e ASKDATA_SECRET_KEY=<your-secret> \
      -e SPRING_PROFILES_ACTIVE=mysql \
      -e ASKDATA_DB_URL=jdbc:mysql://<db-host>:3306/askdata?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true \
      -e ASKDATA_DB_USERNAME=askdata \
      -e ASKDATA_DB_PASSWORD=<db-pwd> \
      -e ASKDATA_EMBEDDING_MODEL_PATH=/opt/askdata/models \
      -e ASKDATA_VECTOR_STORE_TYPE=qdrant \
      -e ASKDATA_QDRANT_URL=http://<qdrant-host>:6333 \
      -e ASKDATA_QDRANT_GRPC_PORT=6334 \
      askdata-server:latest
    # LLM / 远程 embedding 连接信息在「系统 -> AI 模型」界面配置（sys_ai_model），不在此硬编码。

askdata/deploy/docker/docker-compose.yml（仓库提供）一键拉起 应用 + MySQL + Redis + MinIO，适合本地集群形态联调（cluster profile）。详见该文件注释，构建镜像使用 askdata/deploy/docker/Dockerfile。

## 6. 方式三：Kubernetes

仓库 askdata/deploy/k8s/ 目录提供清单：configmap.yaml（非密配置）、secret.yaml（密钥/密码，请改用密封密钥或外部密钥管理）、deployment.yaml、service.yaml。

    kubectl apply -f askdata/deploy/k8s/configmap.yaml
    kubectl apply -f askdata/deploy/k8s/secret.yaml
    kubectl apply -f askdata/deploy/k8s/deployment.yaml
    kubectl apply -f askdata/deploy/k8s/service.yaml
    # 可选：启用向量库语义召回时再 apply（并在 configmap 将 ASKDATA_VECTOR_STORE_TYPE 设为 qdrant）
    kubectl apply -f askdata/deploy/k8s/qdrant.yaml

要点：

- Deployment 暴露容器端口 8000；readinessProbe/livenessProbe 均用 GET /health。
- 所有配置经环境变量注入；多实例部署务必开启集群 profile 并共用同一 ASKDATA_SECRET_KEY 与 Redis。
- 元数据库与 Redis 建议以独立有状态服务/托管服务承载，不在 Pod 内嵌。
- 镜像默认 askdata-server:latest，生产应锁定具体版本标签并配置镜像拉取密钥。

## 7. 单机快速起步（零外部依赖）

不设置数据源变量时默认 H2，直接运行 jar 即可；首次启动由 Flyway 建表。适合本地验证与演示。
