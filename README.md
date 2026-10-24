# java-product

Java 多模块产品仓库，当前聚焦 **AskData（问数）** 产品。

## 项目概览

本仓库采用 Maven 多模块结构，基于自研 `fw-plus` 框架生态构建企业级 AI 应用。当前主产品 AskData 是一套自然语言问数（Text-to-SQL）系统，支持通过自然语言提问自动生成 SQL、查询数据并可视化结果，类似 SQLBot。

## 项目结构

```text
java-product/
├── pom.xml                    # 根聚合 POM（继承 fw-plus-ai-build:0.3.0-SNAPSHOT）
├── askdata/                   # AskData 产品聚合模块
│   ├── pom.xml
│   └── askdata-server/        # AskData 单体后端服务
├── docs/                      # 项目文档
│   ├── audit/                 # 能力审计报告
│   ├── comet/                 # Comet 变更文档
│   └── superpowers/           # Superpowers 相关文档
└── LICENSE                    # Apache License 2.0
```

## AskData（问数）

AskData 是基于大语言模型的自然语言问数系统，采用前后端一体化的单体架构：

- **后端**：Java 17 + Spring Boot 3.5.8，采用 Spring MVC、MyBatis-Plus 等技术栈
- **前端**：Vue 3（基于 SQLBot 前端改造），构建后嵌入到 Spring Boot 静态资源
- **打包方式**：单 JAR 部署，前后端同进程托管（端口 8000）
- **核心能力**：自然语言转 SQL、数据源管理、问答对话、图表可视化、定时任务、权限管理

详细后端文档请参考 [askdata/askdata-server/README.md](askdata/askdata-server/README.md)。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- Node.js 16+（前端开发）
- Git

### 构建与运行

```bash
# 克隆仓库
git clone <repo-url>
cd java-product

# 构建全部模块（跳过测试）
mvn -Pskip-test package

# 运行服务
java -jar askdata/askdata-server/target/askdata-server.jar

# 访问应用
# 浏览器打开 http://localhost:8000
# 默认管理员账号：admin / SQLBot@123456
```

### 前端开发

```bash
# 进入前端目录
cd askdata/askdata-server/web

# 安装依赖
npm install

# 开发模式（热更新，代理后端 API）
npm run dev
# 访问 http://localhost:5173

# 构建生产版本
npm run build
```

## 技术栈

| 分类 | 技术 | 说明 |
|------|------|------|
| **基础框架** | Spring Boot 3.5.8 | 应用框架 |
| **开发语言** | Java 17 | 后端开发语言 |
| **权限认证** | Sa-Token 1.46.0 | 基于 Token 的认证授权框架 |
| **数据访问** | MyBatis-Plus | ORM 框架 |
| **数据库** | H2 / MySQL / PostgreSQL 等 | 支持多种数据源（通过 JDBC） |
| **AI 集成** | fw-plus-ai | 大模型接入、向量检索、Agent 能力 |
| **前端框架** | Vue 3 + TypeScript + Vite | 前端技术栈 |
| **文件存储** | x-file-storage | 统一文件存储抽象 |
| **任务调度** | Spring Task | 定时问数任务 |

## 配置说明

主要配置文件位于 `askdata/askdata-server/src/main/resources/application.yaml`：

- **服务端口**：默认 8000
- **数据库**：默认使用 H2 文件库 `./data/askdata`
- **文件存储**：本地存储路径 `./data/files`
- **Excel 上传**：最大 50MB
- **向量模型**：本地 ONNX 模型（shibing624/text2vec-base-chinese）
- **定时任务**：默认启用，轮询间隔 30 秒

更多配置详情请参考后端 README。

## 测试

项目包含单元测试和 E2E 测试：

```bash
# 后端单元测试
mvn test

# E2E 测试（Playwright）
cd askdata/askdata-server/e2e
npm test
```

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。
