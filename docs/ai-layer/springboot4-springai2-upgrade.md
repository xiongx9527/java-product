# 升级说明：Spring Boot 4 + Spring AI 2

> 选定路径：**Spring Boot 4.1.2（GA）+ Spring AI 2.0.0-RC2（RC）+ Java 17**
> 性质：升级说明，不含代码实施（端口层规范已拆到 `ai-port-layer.md`）
> 基线：`main`（Java 8 字节码 / SB 2.7.18 / JDK 8 运行镜像 / 无 AI 框架）

---

## 目录

- [0. 决策记录](#0-决策记录)
- [1. 版本矩阵与平台事实](#1-版本矩阵与平台事实)
  - [1.1 当前线（main）](#11-当前线main)
  - [1.2 三条候选路径](#12-三条候选路径)
  - [1.3 为什么选 C](#13-为什么选-c)
- [2. 依赖坐标](#2-依赖坐标)
  - [2.1 Spring AI 2（选定路径）](#21-spring-ai-2选定路径)
  - [2.2 langchain4j（备选路径 A）](#22-langchain4j备选路径-a)
  - [2.3 坐标陷阱：`1.23.0` 与 `1.23.0-beta33`](#23-坐标陷阱1230-与-1230-beta33)
  - [2.4 构建前置：Maven 镜像必须改 https](#24-构建前置maven-镜像必须改-https)
- [3. 破坏性变更](#3-破坏性变更)
  - [3.1 `javax` → `jakarta`](#31-javax--jakarta)
  - [3.2 Spring Boot 2.7 → 4.1 的额外变更](#32-spring-boot-27--41-的额外变更)
  - [3.3 Sa-Token 变体](#33-sa-token-变体)
  - [3.4 第三方生态（最大风险）](#34-第三方生态最大风险)
- [4. 行为变更](#4-行为变更)
  - [4.1 模型调用](#41-模型调用)
  - [4.2 模型实例缓存](#42-模型实例缓存)
- [5. 数据兼容与向量层](#5-数据兼容与向量层)
  - [5.1 无需数据迁移](#51-无需数据迁移)
  - [5.2 为什么不换向量库](#52-为什么不换向量库)
  - [5.3 唯一值得做的优化：批量 upsert](#53-唯一值得做的优化批量-upsert)
  - [5.4 迁移顺序与兼容窗口（滚动升级）](#54-迁移顺序与兼容窗口滚动升级)
- [文档地图](#文档地图)
- [7. Flux → 回调桥（Spring AI 专用）](#7-flux--回调桥spring-ai-专用)
- [8. 实施顺序](#8-实施顺序)
- [9. 验收](#9-验收)
  - [9.1 契约测试（切换的判定标准）](#91-契约测试切换的判定标准)
  - [9.2 Spike（正式改造前的阻断项）](#92-spike正式改造前的阻断项)
  - [9.3 功能回归](#93-功能回归)
- [10. 风险与回退](#10-风险与回退)
- [11. 未验证项](#11-未验证项)
- [12. 结论](#12-结论)

---

## 0. 决策记录

| 项 | 结论 |
| --- | --- |
| 路径选择 | **C：Spring Boot 4.1.2 + Spring AI 2.0.0-RC2** |
| 是否现在落码 | **否** —— 只出文档，不动代码 |
| 前置条件 | 当前 Classic change `aimodel-config-flow-revamp` **归档后**，再开新 change 实施 |
| 新 change 建议范围 | ① 端口层 + `NativeChatPort` + 契约测试（行为零变化）；② 平台升级；③ Spring AI 接入（三步分开） |
| 可选能力的定义 | 除主流程外全部可选；**可选 = 默认关闭，不是可以不实现**（每个端口都要有生产实现 + Noop + 双跑测试，§6.13） |
| 观测导出 | Langfuse **本机 v3 真实跑通**（§6.11.6 有实测表）；LangSmith 同实现、只配置差异，无账号不实测（§11） |
| 文档解析 | 抽象用 langchain4j `DocumentParser`，实现用 **Tika**；三种解析器已实测对比（§6.23.2）。但 langchain4j 的解析器是 **Java 17 字节码**、Tika 3 是 **Java 11 字节码**，Java 8 分支只能用 Tika 2.9.4 或 POI + PDFBox（§6.23.3~6.23.4） |
| 模型故障转移 | **必须做**：默认模型单点 + 欠费是常态（§6.26）。只在首帧前转移，`ErrorKind` 扩到 10 值 |
| 大数据量导出 | 借 easypoi 的分页模型，**不引 easypoi 依赖**（4.5.0 停在 POI 4.1.1，与锁定的 5.2.3 冲突，且已无维护，§6.27.3） |

**为什么等归档**：`aimodel-config-flow-revamp` 仍在 Build 阶段，范围是"AI 模型配置流程"（前端字段、模型类型、默认模型、连通性校验），**不含模型调用抽象层**。端口层属于独立需求，按工作流归属规则不应并入该 change；并行改同一片代码也会互相干扰。

---

## 1. 版本矩阵与平台事实

### 1.1 当前线（main）

| 项 | 值 | 出处 |
| --- | --- | --- |
| 编译目标 | `maven.compiler.source/target=1.8` | `askdata/askdata-server/pom.xml` |
| Spring Boot | 2.7.18 | 同上 |
| 运行镜像 | JDK 8 | `askdata/deploy/docker/Dockerfile`，提交 `41312f2` |
| 命名空间 | `javax.*` | 全仓 |
| langchain4j | **未使用**（依赖已摘除，全仓零 `dev.langchain4j` 引用） | — |

### 1.2 三条候选路径

| 路径 | Java | Spring Boot | 框架 | 状态 |
| --- | --- | --- | --- | --- |
| A. SB3 + langchain4j | 17 | 3.5.x | langchain4j 1.23.0 | 全 GA |
| B. SB3 + Spring AI 1 | 17 | 3.5.15 | Spring AI 1.1.8 | GA，但缺 Responses |
| **C. SB4 + Spring AI 2** | 17 | **4.1.2（GA）** | **Spring AI 2.0.0-RC2** | 框架 RC |

### 1.3 为什么选 C

| 能力 | Spring AI 1.1.8 | Spring AI 2.0 | langchain4j 1.23 |
| --- | --- | --- | --- |
| OpenAI Chat Completions | ✅ | ✅ | ✅ |
| Anthropic Messages | ✅ | ✅ | ✅ |
| Azure OpenAI | ✅ | ✅ | ✅ |
| **OpenAI Responses** | ❌ | ✅ | ✅ |
| 思考流 | 需确认 | ✅ `ReasoningPart` | ✅ `onPartialThinking` |
| `extra_body` | ✅ | ✅ 原生 | ⚠️ 需构造嵌套 |
| 流式模型 | Flux | Flux | 回调 |

**关键事实**（Maven Central 实测）：

- Spring Boot 4 已 GA：4.0.9 / **4.1.2**（最新稳定），4.2.0-RC1 为预览
- Spring AI 1.1.x 基线 `spring-boot.version=3.5.15`
- Spring AI 2.0.x 基线 `spring-boot.version=4.1.2`
- Spring AI 2.x 尚无 GA（2.0.0 只有 RC2；2.1.0-RC1 需 SB 4.2.0-RC1）
- langchain4j 1.23.0 GA；`langchain4j-agentic` 为 `1.23.0-beta33`，**历史上从未 GA**

---

## 2. 依赖坐标

### 2.1 Spring AI 2（选定路径）

```xml
<properties>
  <spring-ai.version>2.0.0-RC2</spring-ai.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- OpenAI 兼容 + Responses API（openai/responses 包） -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
  <!-- Anthropic Messages -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
  </dependency>
  <!-- Azure OpenAI -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-azure-openai</artifactId>
  </dependency>
</dependencies>
```

**不引入** `spring-ai-starter-vector-store-qdrant`：候选集过滤与"只取 id+score"两项能力倒退（见 5.2）。

### 2.2 langchain4j（备选路径 A）

若第 0.5 步生态探测不通过，退回 SB3 + langchain4j：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-bom</artifactId>
      <version>1.23.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

构件：`langchain4j` / `langchain4j-open-ai` / `langchain4j-anthropic` / `langchain4j-azure-open-ai`。

### 2.3 坐标陷阱：`1.23.0` 与 `1.23.0-beta33`

| 构件 | `<release>` | 性质 |
| --- | --- | --- |
| `dev.langchain4j:langchain4j` | 1.23.0 | GA |
| `dev.langchain4j:langchain4j-agentic` | **1.23.0-beta33** | beta，从未 GA |

写 `<version>1.23.0</version>` 引 agentic 会解析失败。`-betaN` 被 Maven 视为预发布，版本区间与部分依赖检查工具会特殊处理。

### 2.4 构建前置：Maven 镜像必须改 https

当前 `~/.m2/settings.xml` 的 aliyun 为 http 源，被 Maven 3.8+ HTTP blocker 拦截：

```
Blocked mirror for repositories: [nexus-aliyun (http://maven.aliyun.com/...)]
```

改为：

```xml
<mirror>
  <id>nexus-aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

---

## 3. 破坏性变更

### 3.1 `javax` → `jakarta`

| 包 | 处理 |
| --- | --- |
| `javax.servlet.*` | → `jakarta.servlet.*` |
| `javax.annotation.*` | → `jakarta.annotation.*` |
| `javax.validation.*` | → `jakarta.validation.*` |
| `javax.persistence.*` | → `jakarta.persistence.*` |

已知受影响位置：`ChatModelClient`（`javax.servlet.http.HttpServletResponse`）、
`EmbeddingBackfillRunner`（`javax.annotation.PreDestroy`）。

### 3.2 Spring Boot 2.7 → 4.1 的额外变更

| 项 | 变化 |
| --- | --- |
| Spring Framework | 5.3 → 7.x（部分 API 移除、弃用清理） |
| Jakarta EE | 8 → 11 |
| 自动配置注册 | `spring.factories` 中 `EnvironmentPostProcessor` 仍支持，建议迁到 `META-INF/spring/...AutoConfiguration.imports` |
| Actuator | 端点路径与暴露策略有调整 |
| 配置属性 | 部分 `spring.*` 属性重命名或移除 |

### 3.3 Sa-Token 变体

当前用 SB2 变体（`sa-token-spring-boot-starter`），pom 中有注释说明"boot3 变体会传递引入
`jakarta.servlet-api 6.0.0`（major 55），JDK 8 加载即抛 `UnsupportedClassVersionError`"。

**SB4 上必须换对应变体，且该变体是否存在需先确认** —— 这是本路径的第一个阻断点。

### 3.4 第三方生态（最大风险）

| 依赖 | SB4 兼容性 |
| --- | --- |
| `sa-token` | **未知，需确认** |
| `mybatis-plus` | 需确认 |
| `justauth` | 需确认 |
| `x-file-storage`（minio / aliyun-oss / tencent-cos / aws-s3） | 需确认 |
| `poi` / `easytrans` | 需确认 |
| fw-plus 全家桶（MCP starter、auth、boot） | **未知，需确认** |

**任何一个卡住，整条路径不可行。**

---

## 4. 行为变更

### 4.1 模型调用

| 项 | 现状 | 框架化后 |
| --- | --- | --- |
| HTTP 客户端 | `HttpURLConnection` 手写 | 框架内置 |
| SSE 解析 | 手写（3 套协议分支） | 框架解析 |
| 思考流 | 自己识别 `reasoning_content` | `ReasoningPart`（Spring AI）/ `onPartialThinking`（langchain4j） |
| 私有字段 | 自己识别任意字段 | Spring AI `getMetadata()`（覆盖度待测） |
| token 用量 | 自己从帧里提 | `getMetadata().getUsage()` |

**不变**：SSE 响应格式（`data:{json}` 帧、`finish_step`、`record_id`、图表帧、`error` 帧）逐字保持，SQLBot 兼容性不受影响。

### 4.2 模型实例缓存

`baseUrl` / `apiKey` 是模型实例级参数（builder 参数），**不能按请求覆盖**；`modelName` / `temperature` / `extraBody` 可以按请求覆盖。

`ai_model` 每行有独立 `api_domain` + `api_key`，因此必须按 `(protocol, baseUrl, apiKeyHash)` 缓存实例，并设上限（建议 64）。

| 场景 | 做法 |
| --- | --- |
| 同端点、不同模型名 | 复用实例，请求里换 `modelName` |
| 不同端点 / 不同 key | 重建实例 → 工厂 + 指纹缓存 + 失效 |

---

## 5. 数据兼容与向量层

### 5.1 无需数据迁移

| 项 | 说明 |
| --- | --- |
| Qdrant 集合名 | 不变：`{collection-prefix}-{object-type}` |
| point id | 不变：业务主键（long） |
| 向量维度 | 不变：由 embedding 模型决定 |
| 存量向量 | 不变：REST 实现原样保留 |
| `ai_model` 表结构 | 不变 |
| 业务表 | 不变 |

**原因**：不引入任何框架的向量库实现，向量层代码零改动，不存在集合重建 / 维度不匹配 / point id 格式变化的风险。升级失败可直接回退，不留脏数据。

### 5.2 为什么不换向量库

| 能力 | 现有自研（REST） | langchain4j-qdrant | Spring AI VectorStore |
| --- | --- | --- | --- |
| 候选集 `has_id` 过滤 | ✅ 原生 | ❌ `Filter` 只有 metadata 语义 | ❌ `filterExpression` 只有 metadata 语义 |
| 只取 id + score | ✅ `with_payload=false` | ❌ `search()` 强制回传向量 | ✅ 返回 `Document`，不带向量 |
| 业务 id 直连 point id | ✅ | ✅（`toPointId` 支持数值串） | 需确认 |
| 依赖 | 0 | gRPC + protobuf | 额外 starter |

**两个框架都没有 point id 过滤能力。** 既然要补，不如保留现有实现 —— 它已经拿到了框架给不了的东西。

### 5.3 唯一值得做的优化：批量 upsert

现状 `EmbeddingBackfillRunner` 逐条 `writeEmbedding`，N 张表 = N 次 HTTP。给 `VectorStore` 加批量方法即可，不需要引入框架：

```java
default void upsertAll(String collection, List<Long> ids, List<double[]> vectors) {
    for (int i = 0; i < ids.size(); i++) {
        upsert(collection, ids.get(i), vectors.get(i));
    }
}
```

`QdrantVectorStore` 覆写为单次 `PUT /points?wait=true`。

---

### 5.4 迁移顺序与兼容窗口（滚动升级）

#### 5.4.1 现状

| 项 | 现状 |
| --- | --- |
| 迁移位置 | `classpath:db/migration/common` + `classpath:db/migration/{vendor}`（`application.yaml:59-66`） |
| 版本分布 | common：V2 seed / V3 scheduled_lease / V4 chart_image_token；mysql 与 postgresql **各有** V1 baseline 与 V5 ai_model_states |
| 基线 | `baseline-on-migrate: true`，`baseline-version: "2"` |
| 下一个版本 | **V6**，且 **mysql/ 与 postgresql/ 各写一份**（不是只写一份） |

本次规划新增 6 张表（`ai_model_key` / `ai_model_health` / `export_task` / `chat_feedback` / `eval_case` / `mcp_server`）+ `ai_model` 3 列 + `cache_epoch`，至少一个 V6，必要时拆 V6/V7。

#### 5.4.2 三条规则：expand → contract

1. **只加不删**：新版本只做加表、加列、加索引；新列必须可空或有默认值。
2. **旧代码不读新表也能跑**：新表只被新代码使用；新列不得被旧代码的 `SELECT *` 意外带出（上线前 grep 一遍）。
3. **收缩延后一个版本**：删列、改类型、加 `NOT NULL` 必须等旧版本全部下线后，单独一个版本做。

违反第 1 条，回滚就是灾难；违反第 2 条，旧实例会在运行时抛 `Column not found`。

#### 5.4.3 滚动升级的 Flyway 冲突（必须实测）

蓝绿期间新旧实例共享一个库：

```text
新版本启动 → 执行 V6 → 写入 flyway_schema_history
旧版本重启 → classpath 里没有 V6 → 校验发现"库里有、代码没有"
```

Flyway 对"已应用但本地不存在"的迁移默认会校验失败（具体行为与版本、`ignoreMigrationPatterns` 有关，**不能靠推断**）。两条应对：

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| **A（推荐）** | 迁移窗口内旧实例不重启：部署脚本约束 + 就绪探针 | 一次部署期间不能靠重启旧实例回滚 |
| B | 打开 `spring.flyway.ignore-migration-patterns: "*:future"` | 会掩盖真实的漏迁移，必须配告警 |

**验收方式**：两个实例、两个版本、共享一个库，跑一遍完整上线 + 回滚，记录 Flyway 的实际行为。这条不能只写在文档里。

#### 5.4.4 回滚：代码能回，数据不能

| 对象 | 回滚后状态 | 处理 |
| --- | --- | --- |
| 新表（如 `ai_model_key`） | 旧代码不读，数据留着无害 | 保留，下次上线继续用 |
| 新列（可空） | 旧代码不写，值为 NULL | 保留 |
| 有副作用的表（`chat_feedback` / `export_task` / `eval_case`） | 旧代码不认识，成为孤儿数据 | 保留但不可见；准备人工清理脚本 |
| 向量索引（维度变更） | 旧代码用旧模型，维度不匹配 | **不可回滚**，必须重建索引 |

规则：**凡是有副作用的表，回滚前先停写入**；向量维度变更是单向操作，升级前必须备份。

#### 5.4.5 上线前检查清单

1. 两份方言都写了？（`mysql/` + `postgresql/`）
2. 只加不删？grep `DROP` / `MODIFY` / `NOT NULL` 确认没有收缩操作。
3. 有没有 `SELECT *` 会被新列影响？
4. 大表加索引是否用了 `ALGORITHM=INPLACE, LOCK=NONE`（MySQL）？
5. 迁移耗时估算过吗？`chat_record` 100 万行加索引大约多久？
6. 回滚脚本写了吗？（`DROP TABLE IF EXISTS` / 删列脚本，即使不打算用）

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [`README.md`](./README.md) | **总目录**：四份文档分工、三条阅读路径、主题索引、按问题查 |
| 本文 | 升级说明：版本矩阵、破坏性变更、实施顺序、验收、回退、未验证项 |
| `ai-port-layer.md` | AI 端口层规范：§6 全部（接口、分层、切换、观测、工具、多模态、Pipeline 抽象、故障转移、导出） |
| `risk-register.md` | 风险清单：附录 A 图表 / B SQL / C 会话与意图 / D 模型监测 |
| `enhancement-plan.md` | 增强清单：P0 15 项（含明细）/ P1 18 项 / P2 9 项 / P3 3 项 + 波次建议 |

> 文中出现的 `§6.x` 引用均指向 `ai-port-layer.md`；`附录 A–D` 指向 `risk-register.md`；`P0 #n` 指向 `enhancement-plan.md`。
> 找不到东西时先看 `README.md` §3 主题索引 / §4 按问题查。

---

## 7. Flux → 回调桥（Spring AI 专用）

`stream()` 是阻塞的、错误只走 `onError(String)`，所以桥接要**把 Flux 的三种终止翻译成回调**，而不是抛异常：

```java
public StreamHandle stream(ModelRef model, List<Message> messages, final StreamSink sink) {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    final Disposable disposable = cache.streaming(model).stream(toPrompt(messages)).subscribe(
            resp -> {
                String text = textOf(resp);        // content 与 reasoning 同帧，与现状一致
                String reasoning = reasoningOf(resp);
                if (text != null || reasoning != null) {
                    sink.onDelta(text, reasoning);
                }
                usageOf(resp).ifPresent(u -> sink.onUsage(u.input(), u.output(), u.total()));
            },
            err -> {
                failure.set(err);
                done.countDown();
            },
            done::countDown);

    StreamHandle handle = new StreamHandle() {
        @Override
        public void cancel() {
            disposable.dispose();
            done.countDown();
        }
    };

    boolean finished;
    try {
        finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        disposable.dispose();
        sink.onError("模型调用被中断");
        return handle;
    }
    if (!finished) {
        disposable.dispose();
        sink.onError("模型调用超时（" + timeoutMs + "ms）");
    } else if (failure.get() != null) {
        sink.onError(messageOf(failure.get()));
    } else {
        sink.onComplete();
    }
    return handle;
}
```

**必须处理的三个语义**：超时（Flux 无天然超时，靠 `await`）、取消（`Disposable.dispose()`，且取消后 `onError` 不再触发）、背压（`subscribe` 默认无界请求）。

---

## 8. 实施顺序

| 步骤 | 内容 | 依赖 | 风险 |
| --- | --- | --- | --- |
| 0 | 修 Maven 镜像为 https | — | 无 |
| 0.5 | **第三方生态探测**：sa-token / mybatis-plus / x-file-storage / fw-plus 是否有 SB4 兼容版本 | — | **阻断项** |
| 1 | 引入端口层 + `NativeChatPort` + 契约测试（含 `ModelRef` / `Message` / `Usage` 搬包、`CallContext` / `PortError` 统一基元，§6.16.2；当前 Java 8 分支即可做，行为零变化） | — | 低 |
| 2 | 升平台：Java 17 + SB 4.1.2 + `jakarta` + Sa-Token 对应变体，保持 `native` | 0, 0.5 | 中 |
| 3 | 写 `SpringAi2ChatPort`，跑同一套契约测试对齐基线 | 1, 2 | 中 |
| 4 | 切 `provider=spring-ai`，灰度验证 | 3 | 中 |
| 5 | 可选：`VectorStore` 批量 upsert（默认关，实现照做，§6.13） | — | 低 |
| 6 | 可选：query vector 复用（§6.8.8）→ 权重外置 → 再做 `RerankPort`（`rerank-mode` 默认 `weighted_score`，§6.8） | — | 低 |
| 7 | **修 C.1**：`priorContextMessages` 往前找最近一条有日志的记录（跳过子记录/失败记录） | — | 低 |
| 8 | 建 `chat_turn` 表 + `MemoryStore` / `TurnMemoryStore`，替换 `priorContextMessages`（§6.9.7 第 3 步） | 7 | **中**（改 prompt 内容，需双跑对比） |
| 9 | `ContextWindow` + `TokenCounter`，加 token 预算（§6.9.6） | 8 | 中 |
| 10 | `chat_record` 加 `(chat_id, create_time)` 索引，历史查询只取 5 列（C.3） | — | 低 |
| 11 | `IntentPort` + `HybridIntentPort`，实体抽取改结构化输出 + 三条校验（§6.10） | 8 | 中 |
| 12 | `chat_log` 停止写完整 message list + 保留期清理（C.4） | 8 | 低 |
| 13 | `ModelListener` + `ModelHealthPort` + 修 D.1~D.5（默认模型校验状态位、改密钥重置 valid） | — | 中 |
| 13.2 | **模型故障转移**：`ErrorKind` 加 `QUOTA_EXHAUSTED` / `CONTENT_FILTERED`、候选链（多 key → 同供应商 → 全局备用）、首帧 commit point、按错误类型分冷却、`ai_model_key` / `ai_model_health` 两表、探活只写 `valid`（§6.26） | 13 | 中 |
| 13.5 | OTLP 导出接 Langfuse / LangSmith（默认关；SB4 分支用 `spring-boot-starter-opentelemetry`，Java 8 分支自引 OTel SDK） | 13, 2 | 低 |
| 14 | `RERANK_MODEL_TYPE=2` + rerank 探活分支 + 检索配置接口（§6.12.4） | — | 中 |
| 15 | `SemanticMemoryPort` + `Mem0SemanticMemory`（异步，L2；不影响主链路） | 8 | 低 |
| 16 | 页面改造：模型类型 / 字段矩阵 / 检索配置区（§6.12，前端不在本仓库） | 14 | 中 |
| 17 | `Langchain4jChatPort`（可选实现，仅 Java 17 分支；与 `SpringAi2ChatPort` 共用契约测试，§6.13） | 1, 2 | 中 |
| 18 | 可选能力开关矩阵 + 双跑测试（每项单独开关，全关时 SSE 帧与 `native` 基线一致，§6.13） | 13, 17 | 中 |
| 19 | 护栏：`GuardrailPort` + 输入/输出规则 + FROM 表白名单强校验（§6.14.2） | 1 | 中 |
| 20 | 反馈：`chat_feedback` 表 + 👍/👎 接口 + 隐式信号采集（§6.14.3） | 1 | 低 |
| 21 | 评估：`eval_case` 表 + `JdbcEvalRunner` + 5 个指标 + 合并门禁（§6.14.4） | 20 | 中 |
| 22 | `ToolCallingPort` + `ToolPort` + 三种策略（`none` / `native` / `prompted`）；SB4 委托框架循环，Java 8 才最小自研（§6.15.2~6.15.5） | 1 | 中 |
| 23 | MCP client：`mcp_server` 表 + `McpToolProvider`（优先 `aj-mcp-client`）+ 工具名前缀 + 不可信结果包裹（§6.15.6） | 22 | 中 |
| 24 | `WorkflowPort` + `UnsupportedWorkflow`（只留口，不接编排，§6.17.4） | 1 | 低 |
| 25 | 多模态留口：`Message.parts` / `ContentPart` / `ai_model.capabilities`（§6.18，只留口不接图） | 1 | 低 |
| 26 | 语音与导出留口：`SpeechPort`（ASR/TTS）+ `ArtifactPort`（包装现有 `ExcelService`），默认关（§6.18.10） | 1 | 低 |
| 26.5 | **大数据量导出**：`ExportPort` + **接口分页**（cursor 绑 rid + sqlDigest，一页一文件）+ 查询分页（keyset/游标）+ `SXSSFWorkbook` 滑动窗口 + 大文件默认 CSV + `export_task` 异步合并（§6.27） | 26 | 中 |
| 27 | 文件输入：`FileParsePort` + Tika（Java 8 用 2.9.4；Java 17 用 `ApacheTikaDocumentParser`）+ 临时数据源，默认关（§6.23） | 1 | 中 |
| 28 | 对外契约统一：`PublicChatService` 收口 REST + MCP、MCP 改 `isError`、REST 加 `requestId` / `Idempotency-Key` / 限流（§6.24） | 1 | 中 |
| 29 | 嵌入加固：CSP `frame-ancestors` 白名单、token 加 `nonce` + `session_id`、禁止 token 走 URL（§6.25） | 1 | 中 |

**第 0.5 步必须先做**：生态不通，后面全部白做。
**第 2 步与第 3 步必须分开**：平台升级与框架替换是两个变量，混在一起出问题无法定位。
**第 1 步最便宜、收益最大**：把"现状"变成可对比的基线，之后所有实现都有参照物。
**「可选」= 默认关闭，不是可以不做**：第 5、6、13.5、17、19、20 步都是可选能力，但实现与开关必须一起交付（§6.13）。

**第 7~29 步与平台升级解耦**：它们修的是业务缺陷（附录 C、附录 D）或补独立能力，在 Java 8 分支就能做，不必等 SB4。建议与升级并行——先做第 7 步（改动最小、直接修 P0）和第 10 步（加索引，纯 DDL）。

---

## 9. 验收

### 9.1 契约测试（切换的判定标准）

```java
abstract class ChatPortContractTest {

    protected abstract ChatPort port();

    /** 真实端点；契约测试不打桩，否则测的是桩。 */
    protected abstract ModelRef model();

    @Test void 正文增量拼接后与完整文本一致();
    @Test void 思考流与正文同帧返回且不混入正文();
    @Test void usage_三项均非零且_total_等于_in_加_out();
    @Test void 流式结束回调_onComplete();
    @Test void 端点不可达走_onError_且文案非空();
    @Test void 超时走_onError_且不抛异常();
    @Test void 未配置模型走_onError_而不是_NPE();
    @Test void call_失败返回_null_而不是抛异常();
    @Test void call_成功返回全文且与_stream_拼接一致();
    @Test void 取消后不再收到任何回调();
    @Test void extra_body_按协议过滤_OpenAI_嵌套_Anthropic_丢弃();
}

class NativeChatPortContractTest extends ChatPortContractTest { ... }
class SpringAi2ChatPortContractTest extends ChatPortContractTest { ... }
```

期望值来自当前生产行为，不是"两个框架的平均值"。**注意断言的是回调与返回值，不是异常** —— 端口没有异常通道，谁抛异常谁就没对齐基线。

### 9.2 Spike（正式改造前的阻断项）

用真实端点验证 4 件事，任一不通过则调整方案：

1. `ReasoningPart` / `thinkingFieldName` 与目标厂商 `reasoning_content` 的字段映射
2. `getMetadata()` / `onUnmappedRawEvent` 对私有字段的覆盖度
3. `extraBody` 嵌套结构被服务端接受
4. 流式 `getUsage()` 在未开启 `include_usage` 时是否非零

### 9.3 功能回归

| 项 | 判定 |
| --- | --- |
| 4 协议流式 content 帧 | 与现状逐字一致 |
| reasoning 帧 | 内容、顺序、边界一致 |
| usage（input/output/total） | 流式下为 0 视为不通过 |
| `finish_reason` | 一致 |
| 模型切换 | 改 DB 配置后下一次请求生效 |
| 向量召回 | 同一集合、同一向量下 score 与现状一致 |
| 回填 | 批量写入不丢点；重复执行幂等 |
| MCP `/mcp/sse` | 401 与正常流式两条路径均可 |
| 启动 | `/health`、`/api-docs` 可访问，无 `NoClassDefFoundError` |

---

## 10. 风险与回退

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| **第三方生态不支持 SB4** | 整条路径不可行 | 第 0.5 步先探测；不通则退回 SB3 + langchain4j |
| Spring AI 2 仍 RC | API 可能变 | 端口层隔离，变化只影响一个 adapter |
| Flux → 阻塞桥 | 超时/取消/背压语义 | 契约测试覆盖三条 |
| 私有字段覆盖度 | 厂商私有字段丢失 | 契约测试覆盖；不通过则该协议保留手写解析 |
| `javax` → `jakarta` 遗漏 | 启动失败 | 全仓 grep `javax\\.` 清单化 |
| 模型实例缓存膨胀 | 内存 / 连接耗尽 | 按 `(protocol, baseUrl, apiKeyHash)` 缓存 + LRU + 上限 64 |
| 过早抽象 | 死代码 | 只做端口 + native adapter，不抽基类、不写框架 adapter |

**回退**

| 阶段 | 回退 |
| --- | --- |
| 生态探测不通过 | 改走 SB3 + langchain4j 1.23 |
| 端口层改造失败 | 端口是纯增量，回滚分支即可，业务无感 |
| 平台升级失败 | 回 `main`，两条线并行维护 |
| Spring AI adapter 失败 | `provider=native`，回到自研实现 |

---

## 11. 未验证项

以下结论基于源码 / 字节码 / Maven metadata 阅读，**未在真实环境跑通**：

1. Sa-Token / mybatis-plus / x-file-storage / fw-plus 是否存在 SB4 兼容版本
2. Spring AI 2 的 `ChatResponse.getMetadata()` 对厂商私有字段的覆盖度
3. `ReasoningPart` 与目标厂商 `reasoning_content` 的字段映射
4. `OpenAiChatOptions.extraBody` 的嵌套结构与服务端预期是否一致
5. Azure `api_version` / `deployment_name` 在 Spring AI 2 中的 URL 构造
6. 流式 `getUsage()` 在未开启 `include_usage` 时是否为 0
7. SB 2.7 → 4.1 的配置属性迁移清单
8. 厂商 `/rerank` 契约的字段名与分数区间（`results[].index` / `relevance_score` 是按主流实现推的）
9. Spring AI 2 / langchain4j 是否原生提供 rerank 抽象（不影响自研 `RerankPort`，只影响 adapter 行数）
10. `chat.context_record_count` 在生产环境是否被改过（默认 3），决定 C.2 的实际影响
11. 宽表数据源的 m-schema 实际字节数，决定「schema 重复注入」的放大倍数
12. `chat_log` 在生产环境的实际行数与体积，决定 C.4 的清理优先级
13. 生产是否存在「生成阶段失败」高频场景，决定 C.1 的实际影响面
14. mem0 `infer=true` 的抽取质量与耗时随 LLM 变化（本机 42.9s 是 `gpt-oss:120b-cloud` 的结果）
15. mem0 `GET /memories` 的排序规则（实测非插入序，未确认是按 hash 还是 pgvector 返回序）
16. mem0 原文模式下 `metadata` 是否参与向量化（影响检索命中与排序稳定性）
17. **Langfuse：已在本机 v3 真实跑通**（2026-11-02，`localhost:3000`）—— OTLP/HTTP JSON 入库、trace 可见、generation 类型识别、token 映射、`langfuse.session.id` / `user.id` / `trace.name`、错误 → `level=ERROR`，结果见 §6.11.6。**未验证**：Cloud 区域域名（`us.` / `jp.` / `hipaa.`）、大规模批量下的限流与丢弃策略
18. **LangSmith：未验证**（无账号）—— `gen_ai.*` → run 类型映射、token / 成本展示、`Langsmith-Project` 头行为、区域域名。实现已按"同一 exporter 换配置"设计（§6.11.6），只保证发得出去、不阻塞主链路
19. Spring AI 2 默认观测约定能否被 Langfuse 识别为 generation（`spring-ai-model` 内只找到 `gen_ai.client.operation` 一个 `gen_ai.*` 字面量，其余键名运行时拼装；而实测证明 Langfuse 靠 `gen_ai.request.model` 判类型，所以 SB4 分支必须实测或显式补属性）
20. `opentelemetry-sdk` / `opentelemetry-exporter-otlp` 在 Java 8 上的兼容性（若改走自写 JSON 导出器则不存在该风险；SB 4.1.2 starter 传递版本是 1.62.0）
21. langchain4j 是否另有 OTel 导出模块（`-observability` / `-observability-otel` / `-opentelemetry` 三个坐标在 1.23.0 均 404，未穷举其他命名）
22. Langfuse 侧模型价目表未配置时 `costDetails` 为空（本机实测），成本看板要额外配置，不算导出缺陷
23. 评估集从哪来：初期只能人工整理（建议从 `chat_record` 里挑 50~100 条真实问题），规模与代表性都未验证
24. 护栏规则（注入特征、跨空间探测）的误杀率：无生产样本，只能先"只记录不拦截"跑一段时间再开拦截
25. 隐式反馈（重生成 / 重问）与真实满意度的相关性：未验证，先只采集不参与任何判定
26. MCP client 未实测：传输层（stdio / sse / streamable_http）、握手与工具发现、超时与重连行为都只有协议规范，没有跑过真实 server
27. `prompted` 策略（无原生 tools 的模型）解析成功率未测：取决于模型是否稳定输出约定 JSON，需在 vllm / 本地模型上单独评估
28. langchain4j-agentic 是 beta（`1.23.0-beta33`），API 稳定性无承诺；本文按当前字节码设计，升级需重新核对（§6.17）
29. 多模态 token 估算：按 tile 的公式未在目标厂商实测，预算偏差可能超过 30%（§6.18.6）
30. **文档解析已实测**（2026-11-02，JDK 17）：docx / xlsx / pptx / pdf 四种格式 × POI / PDFBox / Tika 三种解析器，结果见 §6.23.2。**未验证**：扫描版 PDF（无文本层）的 `BlankDocumentException` 行为、加密 PDF、超大 xlsx（> 10MB）的解析耗时、`tika-parsers-standard-package` 与项目已有 `tika-core 2.4.1`（经 `x-file-storage`）的运行时冲突
31. Tika 3.3.3 是 Java 11 字节码、langchain4j 解析器是 Java 17 字节码（均实测 major 55 / 61），**Java 8 分支只能用 Tika 2.9.4**；Tika 2.9.4 的解析结果与 3.3.3 是否有差异未对比
32. POI 5.2.3 → 5.5.1 顶升后 `ExcelService` / `ChatExcel` / `ExcelReadSupport` 的回归未跑（Tika 的 microsoft 模块会强制顶升）
33. 模型故障转移只有设计，无实现：402 / 429 / 首帧后断连的真实上游行为、熔断阈值合理性、多实例状态同步延迟都未实测（§6.26.12 是验收项，不是已验证结论）
34. 大数据量导出只有设计：SXSSF 在 100 万行下的真实堆峰值与耗时、`/tmp` 临时文件占用、MySQL `useCursorFetch` 在现有连接池下的行为都未实测（§6.27.10 是验收项）
35. easypoi 版本现状（实测）：五个 artifact 最新均为 4.5.0，`lastUpdated = 2024-02-26`，源码仓库 `jueyue/easypoi` 最后 push 2022-12、无 tag/release；其父 pom 锁 POI 4.1.1

（langchain4j 备选路径的对应项：`onUnmappedRawEvent` 覆盖度、`thinkingFieldName` 默认值、`tokenUsage()` 流式取值。）

---

## 12. 结论

选择 C 路径意味着接受：

- **收益**：Spring AI 2 覆盖 4 协议（含 Responses）、`ReasoningPart` 一等公民、`extraBody` 原生、后续与 Spring 生态同轨
- **代价**：跨两个大版本、框架仍 RC、Flux 桥接、第三方生态未知

**执行前提**：第 0.5 步的生态探测必须先通过。否则 C 路径不成立，应退回 SB3 + langchain4j。

**无论走哪条路径**，端口层（第 6 节）都应该先落地 —— 它是唯一让"换框架"从改代码降级为换配置的东西，且当前 Java 8 分支就能写。

**当前动作**：只出文档，不落码；等 `aimodel-config-flow-revamp` 归档后开新 change 实施。

---
