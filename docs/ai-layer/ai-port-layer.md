# AI 端口层规范（askdata）

> 从 `springboot4-springai2-upgrade.md` 拆出，节号沿用原编号（§6.x），便于交叉引用。
> 升级与实施 → `springboot4-springai2-upgrade.md`；风险清单（附录 A–D） → `risk-register.md`。

---

## 目录

- [6. AI 端口层规划](#6-ai-端口层规划)
  - [6.1 目标](#61-目标)
  - [6.2 分层](#62-分层)
  - [6.3 接口定义（Java 8）](#63-接口定义java-8)
  - [6.4 协议 × 参数处理矩阵](#64-协议--参数处理矩阵)
  - [6.5 NativeChatPort 实现映射](#65-nativechatport-实现映射)
  - [6.6 共享与差异](#66-共享与差异)
  - [6.7 切换机制](#67-切换机制)
  - [6.8 重排模型（RerankPort）：对照 Dify 的取舍](#68-重排模型rerankport对照-dify-的取舍)
  - [6.9 会话存储与上下文窗口（MemoryStore / ContextWindow）](#69-会话存储与上下文窗口memorystore--contextwindow)
  - [6.10 意图识别（IntentPort）](#610-意图识别intentport)
  - [6.11 模型监测（监听器 + 健康度）](#611-模型监测监听器--健康度)
  - [6.12 Rerank 模型的页面改造](#612-rerank-模型的页面改造)
  - [6.13 可选能力：默认关闭，但必须有实现](#613-可选能力默认关闭但必须有实现)
  - [6.14 护栏 / 反馈 / 评估（质量闭环）](#614-护栏--反馈--评估质量闭环)
  - [6.15 工具调用：从固定管线到通用 ToolPort（含 MCP）](#615-工具调用从固定管线到通用-toolport含-mcp)
  - [6.16 通用接口一致性评审](#616-通用接口一致性评审)
  - [6.17 前瞻：将来用 langchain4j-agentic](#617-前瞻将来用-langchain4j-agentic)
  - [6.18 多模态：当前不用，接口必须先留](#618-多模态当前不用接口必须先留)
  - [6.19 通用流程与问数流程的边界：AUTH 是可选能力](#619-通用流程与问数流程的边界auth-是可选能力)
  - [6.20 Pipeline 阶段清单（24 个节点，可扩展）](#620-pipeline-阶段清单24-个节点可扩展)
  - [6.21 抽象模型：7 阶段 + Step + 拦截器（规范）](#621-抽象模型7-阶段--step--拦截器规范)
  - [6.22 文本输入：归一、边界与锚定](#622-文本输入归一边界与锚定)
  - [6.23 文件输入：Office / PDF / CSV](#623-文件输入office--pdf--csv)
  - [6.24 对外契约：REST + MCP](#624-对外契约rest--mcp)
  - [6.25 嵌入方式：iframe / 免登录 token（第三种对外形态）](#625-嵌入方式iframe--免登录-token第三种对外形态)
  - [6.26 模型故障转移：单点、欠费与动态切换](#626-模型故障转移单点欠费与动态切换)
  - [6.27 大数据量导出：接口分页、流式取数与异步合并](#627-大数据量导出接口分页流式取数与异步合并)
  - [6.28 性能与容量：线程模型是当前真正的天花板](#628-性能与容量线程模型是当前真正的天花板)
  - [6.29 安全基线：入口 × 攻击面](#629-安全基线入口--攻击面)
  - [6.30 场景覆盖矩阵：这套流程到底能覆盖多少](#630-场景覆盖矩阵这套流程到底能覆盖多少)
  - [6.31 非问数场景：智能预约（表单收集 + 人工选择 + 写入）](#631-非问数场景智能预约表单收集--人工选择--写入)
  - [6.32 反问与选项：通用交互协议](#632-反问与选项通用交互协议)
  - [6.33 人工确认机制（Human-in-the-loop）](#633-人工确认机制human-in-the-loop)
  - [6.34 Skill：本地与远程能力包](#634-skill本地与远程能力包)
  - [6.35 缓存失效协议：一个 epoch 管所有缓存](#635-缓存失效协议一个-epoch-管所有缓存)
  - [6.36 质量基线：先把"答对"定义清楚](#636-质量基线先把答对定义清楚)
  - [6.37 单次提问的全链路日志：一个 trace 看到底](#637-单次提问的全链路日志一个-trace-看到底)

---

## 6. AI 端口层规划

### 6.1 目标

- 业务层只依赖自研端口，不出现任何框架类型
- 换框架 = 换一个 Spring Bean，业务代码零改动
- 同一套契约测试验证所有实现行为一致

### 6.2 分层

```
业务层（ChatQuestionService / SqlbotMcpCompatController / PermissionEnforcer / ScheduledTaskExecutor）
      │
      ▼
帧层（不动，且不在端口内）
      ChatModelClient.FrameWriter / data:{json} / finish_step / record_id / 图表帧
      │
      ▼
端口层（自研，零框架依赖）
      ChatPort / EmbeddingPort / RerankPort / StreamSink / StreamHandle
      IntentPort / MemoryStore / SemanticMemoryPort / ContextWindow / TokenCounter
      ModelHealthPort / ModelListener
      + 从现有代码搬来的 ModelRef / Message / Usage，新增 Turn / Intent
      │
      ├── NativeChatPort         （现状，基线实现，约 40 行）
      ├── SpringAi2ChatPort      （目标实现，约 80 行）
      ├── Langchain4jChatPort    （§6.13，可选但必须实现；仅 Java 17 分支）
      ├── RuleIntentPort / LlmIntentPort / HybridIntentPort   （§6.10）
      ├── TurnMemoryStore        （§6.9，落地 chat_turn 表）
      ├── Mem0MemoryStore / Mem0SemanticMemory               （§6.9.10，原文兜底 / 语义记忆）
      ├── DbModelHealthStore     （§6.11，健康度与熔断状态）
      └── CompositeModelListener （§6.11.4，扇出：健康度 / 指标 / OTLP）
      │
      ▼
向量层（不动）  VectorStore（已是自研端口） ──► QdrantVectorStore（REST）
```

**铁律**：端口层任何类的 import 不允许出现 `dev.langchain4j` 或 `org.springframework.ai`。

**帧层与端口层是两条线**：`finish_step` / `record_id` / 图表帧 / `error` 帧全部由 `ChatQuestionService` 用 `FrameWriter.write(Map)` 产出，AI 客户端只回调文本增量。端口不需要、也不应该承载帧概念。

---

### 6.3 接口定义（Java 8）

#### 6.3.1 先纠正五个事实错误

初版接口是按"通用框架抽象"想象出来的，和仓库现状有硬冲突：

| 初版写法 | 代码事实 | 后果 |
| --- | --- | --- |
| 新建 `ai.port.ModelRef`（`baseUrl` / `modelName` / `protocol` / `temperature` / `dimension`） | 已有 `ChatModelRouter.ModelRef`，**8 个业务文件在用**；字段多出 `supplier` / `params` / `upstreamFormat` / `usable()` | 两套 DTO 并存；`params`（temperature / top_p / max_tokens / enable_thinking / additional_params 的**唯一载体**）丢失，§2.3 的参数透传直接作废 |
| `protocol: 1 OpenAI / 2 Anthropic / 3 Responses / 4 Azure` | `protocol` 是 **1=openai 兼容 / 2=vllm**；`upstream_format` 才是 **0=auto / 1=Chat Completions / 2=Anthropic / 3=Responses / 4=Azure** | adapter 按错枚举分发，Anthropic / Responses / Azure 分支永远进不去 |
| `stream()` 抛 `AiException`，`StreamSink` 另有 `onError` | `streamCompletion` 返回 `void`，**从不抛异常**，一切错误走 `onError(String)`；9 个调用点无一处 `try/catch` | 两条错误通道；改抛异常会打断 SSE 流，不是"行为零变化" |
| `StreamSink.onText` / `onThinking` 分开 | `onToken(content, reasoning)` **一次回调两个值**，调用方发**一帧** `{type, content, reasoning_content}` | 拆开 → 每段内容变两帧 → SQLBot 帧序变化，违反"逐字保持" |
| `Capability.RAW_EVENT_PASSTHROUGH` + `onRaw(JsonNode)` | AI 客户端只回调 token / complete / error / usage，**没有 raw 事件生产者**；帧类型是 `Map<String,Object>` 不是 `JsonNode` | 无生产者的接口，adapter 永远返回空实现 |

#### 6.3.2 三个 DTO 不新建，直接搬

端口要用 `ModelRef` / `Message` / `Usage`，而这三个类型已经存在。正确做法是**搬到端口包**，不是再定义一套：

| 现位置 | 搬到 | 现有使用面 |
| --- | --- | --- |
| `ChatModelRouter.ModelRef` | `ai.port.ModelRef` | 8 个文件 |
| `ChatModelClient.ModelMessage` | `ai.port.Message` | 6 个文件 + client |
| `ChatModelClient.Usage` | `ai.port.Usage` | `ChatQuestionService` |

搬迁是机械改 import，字段名与语义**一个字不改**（尤其 `params`、`upstreamFormat`、`supplier`）。搬完 `ChatModelRouter` 返回端口类型，业务层不再依赖 `chat.service` 的 DTO。

#### 6.3.3 端口定义

包名 `com.xiongx9527.askdata.ai.port`。

```java
public interface ChatPort {

    /** 阻塞到流结束（与现有 streamCompletion 一致）；返回取消句柄。 */
    StreamHandle stream(ModelRef model, List<Message> messages, StreamSink sink);

    /** 失败返回 null（与现有 generate 一致）。 */
    default String call(ModelRef model, List<Message> messages) {
        return call(model, messages, null);
    }

    /** 需要 usage 明细时用这个；usage 为 null 表示不收集。 */
    default String call(ModelRef model, List<Message> messages, final Usage usage) {
        final StringBuilder text = new StringBuilder();
        final String[] failure = new String[1];
        stream(model, messages, new StreamSink() {
            @Override
            public void onDelta(String content, String reasoning) {
                if (content != null) {
                    text.append(content);
                }
            }

            @Override
            public void onError(String message) {
                failure[0] = message;
            }

            @Override
            public void onUsage(long input, long output, long total) {
                if (usage != null) {
                    usage.input = input;
                    usage.output = output;
                    usage.total = total;
                }
            }
        });
        return failure[0] != null || text.length() == 0 ? null : text.toString();
    }
}
```

```java
public interface StreamSink {

    /** 与现状 onToken(content, reasoning) 一一对应：一次回调发一帧，不能拆成两个方法。 */
    void onDelta(String content, String reasoning);

    /** 错误文案直接进 error 帧，保持原文，不做包装。 */
    void onError(String message);

    default void onUsage(long input, long output, long total) {
    }

    default void onComplete() {
    }
}
```

```java
public interface StreamHandle {

    /** 现状无取消能力，默认实现留空；Spring AI adapter 用 Disposable 真实现。 */
    void cancel();

    StreamHandle NONE = new StreamHandle() {
        @Override
        public void cancel() {
        }
    };
}
```

```java
public interface EmbeddingPort {

    /** 失败返回 null（HTTP 错误 / 异常 / 条数与入参不符）；成功时 size == texts.size()。 */
    List<double[]> embed(ModelRef model, List<String> texts);
}
```

四个类型、两个接口，没有基类。

#### 6.3.4 删掉的四个抽象

| 删 | 理由 |
| --- | --- |
| `ChatCommand` | 9 个调用点全是 `aiModel.params` 原样传，**没有一处做 per-request 覆盖**；`temperature` 在 `ModelRef` 与 `ChatCommand` 各一份，语义重复。真出现按次覆盖时再加 |
| `Capability` / `CapabilityLevel` / `supports()` | `TOOL_CALLING` 无法实现（`Message` 没有 `toolCallId` / `toolCalls`）；`RESPONSES_API` 与 `upstreamFormat` 重复；**没有任何消费者按它分支**。降级判断直接用 `upstreamFormat` 分支即可 |
| `ChatResult` | `generate` 的现有契约是「失败返回 null 的 `String`」，5 个调用点里 4 个只要 `String`；包一层 `ChatResult` 只是把 null 检查换个位置 |
| `AiException`（在 stream 路径上） | 五分类 code（`endpoint_unreachable` / `unauthorized` / `timeout` / `protocol_error` / `cancelled`）当前代码一个都不产出；`NativeChatPort` 只能靠解析中文文案猜 code。保留回调单通道，需要 code 时再让实现逐步产出 |

> 判断标准：**能力枚举只放有消费者分支的项**。当前消费者数为 0，所以整组删掉；等真出现"某协议要降级"的分支，再把那一项加回来。

#### 6.3.5 Java 8 约束

| 不能用 | 用 | 说明 |
| --- | --- | --- |
| 接口私有方法 | `abstract class` 或静态工具类 | Java 9 才有。这是 `AbstractChatPort` 曾经唯一的理由；当前两个实现共享逻辑很少，不需要 |
| `List.of` / `Map.of` | `Collections.unmodifiableList(Arrays.asList(...))` | |
| `record` | 普通 `final` 类 + 全参构造 | |
| `var` / switch 表达式 | 显式类型 / 传统 `switch` | |
| — | `StreamHandle NONE = () -> { };` | Java 8 函数式接口可用，比匿名类短 |

---

### 6.4 协议 × 参数处理矩阵

删掉 `Capability` 后，"哪些能力降级"由 `upstreamFormat` 直接决定。这张表是**实现必须逐格对齐的现状行为**，也是 adapter 差异的全部来源：

| 项 | OpenAI 兼容 | Anthropic Messages | Responses | Azure |
| --- | --- | --- | --- | --- |
| 正文增量 | `choices[].delta.content` | `content_block_delta` | `response.output_text.delta` | 同 OpenAI |
| 思考流 | `delta.reasoning_content` | thinking block | reasoning summary | 同 OpenAI |
| usage | `stream_options.include_usage` | `message_start` / `message_delta` | `response.completed` | 同 OpenAI |
| `extra_body` 配置 | 合并进嵌套 `extra_body` | **丢弃** | **丢弃** | 合并进嵌套 `extra_body` |
| `enable_thinking` 配置 | 强制进 `extra_body` | **丢弃** | **顶层保留** | 强制进 `extra_body` |
| `api_version` / `deployment_name` | 顶层（非 Azure 时） | 丢弃 | 丢弃 | 不进 payload，拼进 URL |
| 鉴权 | `Authorization: Bearer` | `x-api-key` | `Authorization: Bearer` | `api-key` 头 |

**结论：参数归一化是协议私有逻辑，不能上提到共享层。** 三种协议对同一份 `params` 的过滤规则各不相同（Anthropic 丢 `enable_thinking`，Responses 却保留），上提就会改变现有行为。

---

### 6.5 NativeChatPort 实现映射

不重写任何解析逻辑，只做类型转换：

```java
public final class NativeChatPort implements ChatPort, EmbeddingPort {

    private final ChatModelClient delegate;

    public NativeChatPort(ChatModelClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public StreamHandle stream(ModelRef model, List<Message> messages, final StreamSink sink) {
        delegate.streamCompletion(model.apiDomain, model.apiKey, model.baseModel, messages,
                model.params, model.protocol, model.upstreamFormat,
                new ChatModelClient.TokenHandler() {
                    @Override
                    public void onToken(String content, String reasoning) {
                        sink.onDelta(content, reasoning);
                    }

                    @Override
                    public void onComplete() {
                        sink.onComplete();
                    }

                    @Override
                    public void onError(String message) {
                        sink.onError(message);
                    }

                    @Override
                    public void onUsageDetail(long input, long output, long total) {
                        sink.onUsage(input, output, total);
                    }
                });
        return StreamHandle.NONE;   // 现状无取消能力，行为不变
    }

    @Override
    public List<double[]> embed(ModelRef model, List<String> texts) {
        return delegate.embed(model.apiDomain, model.apiKey, model.baseModel, texts);
    }
}
```

**调用面（9 处，不是 6 处）**：

| 方法 | 处数 | 位置 |
| --- | --- | --- |
| `call()` | 5 | `ChatKnowledgeService` / `ChatQuestionService`×2 / `PermissionEnforcer` / `ScheduledTaskExecutor` |
| `stream()` | 2 | `ChatQuestionService`（主链路）/ `AiModelService`（连通性探测） |
| `embed()` | 2 | `AiModelService`（连通性探测，顺带回写维度）/ `EmbeddingService` |

其中 2 处是**配置期连通性探测**，不是业务主链路；它们传入的是尚未入库的候选配置，构造一个临时 `ModelRef` 即可。

---

### 6.6 共享与差异

| 逻辑 | 归属 | 说明 |
| --- | --- | --- |
| 模型实例缓存（`(protocol, baseUrl, apiKeyHash)` → 实例） | 共享 | 与协议无关，放独立 `ChatModelCache` |
| `call()` 的实现（收集全文 / 取最后一次 usage / 失败返回 null） | 共享 | 已在 `ChatPort` 里写成 `default`，无需基类 |
| 超时、异常归一 | 共享 | 但**归一后仍走 `onError(String)`**，不抛 |
| 请求构造（含 `params` 归一化） | **差异** | 见 §6.4，三种协议过滤规则不同 |
| SSE 解析 / 思考提取 / usage 提取 | **差异** | |
| 取消 | 差异 | `StreamHandle` 各自实现 |

**什么时候才抽基类**：第二个实现真正跑通契约测试之后。当前只有 `NativeChatPort`，抽基类就是给不存在的实现写死代码。初版文档里的 `AbstractChatPort`（含 `capabilities()` / `cancel(Object)`）已被移除。

---

### 6.7 切换机制

```yaml
askdata:
  ai:
    provider: native    # native | spring-ai | langchain4j
```

```java
@Bean
@ConditionalOnProperty(name = "askdata.ai.provider", havingValue = "native", matchIfMissing = true)
public ChatPort nativeChatPort(ChatModelClient client) { return new NativeChatPort(client); }

@Bean
@ConditionalOnProperty(name = "askdata.ai.provider", havingValue = "spring-ai")
public ChatPort springAiChatPort(...) { return new SpringAi2ChatPort(...); }
```

**切换粒度是整体，不是按协议** —— 按协议切换会让行为不可预测，契约测试也会组合爆炸。

默认值 `native`：零依赖、Java 8 可跑，是唯一不会因平台升级而失效的实现。

---

### 6.8 重排模型（RerankPort）：对照 Dify 的取舍

`ai_model.model_type=2` 已经为 Rerank 预留（`AiModelService.java:41` 注释"本期不开放"）。对照 Dify 源码后结论不变——**继续不开**——但设计要改三处。

#### 6.8.1 Dify 的实际做法

| 机制 | Dify 源码 | 行为 |
| --- | --- | --- |
| 检索模式 | `RetrievalMethod` | `semantic_search` / `full_text_search` / `hybrid_search` / `keyword_search`，按知识库配置切换 |
| 重排模式 | `RerankMode` | `reranking_model` / `weighted_score`，**二选一，不叠加** |
| 分派 | `RerankRunnerFactory.create_rerank_runner(mode)` | `reranking_model` → `RerankModelRunner`；`weighted_score` → `WeightRerankRunner` |
| 加权融合 | `WeightRerankRunner.run` | `score = vector_weight × cosine + keyword_weight × jieba_overlap`，阈值过滤 → 覆盖 `metadata["score"]` → 降序 → `[:top_n]` |
| 模型重排 | `RerankModelRunner.run` | 调模型 → 按 `score_threshold` 过滤 → `metadata["score"] = rerank_score`（**覆盖原分**）→ 降序 → `[:top_n]` |
| 两段截断 | `retrieval_service._retrieve` | 召回各取 `top_k` → hybrid 合并（最多 2×`top_k`）→ `DataPostProcessor.invoke(top_n=top_k)` |
| 阈值兜底 | 同上 | 无 rerank runner 且有阈值时，回落到按存储分过滤 |
| 位置重排 | `ReorderRunner` | `docs[::2] + docs[1::2][::-1]` —— 高分放两端，对抗 lost in the middle |

#### 6.8.2 可以直接照搬的三点

| 点 | 原方案 | 改成 |
| --- | --- | --- |
| **模式互斥** | `rerank.enabled=true` 叠在 0.4/0.6 加权分之上 | `RerankMode` 三态互斥。Dify 里没有"加权分 + 模型分"同时参与排序的路径，而且叠加会打翻 `+10` 的钉死项 |
| **权重可配** | `VECTOR_WEIGHT=0.4` / `MENTION_WEIGHT=0.6` 硬编码 | 外置为配置，默认值不变（Dify 的 `Weights{vector_setting, keyword_setting}` 就是可配的） |
| **重排后覆盖分数** | 未定义 | `RERANKING_MODEL` 模式下终态分 = 重排分，加权分只留 metadata 供调试；阈值按终态分过滤 |

#### 6.8.3 不能照搬的两点

**1. Dify 的 `WeightRerankRunner` 重算两个分，我们不能重算**

Dify 用 `jieba` 重算关键词分、用 `CacheEmbedding` 重算余弦——因为它的检索可能跨多个知识库、多个索引。askdata 在 `rankTables` 里**两个分都已在手**（`mentionScores` / `keywordScores` 的五档权重 + Qdrant 相似度）。再引入一套 jieba 分词等于并存两个关键词打分器，与现有权重冲突。

**2. Dify 没有"用户显式点名标识符"信号，我们有**

`EXPLICIT_MENTION_BONUS = 10D`（`TableEmbeddingService.java:91`）让引号点名的表几乎必进 top10。在 `RERANKING_MODEL` 模式下，cross-encoder 只看 `question ↔ 表文档` 语义，很可能把它排下去 —— 这是净损失。

因此 askdata 的必要偏离：**显式提及项不送重排，直接置顶；rerank 只重排其余候选。**

#### 6.8.4 改后的设计

```java
public enum RerankMode {
    NONE,               // 纯词法/提及，等价于向量不可用时的降级
    WEIGHTED_SCORE,     // 现状：0.4×向量 + 0.6×max(提及, 关键词) + 显式提及 10
    RERANKING_MODEL     // 重排模型出终态分，加权分退为 metadata
}
```

```yaml
askdata:
  vector:
    recall-threshold: 0.4          # 已有
    vector-weight: 0.4             # 新增，默认值不变
    mention-weight: 0.6            # 新增，默认值不变
    rerank-mode: weighted_score    # NONE | weighted_score | reranking_model
```

默认 `weighted_score` = 当前行为，逐位不变。

#### 6.8.5 端口定义

```java
public interface RerankPort {

    /**
     * 与 documents 等长、一一对应的相关性分数（越大越相关）。
     * 失败返回 null，调用方回落到 WEIGHTED_SCORE，不降级为"只保留重排结果"。
     */
    List<Double> rerank(ModelRef model, String query, List<String> documents);
}
```

厂商契约按 `POST {baseUrl}/rerank`、请求 `{model, query, documents}`、响应 `results[].index / relevance_score` 设计（与 embedding 一样裸 HTTP，不依赖框架）。`NativeRerankPort` 约 60 行：一次 HTTP + 按下标回填 + 任何异常返回 null。

#### 6.8.6 接入点：只改 `rankTables`

```java
switch (rerankMode) {
    case NONE:
        score = Math.max(mention, keyword);
        break;
    case WEIGHTED_SCORE:
        score = hybrid(Math.max(mention, keyword), vector);      // 现状
        break;
    case RERANKING_MODEL:
        // 1. 显式提及命中的表直接置顶，不送重排
        // 2. 其余候选按 tableDoc 拼装送 rerank
        // 3. 返回 null → 整批回落到 WEIGHTED_SCORE
        break;
}
```

四条铁律（其余三条沿用）：

1. **只在最终截断前调一次**，不在四条召回链路里各加一次
2. **显式提及项不参与重排**
3. **候选数 ≤ limit 时跳过**，不花这次调用
4. **失败整批回落** `WEIGHTED_SCORE`，不做部分重排

#### 6.8.7 为什么仍然默认不开

| 理由 | 依据 |
| --- | --- |
| 候选集是业务硬过滤后的小集合 | 单数据源 `checked=TRUE` 的表，最终 `MAX_TABLES = 10`；术语/SQL 示例各 5 条 |
| 精排已经用业务规则写死 | 表名 1.0 / 注释 0.9 / 表名子串 0.7 / 字段注释 0.5 / 字段名 0.3，加权 0.4/0.6 |
| 显式提及会被 rerank 打翻 | `EXPLICIT_MENTION_BONUS = 10D`；需额外做置顶偏离才能保住 |
| 多一个必须可降级的依赖 | 现有每条链路都是"向量挂了退词法"；rerank 挂了要整批回落 |

Dify 之所以必须提供 rerank，是因为它的文档是无结构长文本、候选可达上万、没有业务硬过滤；askdata 三者都不成立。

#### 6.8.8 真正该先做的：query vector 复用

同一个问题现在至少算 **4 次** 相同文本的 embedding（术语 / SQL 示例 / 数据源 / 表级各一次），`EmbeddingService.embed()` 无任何缓存（`EmbeddingService.java:102`）。加一个按 `(模型 id, 文本)` 的短 TTL 缓存，省下的延迟比 rerank 花掉的更多，且零风险。

#### 6.8.9 什么时候才值得开

1. 单数据源勾选表 > 100，且 `MAX_TABLES=10` 截断处人工抽检有明显漏选
2. 出现"同义改写"线上 case：用户问法与表注释语义一致，但**零词法命中**，向量是唯一信号
3. 术语召回精度不够：术语错配会直接改写 SQL 语义

#### 6.8.10 验收

| 项 | 判定 |
| --- | --- |
| `rerank-mode=weighted_score` | 排序结果与现状**逐位一致** |
| `rerank-mode=reranking_model` 但模型未配置 | 回落加权分，结果不变，无错误日志 |
| `rerank-mode=reranking_model` + 模型可用 | 显式提及项**位置不变**（硬断言） |
| 模型超时 / 5xx | 整批回落，请求不失败 |
| 延迟 | 单次重排 P99 < 300ms，否则跳过 |

#### 6.8.11 不做的事

| 不做 | 理由 |
| --- | --- |
| `ReorderRunner`（高分放两端） | 只 10 张表，lost-in-the-middle 影响可忽略 |
| RRF 替代加权 | 现有 0.4/0.6 是有意的业务权重，换 RRF 属于改变行为 |
| jieba 重算关键词分 | 与现有五档权重冲突，等于并存两个打分器 |
| rerank 结果缓存 | 查询各不相同，命中率接近 0 |
| 重排后的二次阈值 | 分数分布因厂商而异，跨模型不可比 |

---

### 6.9 会话存储与上下文窗口（MemoryStore / ContextWindow）

#### 6.9.1 现状：两条历史通道，语义不一致

| 通道 | 方法 | 数据来源 | 单位 | 消费方 |
| --- | --- | --- | --- | --- |
| 文本通道 | `priorContext(chatId, excludeId)` | `chat_record` 的 `question` / `sql` | **记录数**（`chat.context_record_count`，默认 3） | 知识增强（实体抽取 / 术语召回） |
| 消息通道 | `priorContextMessages(chatId, excludeId)` | 上一条记录的 `chat_log.message`（完整 model messages） | **user 消息数**（同一配置） | SQL 生成 |

同一个问题，两条通道给出的历史不一样：文本通道带 `Q/A(SQL)` 结构，消息通道带原始 user/assistant 序列。这本身就是漂移源——调优时改了配置，两边不同步变。

#### 6.9.2 关键缺陷：按「消息」计数，而不是按「轮次」计数

`buildSqlMessageSeq`（`SchemaContextBuilder.java:385-419`）里，规则、schema、术语、样例的 ack 块**全部是 `role=user`**：

```java
messages.add(new ModelMessage("user", rules));                        // ← user
messages.add(new ModelMessage("user", schemaUser));                   // ← user
messages.add(new ModelMessage("user", "【业务术语】\n" + terminologyText)); // ← user
messages.add(new ModelMessage("user", sb.toString()));                // ← SQL 样例，user
messages.add(new ModelMessage("user", "【用户问题】\n" + question));     // ← 真正的提问
```

而 `priorContextMessages`（`ChatQuestionService.java:1073-1082`）是「从倒数第 N 个 user 消息起截取」：

```java
int start = humanIdx.size() <= roundCount ? humanIdx.get(0) : humanIdx.get(humanIdx.size() - roundCount);
List<ModelMessage> history = new ArrayList<>(filtered.subList(start, filtered.size()));
```

于是 ack 块被当成轮次。按同一算法复刻推演（Python 复刻切片逻辑，非线上实测）：

| 场景 | 第 2 轮历史 | 第 3 轮历史 | 第 4 轮起 |
| --- | --- | --- | --- |
| 无术语/样例（只有 rules + schema） | 含 **RULES + SCHEMA**，真实轮次 **1** | 含 **SCHEMA**，真实轮次 **2** | 3 轮，正常 |
| 有术语 + 样例 | 3 条 user 全是 ack 块 + 1 问 | 收敛 | 正常 |

两个后果：

1. **m-schema 被重复注入 1~2 次**。宽表数据源的 schema 动辄 10~50KB，第 2 轮 prompt 直接翻倍
2. **实际轮次少于配置值**。配置写 3 轮，第 2 轮只给了 1 轮真实上下文——追问「那上个月呢」时模型看不到上一轮

> 根因不是切片写错了，而是**持久化了不该持久化的东西**：ack 块是每轮重新构建的装配件，却被写进 `chat_log.message` 并在下一轮回读。

#### 6.9.3 第二个缺陷：只认「紧邻的上一条记录」

```java
// ChatQuestionService.java:1044-1057
for (int i = records.size() - 1; i >= 0; i--) {
    long rid = Rows.lng(records.get(i), "id", 0L);
    if (rid != excludeRecordId) { prevRecordId = rid; break; }   // ← 只取最近一条
}
Map<String, Object> parsed = chatRepository.lastLogMessage(prevRecordId, OPERATE_GENERATE_SQL);
if (parsed == null) {
    return Collections.emptyList();                              // ← 没有日志 = 历史全丢
}
```

`listRecordsByChat` 返回的是该 `chat_id` 下**全部** `chat_record`，包含：

| 记录类型 | 有 GENERATE_SQL 日志吗 | 成为 `prevRecordId` 的后果 |
| --- | --- | --- |
| 正常问答 | 有 | 正常 |
| 生成阶段就失败的记录 | **无** | **整段多轮上下文清空** |
| 分析子记录（`insertSubRecord`，同 `chat_id`） | **无** | **整段多轮上下文清空** |
| 预测子记录 | **无** | 同上 |
| `first_chat` 空记录 | 无 | 只影响首问，无害 |

也就是说：**用户点一次「分析」或「预测」，下一个追问就丢失全部多轮上下文**。这不是边缘场景，是产品主路径。

#### 6.9.4 通用接口：以「轮次」为单位持久化

结论：**存轮次，不存消息序列**。schema / rules / 术语 / 样例在组装时投影，永不落库。

```java
package com.xiongx9527.askdata.ai.port;

/** 一轮问答的业务事实，不含任何装配件（schema/规则/术语/样例）。 */
public final class Turn {

    public final long recordId;
    public final String question;
    public final String sql;       // 可为空（未生成）
    public final String error;     // 可为空（执行成功）
    public final long createTime;

    public Turn(long recordId, String question, String sql, String error, long createTime) {
        this.recordId = recordId;
        this.question = question;
        this.sql = sql;
        this.error = error;
        this.createTime = createTime;
    }
}
```

```java
public interface MemoryStore {

    /** 按时间正序返回最近 limit 轮（不含装配件）；无历史返回空列表，不返回 null。 */
    List<Turn> recent(String conversationId, int limit);

    /** 追加一轮。重复 recordId 覆盖，不抛。 */
    void append(String conversationId, Turn turn);

    /** 删除整个会话的全部轮次（硬删，供会话删除与保留期清理复用）。 */
    void delete(String conversationId);
}
```

```java
public interface ContextWindow {

    /**
     * 在 budget 内把轮次投影成消息序列。
     * 规则：保 system；从最新轮往前收；整轮丢弃，不切断半轮；放不下最小集返回 null（调用方报错，不静默截断）。
     */
    List<Message> project(List<Message> head, List<Turn> turns, Message tail, int budget);
}
```

```java
public interface TokenCounter {

    int count(String text);

    /** 无 tokenizer 时的估算：CJK 按 1 字 1 token，ASCII 按 4 字符 1 token。 */
    TokenCounter ESTIMATE = new TokenCounter() {
        @Override
        public int count(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            int cjk = 0;
            int ascii = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) < 128) {
                    ascii++;
                } else {
                    cjk++;
                }
            }
            return cjk + (ascii + 3) / 4;
        }
    };
}
```

三个接口、一个值对象，零框架依赖。

#### 6.9.5 与框架 Memory 的映射（已用 javap 核对）

| 框架 | 接口 | 方法 | 适配方式 |
| --- | --- | --- | --- |
| langchain4j 1.x | `dev.langchain4j.store.memory.chat.ChatMemoryStore` | `getMessages(Object)` / `updateMessages(Object, List<ChatMessage>)` / `deleteMessages(Object)` | 委托 `MemoryStore`，`Turn` ↔ `UserMessage`/`AiMessage` 互转 |
| Spring AI 1.x | `org.springframework.ai.chat.memory.ChatMemoryRepository` | `findByConversationId(String)` / `saveAll(String, List<Message>)` / `deleteByConversationId(String)` | 同上 |

两个框架的 store 抽象都只有「按会话 id 读写消息列表」，**没有轮次概念**。所以：

- **端口必须自己定义轮次模型**，不能直接把框架接口当端口——否则 6.9.2 的缺陷会被原样继承
- 适配器只做形状转换，约 40 行；业务侧始终面向 `Turn`

窗口能力两框架都提供，但都不够：

| 框架 | 能力 | 缺口 |
| --- | --- | --- |
| langchain4j | `MessageWindowChatMemory`（`maxMessages`）、`TokenWindowChatMemory`（`maxTokens(Integer, TokenCountEstimator)`、`alwaysKeepSystemMessageFirst`） | 按**消息**计数，ack 块照样占额度 |
| Spring AI | `MessageWindowChatMemory`（`maxMessages`）、`JTokkitTokenCountEstimator` | 同上 |

结论：**窗口由自研 `ContextWindow` 负责，不交给框架**。框架的 memory 只当存储适配层用。

#### 6.9.6 预算怎么算

```
budget = modelContextWindow
       - maxOutputTokens
       - systemTokens
       - 本轮 schema / 术语 / 样例 tokens
       - 安全余量（10%）
```

`modelContextWindow` 和 `maxOutputTokens` 来自 `ai_model.config`（已有字段）；缺失时用保守默认（context 8K、output 2K）。

三种失败语义必须区分：

| 情况 | 行为 |
| --- | --- |
| 历史超预算 | 丢最旧轮次，保留最新 |
| 单轮就超预算 | 该轮只保留 question，丢弃 SQL（SQL 通常远大于 question） |
| system + 本轮 schema 已超预算 | 返回 null → 明确报错「表结构过大」，**不静默截断 schema** |

#### 6.9.7 迁移顺序

| 步 | 动作 | 行为变化 |
| --- | --- | --- |
| 1 | 建 `chat_turn` 表（`conversation_id, record_id, question, sql, error, create_time`）+ 唯一键 `(conversation_id, record_id)` | 无 |
| 2 | 回填：从 `chat_record` 一次性灌入历史轮次 | 无 |
| 3 | 引入 `MemoryStore` + `TurnMemoryStore`，`priorContextMessages` 改为读它 | **有**：修掉 6.9.2 / 6.9.3，前两轮不再重复注入 schema |
| 4 | 引入 `ContextWindow` + `TokenCounter`，加 token 预算 | **有**：长历史开始被裁 |
| 5 | `chat_log.message` 停止写完整 message list，只写本轮 prompt 摘要 | 存储下降 |
| 6 | 加保留期清理（对齐 `ChartImageService` 的 `@Scheduled` + 分布式锁写法） | 存储可控 |

第 3 步是关键，也是唯一需要人工对比 prompt 的一步——建议先双跑（新旧历史各生成一次 SQL，比对结果），再切。

#### 6.9.8 验收

| 项 | 判定 |
| --- | --- |
| 连续 3 轮追问 | 每轮 history 中 `【数据库表结构】` 出现 **0 次**（schema 只在本轮 head 出现） |
| 连续 3 轮追问 | 第 2 轮 history 含 **2** 轮真实 Q/A，第 3 轮含 **3** 轮 |
| 中间插入一次分析子记录 | 下一轮 history **不为空** |
| 中间一轮生成失败 | 下一轮 history **不为空**（跳过无日志记录往前找） |
| 历史超预算 | 丢最旧轮次，最新轮完整保留 |
| schema 单项超预算 | 明确报错，不静默截断 |
| 并发同会话两问 | 两问的 history 都基于同一快照，不出现半轮 |

#### 6.9.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 会话摘要压缩（summary memory） | 候选集只有 3 轮、且都是结构化 Q/A，压缩收益小于信息损失 |
| 向量化历史检索 | 同一会话内轮次少，全量注入即可 |
| 把 `chat_log.message` 改造成历史主存储 | 日志是排障用的，语义上不该承载业务状态 |
| 复用框架的 `MessageWindowChatMemory` 做窗口 | 按消息计数，ack 块占额度的问题原样存在 |

---

#### 6.9.10 会话存储改用 mem0：本机实测与落地方案

本机已跑通 mem0（Docker，`mem0-dev-mem0-1`，`localhost:8888`），配置为 **pgvector + bge-m3（1024 维，TEI 9528）+ Ollama 兼容 LLM**。以下结论全部来自实测，不是文档推测。

##### 实测结果

| 项 | 结果 |
| --- | --- |
| `POST /memories`，`infer=false`（原文模式） | **177ms**（含 1 次 bge-m3 embedding） |
| `POST /memories`，`infer=true`（抽取模式） | **42.9s**（同步阻塞，等 LLM 抽完才返回） |
| `infer=true` 的输出 | 原文被**改写并翻译成英文**：`User's name is 张三 and they are responsible for the 华东区 (East China region).`；1 条消息裂成 2 条记忆 |
| `GET /memories?run_id=...` 顺序 | **非插入序**。写入序 `u1,a1,u2,a2,u3,a3`，返回序 `u1,u2,a1,a2,a3` |
| `POST /search` 顺序 | 按相关度（0.816 / 0.812 / …），非时间 |
| `metadata` 往返 | 正常，`seq` / `record_id` 原样返回 |
| `role` | 保留为顶层字段（不在 `metadata` 里） |
| 相同内容 | 3 条完全相同的 SQL，`hash` 一致，仍存 3 行——**不去重** |
| `DELETE /memories?run_id=...` | 可用，实测清空成功 |

三个直接结论：

1. **`infer=true` 不能用于问数主链路**。它把中文 SQL 上下文改写成英文事实，原文丢失——SQL 生成依赖精确的表名、列名、字面量，改写即失效。而且同步阻塞 43 秒，一次追问的延迟比整条问答链路还长。
2. **`infer=false` 可用，但顺序不可依赖**。返回顺序与写入顺序无关，必须自己按 `metadata.seq` 或 `created_at` 排序。
3. **embedder 与 askdata 恰好同源**（都是 9528 上的 bge-m3、1024 维）。这是本机巧合，不是设计保证——mem0 的 embedder 是它自己的配置，askdata 换模型时 mem0 不会跟着变。

##### 落地方案：三层，逐层可关

| 层 | 端口 | 实现 | 用途 | 依赖 |
| --- | --- | --- | --- | --- |
| L1 精确轮次 | `MemoryStore` | `TurnMemoryStore`（MySQL `chat_turn`） | 主链路上下文窗口 | 无 |
| L2 语义记忆 | `SemanticMemoryPort` | `Mem0SemanticMemory`（`infer=true`） | 跨会话偏好 / 业务口径 | mem0 |
| L3 原文兜底 | `MemoryStore` | `Mem0MemoryStore`（`infer=false`） | L1 不可用时降级 | mem0 |

L1 是主存储，L2 是增强，L3 是兜底。三层实现同一组端口，切换只改装配。

```java
package com.xiongx9527.askdata.ai.port;

/** 语义记忆作用域；oid 映射 mem0 的 user_id，不是用户 id。 */
public final class MemoryScope {

    public final long oid;
    public final long userId;
    public final Long appId;

    public MemoryScope(long oid, long userId, Long appId) {
        this.oid = oid;
        this.userId = userId;
        this.appId = appId;
    }
}
```

```java
public interface SemanticMemoryPort {

    /** 按相关度返回记忆文本；失败返回空列表，不抛。 */
    List<String> recall(MemoryScope scope, String query, int limit);

    /** 写入一段对话供抽取；失败只记日志，不影响主链路。 */
    void remember(MemoryScope scope, List<Message> messages, Map<String, Object> metadata);

    /** 删除该作用域全部记忆（会话删除 / 工作空间清理）。 */
    void forget(MemoryScope scope);
}
```

##### 端口 → mem0 映射（实测可用）

| 端口方法 | mem0 | 实测要点 |
| --- | --- | --- |
| `remember`（L2） | `POST /memories` | `user_id=oid`、`run_id=chatId`、`agent_id=appId`、`infer=true`；**同步阻塞 ~43s**，必须放异步线程池 |
| `remember`（L3） | `POST /memories` | `infer=false`、`metadata={record_id, seq, record_type, sql, error}`；~180ms |
| `recall` | `POST /search` | `filters{user_id, agent_id, run_id}`、`top_k`、`threshold` |
| `forget` | `DELETE /memories?user_id=|run_id=` | 实测可用，需 admin 角色 |

`MemoryStore` 的 L3 实现：

| 端口方法 | mem0 | 必须自己补的 |
| --- | --- | --- |
| `append` | `POST /memories`（`infer=false`） | 一轮拆两条（user/assistant），共享同一 `metadata.seq` |
| `recent` | `GET /memories?run_id=...&top_k=...` | **按 `metadata.seq` 降序自排序后取前 N**，再按 `seq` 升序还原轮次 |
| `delete` | `DELETE /memories?run_id=...` | 无 |

**命名空间铁律**：`user_id` 必须映射**工作空间 `oid`**。映射成用户 id 会让同工作空间的多个用户互相看不到历史；映射成常量则跨租户串味。

##### 全量走 mem0 必须补的六项

| # | 缺口 | 实测依据 | 补法 |
| --- | --- | --- | --- |
| 1 | 顺序无保证 | `GET` 返回 `u1,u2,a1,a2,a3` | `metadata.seq` 自排序 |
| 2 | 一轮拆成多行 | 一条消息一行，不是一轮一行 | 用 `metadata.seq` + `record_id` 聚合还原 |
| 3 | 子记录混入 | — | `metadata.record_type` 标 `base/analysis/predict`，读取时过滤（顺带修掉 C.1） |
| 4 | embedder 需对齐 | 本机恰好同为 bge-m3/1024 | 配置项显式声明，askdata 换 embedding 模型时同步检查 mem0 |
| 5 | 删除级联 | `DELETE?run_id` 可用 | `deleteChat` 必须调 `forget`，失败重试 + 补偿表 |
| 6 | 降级 | — | mem0 不可用回 `TurnMemoryStore`，不能报错；纳入 §6.11 健康检查 |

##### 成本对照（实测值）

| 项 | MySQL 轮次（L1） | mem0 原文（L3） | mem0 抽取（L2） |
| --- | --- | --- | --- |
| 写延迟 | ~1ms（本机库） | **177ms** | **42.9s** |
| 读延迟 | ~1ms | 1 次 HTTP + 1 次 embedding | 同左 |
| 新增部署 | 无 | mem0 server + pgvector | 再加 LLM provider |
| 一致性 | 强 | 强（同步写） | 强（同步写，但极慢） |
| 数据出库 | 否 | 自托管不出网 | 同左 |

##### 结论

1. **主链路窗口仍用 MySQL 轮次**。mem0 原文模式写一轮 177ms、读还要再算一次 embedding，比一次索引查询贵两个数量级；而它连"顺序"都不保证，等于用更高的成本换更弱的语义。
2. **mem0 用在 L2 跨会话语义记忆**。这是它的强项，当前产品完全空白（「GMV 指订单实付金额」这类口径无法跨会话继承）。但必须异步执行——43 秒同步调用会直接拖死提问。
3. **L3 保留 mem0 原文实现作为兜底**，让"会话历史全部进 mem0"这个诉求有落地路径，同时不牺牲主链路。
4. **端口统一、存储分离**：`MemoryStore`（轮次）与 `SemanticMemoryPort`（事实）各管一段，切换只改装配。

> 若产品明确要求「会话历史全部进 mem0」：按上表六项补齐后再切，且**必须保留 MySQL 轮次作为降级路径**——mem0 不可用时问答不能挂。

### 6.10 意图识别（IntentPort）

#### 6.10.1 现状：没有独立的意图层

全仓只有一个显式意图判定——正则：

```java
// ChatQuestionService.java:117
Pattern.compile("^/regenerate(?:\\s+(\\d+))?$")
```

其余全部是隐式的：

| 决策 | 谁来定 | 依据 |
| --- | --- | --- |
| 用哪个数据源 | 前端传 `datasource` | 模型不参与 |
| 查哪些表 | SQL 生成模型 | 输出 JSON 里的 `tables` 字段 |
| 要不要出图 | 前端 + 二次模型调用 | `generateChart` |
| 是分析/预测还是问答 | **前端** | `streamAnalysisOrPredict(baseRecordId, actionType)` |
| 是不是追问 | 无判定 | 无条件注入最近 N 轮 |

两个可以直接指出的事实：

- `chat_record.datasource_select_answer` 列在 `V1__baseline.sql` 里建了，**全仓零读零写**——从 SQLBot 抄过来但没实现
- 用户在对话框里说「帮我分析一下这个结果」，走的是 SQL 生成链路，不是分析链路。分析入口在 UI 按钮上

#### 6.10.2 现有的「伪意图识别」：LLM 实体抽取

`ChatKnowledgeService.prepare` 里有一层前置 LLM 调用，承担了事实上的意图识别（抽业务实体 → 驱动术语召回和选表）：

```java
// ChatKnowledgeService.java:317-340
Optional<ChatModelRouter.ModelRef> chat = modelRouter.defaultChatModel();   // ← 无 oid
String raw = modelClient.generate(model.apiDomain, model.apiKey, model.baseModel, messages,
        model.params, model.upstreamFormat);
return parseEntities(raw);
```

```java
// ChatKnowledgeService.java:344-360
for (String part : text.split("[,，、;；\\n\\r]+")) {   // ← 按标点切
    String word = part.trim();
    if (!word.isEmpty() && seen.size() < MAX_LLM_ENTITIES) {   // ← 上限 30，无其它校验
        seen.add(word);
    }
}
```

四个问题：

| 问题 | 后果 |
| --- | --- |
| **无校验**：模型多写一句「以上是关键词」，按逗号切开后整句成为「实体」 | 垃圾词进 `extracted_keywords`，进而当 `matchText` 驱动术语召回和表召回 → **选错表** |
| **无置信度、无类型**：返回的是纯字符串列表 | 无法区分「查数」「分析」「闲聊」，也就无法分流 |
| **串行阻塞**：在 SQL 生成之前同步调用，读超时 `READ_TIMEOUT_MS = 600000`（10 分钟，`ChatModelClient.java:46`） | 模型卡住 → 整个提问卡 10 分钟，用户侧无任何反馈 |
| **模型选择绕过工作空间**：用 `defaultChatModel()` 而非 `defaultChatModel(oid)` | 无全局默认时回退 `firstAvailable()`（全库首个可用），**跨空间取模型** |

最后一处影响面不止意图识别。全仓 `defaultChatModel()`（无 oid）共 5 处：

| 位置 | 用途 |
| --- | --- |
| `ChatKnowledgeService.java:318` | 意图识别 / 实体抽取（**每问必走**） |
| `ChatQuestionService.java:792` | 分析 / 预测 |
| `ChatQuestionService.java:892` | 推荐问题 |
| `PermissionEnforcer.java:154` | 权限改写兜底 |
| `ScheduledTaskExecutor.java:127` | 定时任务 |

主链路（`:269` / `:273`）用的是 `defaultChatModel(oid)`，有工作空间兜底；这 5 处没有。`ChatModelRouter.java:146-150` 的注释写得很清楚「避免空间 A 的模型被空间 B 静默使用」——这条约束在 5 个调用点被绕过了。

#### 6.10.3 每问的 LLM 调用次数

| 阶段 | 次数 | 是否可跳过 |
| --- | --- | --- |
| 意图识别（实体抽取） | 1 | 模型不可用时可跳过 |
| SQL 生成 | 1 | 否 |
| SQL 修复 | 0~2（`MAX_SQL_REPAIR_RETRIES = 2`） | 失败才有 |
| 图表配置 | 0~1 | 无结果/无需图可跳过 |
| 推荐问题 | 0~1 | 开关控制 |

最少 2 次，最坏 6 次。**意图识别这一次在链路最前端且串行**——它慢，后面全部等它。

#### 6.10.4 端口定义

```java
package com.xiongx9527.askdata.ai.port;

public enum IntentType {
    DATA_QUERY,   // 查数：进 SQL 链路（现状默认）
    FOLLOW_UP,    // 追问：必须带历史
    ANALYSIS,     // 分析：走分析链路
    PREDICT,      // 预测：走预测链路
    COMMAND,      // 显式命令，如 /regenerate
    CHITCHAT,     // 与数据无关
    UNKNOWN       // 判不出来，按 DATA_QUERY 处理
}
```

```java
public final class Intent {

    public final IntentType type;
    /** 归一化后的提问（去命令前缀、去首尾空白）；原问题另存，不覆盖。 */
    public final String normalizedQuestion;
    /** 业务实体，已按 schema/问题校验过滤；可能为空，不为 null。 */
    public final List<String> entities;
    /** 扩展关键词，用于术语召回与选表；可能为空，不为 null。 */
    public final List<String> keywords;
    /** 0~1；null 表示实现不产出置信度。 */
    public final Double confidence;
    /** 命中的规则名或模型给出的理由，仅用于日志与排障。 */
    public final String reason;

    public Intent(IntentType type, String normalizedQuestion, List<String> entities,
                  List<String> keywords, Double confidence, String reason) {
        this.type = type;
        this.normalizedQuestion = normalizedQuestion;
        this.entities = entities == null ? Collections.<String>emptyList() : entities;
        this.keywords = keywords == null ? Collections.<String>emptyList() : keywords;
        this.confidence = confidence;
        this.reason = reason;
    }

    public static Intent fallback(String question) {
        return new Intent(IntentType.DATA_QUERY, question,
                Collections.<String>emptyList(), Collections.<String>emptyList(), null, "fallback");
    }
}
```

```java
public interface IntentPort {

    /**
     * 识别意图。永不抛异常、永不返回 null：
     * 任何失败（模型不可用 / 超时 / 输出不可解析）都返回 Intent.fallback(question)，
     * 与现状「抽取失败退化为词法命中」的降级语义一致。
     */
    Intent recognize(IntentRequest request);
}
```

```java
public final class IntentRequest {

    public final String question;
    public final long oid;
    public final Long dsId;
    /** 前序轮次（可为空）；实现自行决定用几轮。 */
    public final List<Turn> history;

    public IntentRequest(String question, long oid, Long dsId, List<Turn> history) {
        this.question = question;
        this.oid = oid;
        this.dsId = dsId;
        this.history = history == null ? Collections.<Turn>emptyList() : history;
    }
}
```

三个类型、一个接口。**没有 `IntentClassifier` 基类，没有 `IntentRegistry`，没有插件机制。**

#### 6.10.5 三级实现，按成本排

| 级别 | 实现 | LLM 调用 | 触发条件 | 行为 |
| --- | --- | --- | --- | --- |
| L0 | `RuleIntentPort` | 0 | 总是先跑 | `/regenerate` 正则、纯寒暄词表、空问题 → 直接给出意图 |
| L1 | `LlmIntentPort` | 1 | L0 未命中 | 结构化输出 `{type, entities, keywords}` |
| L2 | `HybridIntentPort` | 0~1 | 生产默认 | L0 命中即返回，未命中走 L1；L1 超时走 fallback |

`HybridIntentPort` 是生产实现，约 50 行，只是组合 L0 和 L1。

**关键改动：L1 输出结构化 JSON，不再输出逗号分隔的裸文本。**

沿用现有 SQL 的 `jsonFormat` 约束方式（`SchemaContextBuilder` 已有 `SQL_JSON_FORMAT` 先例）：

```json
{"type":"DATA_QUERY","entities":["订单","销售额"],"keywords":["订单","销售额","GMV"]}
```

解析失败 → `Intent.fallback(question)`，不抛。

#### 6.10.6 三条必须加的校验

这三条是现有实现完全没有的，也是「选错表」的主要来源：

| 校验 | 规则 | 失败处理 |
| --- | --- | --- |
| 实体长度 | 单实体 ≤ 32 字符 | 丢弃该实体 |
| 实体来源 | 必须出现在 `question` 或本轮 schema 的标识符/注释中（大小写不敏感子串） | 丢弃该实体 |
| 数量上限 | entities ≤ 30（沿用 `MAX_LLM_ENTITIES`）、keywords ≤ 10（沿用 `MAX_EXPANDED_KEYWORDS`） | 截断 |

第一条直接干掉「以上是关键词」这类整句垃圾；第二条干掉模型幻觉出来的不存在的业务词。

#### 6.10.7 与现有链路的关系

```
ChatKnowledgeService.prepare(question, oid, dsId, appId, historyText)
  ↓ 拆分
IntentPort.recognize(IntentRequest)          → Intent
  ↓
KnowledgeService.recall(intent, oid, dsId, appId)  → KnowledgeContext
  ↓
SchemaContextBuilder.buildSqlMessages(..., knowledge, historyTurns)
```

变化点：

| 项 | 现状 | 改后 |
| --- | --- | --- |
| `matchText` | `llmEntities.isEmpty() ? question : join(entities)` | `intent.keywords.isEmpty() ? question : join(keywords)` |
| 历史来源 | `priorContext(chatId)` 文本 | `MemoryStore.recent()` 的 `Turn` |
| 意图分流 | 无 | `CHITCHAT` / `COMMAND` 可短路，不进 SQL 链路 |
| 模型选择 | `defaultChatModel()` | `defaultChatModel(oid)` |

「`CHITCHAT` 短路」需要产品确认：现在所有提问都会进 SQL 链路，加短路会改变行为。**默认不短路**，只记录日志观察误判率，跑够样本再开。

#### 6.10.8 验收

| 项 | 判定 |
| --- | --- |
| 模型不可用 | `recognize` 返回 `fallback`，主链路正常出 SQL（与现状一致） |
| 模型返回非 JSON | 同上，不抛异常 |
| 模型返回「以上是关键词」整句 | 被长度校验丢弃，`entities` 为空，走词法 |
| 模型返回不存在的表名 | 被来源校验丢弃 |
| 意图识别超时 | 主链路不阻塞超过配置的 `intent.timeout-ms`（默认 3000），降级继续 |
| `/regenerate 123` | L0 直接判 `COMMAND`，零 LLM 调用 |
| 连续 100 条真实提问 | 意图误判率 < 5%（人工抽检） |

超时这一条是新增的硬约束：现在继承的是 `READ_TIMEOUT_MS = 600000`，意图识别必须有自己的短超时。

#### 6.10.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 独立的小模型分类器 | 训练/部署成本高于收益；规则 + 大模型结构化输出足够 |
| 多意图（一句话里既查数又要分析） | 当前帧协议不支持一次返回两个结果，属于协议改造 |
| 澄清对话闭环（「你是指 A 还是 B？」） | 需要前端多轮交互协议，不在本次范围 |
| 把 `datasource_select_answer` 用起来 | 数据源由前端确定，让模型猜数据源风险更高 |
| 意图置信度阈值分流 | 置信度不可校准，先只记录不分支 |

---

### 6.11 模型监测（监听器 + 健康度）

#### 6.11.1 现状：只有一次性校验，没有监测

`ai_model` 的两个状态位（`V5__ai_model_states.sql` 注释已写明语义）：

| 字段 | 语义 | 谁写 |
| --- | --- | --- |
| `valid` | 连通校验是否通过 | **只有** `AiModelService.streamStatus`（手动点「校验」） |
| `enable` | 是否启用 | 用户手动，或校验失败时被自动置 false |

全仓只有 2 个 `@Scheduled`：

```
chart/server/ChartImageService.java:365   图表图片 TTL 清理
scheduled/ScheduledTaskScheduler.java:49  定时任务轮询
```

**没有模型探活、没有失败率、没有延迟、没有错误码统计。** 模型在配置当天校验通过，之后密钥被吊销、余额耗尽、端点下线，系统一无所知——`valid` 永久停在 `true`。

#### 6.11.2 四个断裂（都有代码证据）

**1. 默认模型绕过 `enable` / `valid`**

```java
// AiModelRepository.java:70-73
public Optional<Map<String, Object>> findDefault(int modelType) {
    return jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_model WHERE default_model = TRUE AND model_type = ?",
            modelType).stream().findFirst();
}
```

没有 `enable` / `valid` 条件，而 `defaultChatModel(oid)` 直接返回它（`ChatModelRouter.java:152-156`）。对比 `chatModelById`（`:183-186`）是**有**校验的：

```java
if (Rows.intg(model, "model_type", CHAT_MODEL_TYPE) != CHAT_MODEL_TYPE
        || !Rows.bool(model, "enable", false)
        || !Rows.bool(model, "valid", false)) {
    return Optional.empty();
}
```

结果：**界面显示「已停用 / 未校验」的默认模型，主链路照样在用**。状态位对默认模型完全无效。

**2. 校验失败会停用默认模型，绕过自身守卫**

服务层有明确不变式（`AiModelService.java:166-169`）：

```java
if (isDefault && !requestedEnable) {
    // 默认模型不可停用：停用会让该类型链路无模型可用
    throw new BadRequestException(messageResolver.get("aimodel.disable_default_error"));
}
```

但校验失败走仓储层，绕过服务层（`AiModelService.java:476-479`）：

```java
aiModelRepository.updateValid(modelId, passed);
if (!passed) {
    aiModelRepository.updateEnable(modelId, false);   // ← 直接改库
}
```

一次网络抖动 → 默认模型 `enable=false` → 与上面那条不变式直接矛盾。而由于断裂 1，主链路还在用它，`enable=false` 除了让界面显示「未启用」之外没有任何效果。

**3. 改密钥不重置 `valid`**

```java
// AiModelService.java:151
// valid 只由连通校验回写：保存配置沿用库中值，不因保存动作改写
boolean valid = Rows.bool(existing, "valid", false);
```

`api_key` / `api_domain` / `base_model` 都可能被改，`valid` 仍沿用旧值。密钥改错 → 仍 `valid=1` → 每次提问都失败，界面却显示正常。

**4. 没有失败降级**

```java
// ChatQuestionService.java:273-276
if (!model.isPresent() || !model.get().usable()) {
    emitError(writer, messageResolver.get("scheduled.no.default.model"));
    return;
}
```

`usable()` 只检查 `apiDomain` / `baseModel` 非空。选中模型调用失败（401 / 超时 / 5xx）时链路直接报错——即使同工作空间还有 3 个健康模型，也不会切。

#### 6.11.3 端口定义

```java
package com.xiongx9527.askdata.ai.port;

/** 一次模型调用的结果，供被动记录使用。 */
public final class CallOutcome {

    public final boolean success;
    /** 归一后的错误类别；成功时为 null。 */
    public final String errorKind;    // unauthorized / rate_limited / timeout / unreachable / protocol_error
    public final long latencyMs;

    public CallOutcome(boolean success, String errorKind, long latencyMs) {
        this.success = success;
        this.errorKind = errorKind;
        this.latencyMs = latencyMs;
    }
}
```

```java
public final class ModelHealth {

    public final boolean available;
    /** 连续失败次数，成功即清零。 */
    public final int consecutiveFailures;
    /** 熔断打开时间戳；0 表示未熔断。 */
    public final long openedAt;
    public final long lastCheckedAt;
    public final long lastLatencyMs;
    public final String lastErrorKind;

    public ModelHealth(boolean available, int consecutiveFailures, long openedAt,
                       long lastCheckedAt, long lastLatencyMs, String lastErrorKind) {
        this.available = available;
        this.consecutiveFailures = consecutiveFailures;
        this.openedAt = openedAt;
        this.lastCheckedAt = lastCheckedAt;
        this.lastLatencyMs = lastLatencyMs;
        this.lastErrorKind = lastErrorKind;
    }
}
```

```java
public interface ModelHealthPort {

    /** 每次真实调用后上报（由 HealthModelListener 调用，见 §6.11.4；业务层不感知）。 */
    void record(long modelId, CallOutcome outcome);

    /** 读当前健康度；无记录返回 available=true（未观测不等于不可用）。 */
    ModelHealth get(long modelId);

    /** 主动探活（一次最小请求）；用于配置保存后预热与长时间无流量时兜底。 */
    ModelHealth probe(ModelRef model);

    /** 是否允许调用；熔断打开时返回 false。 */
    boolean allow(long modelId);
}
```

一个接口、两个值对象，没有状态机框架。

#### 6.11.4 监听器：对齐 ChatModelListener

§6.11.3 的 `ModelHealthPort.record(modelId, outcome)` 只是「结果上报」。真正需要的是**在模型调用边界上的一次拦截**：请求前、响应后、异常时各一次回调，回调之间共享一份可变属性表。langchain4j 1.23.0 的 `ChatModelListener` 就是这个形状（实测签名见 §6.11.5），直接对齐，不发明新模型。

**为什么不埋点而要 listener**：`ChatModelClient` 的调用面是 9 处（`call()` ×5、`stream()` ×2、`embed()` ×2）。埋点要改 9 处，每加一个观测维度再改一遍；listener 只挂在客户端出口上，1 处。

端口定义（Java 8，`ai.port`）：

```java
public enum CallKind { CHAT, EMBEDDING, RERANK }

/** 一次模型调用的上下文；attributes 在三个回调之间共享同一个实例。 */
public final class ModelCall {

    public final ModelRef model;
    public final CallKind kind;
    public final long startedAt;
    /** 输入规模：chat=消息数 / embedding=条数 / rerank=候选数。不含内容。 */
    public final int inputSize;
    /** 可变关联表：调用方写 chatId / recordId / step / traceId，listener 只读。 */
    public final Map<String, Object> attributes;

    public ModelCall(ModelRef model, CallKind kind, int inputSize) {
        this.model = model;
        this.kind = kind;
        this.inputSize = inputSize;
        this.startedAt = System.currentTimeMillis();
        this.attributes = new LinkedHashMap<String, Object>();
    }
}

public final class ModelResult {

    public final long latencyMs;
    public final long inputTokens;
    public final long outputTokens;
    /** stop / length / tool_calls；未知时为 null。 */
    public final String finishReason;

    public ModelResult(long latencyMs, long inputTokens, long outputTokens, String finishReason) {
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.finishReason = finishReason;
    }
}

public interface ModelListener {

    default void onRequest(ModelCall call) { }

    default void onResponse(ModelCall call, ModelResult result) { }

    default void onError(ModelCall call, Throwable error, long latencyMs) { }
}
```

三个方法都是 `default`（与 langchain4j 一致），实现类只覆写关心的那一个。

**五条硬约束**：

| 约束 | 理由 | 做法 |
| --- | --- | --- |
| 回调不能抛 | 观测不能改变业务结果 | 每个回调 try/catch + `log.warn`；`CompositeModelListener` 逐个隔离，一个 listener 炸不影响下一个 |
| 回调不能阻塞 | 跑在模型调用线程上 | 导出走有界队列，满即丢（`BatchSpanProcessor` 默认 2048 条 / 批 512） |
| `attributes` 只放标量 | 要能进 span / 日志 / JSON | 只允许 `String` / `Number` / `Boolean`，写入方负责 |
| 三次回调同一个 `attributes` 实例 | request → response 关联 | adapter 建一次，透传 |
| 默认不采集内容 | 问数 prompt 里是表名、字段名、样例数据 | 只报长度 / token / 错误类别；内容开关默认关（§6.11.6） |

**与 §6.11.3 的关系（修正）**：`ModelHealthPort.record` 不再由 adapter 直接调，改成 listener 的一个消费者：

```
ChatPort adapter（NativeChatPort / SpringAi2ChatPort）
   └─ onRequest / onResponse / onError
        └─ CompositeModelListener
             ├─ HealthModelListener       → ModelHealthPort.record(...)   熔断数据源
             ├─ MicrometerModelListener   → 四个指标（§6.11.10）
             └─ OtlpModelListener         → OTLP span（§6.11.6，默认关）
```

好处：加一个观测维度 = 加一个 listener，adapter 不动；关掉导出不影响健康度与熔断。

**落点**：只需改 `ChatModelClient` 的三个出口（`call()` / `streamCompletion()` / `embed()`），9 个业务调用点零改动。`streamCompletion` 的 `TokenHandler.onComplete` / `onError` 正好对应 `onResponse` / `onError`；`onResponse` 在流结束时只发一次，不是逐 token（langchain4j 流式也是这个语义，见 §6.11.5）。

#### 6.11.5 两个框架的桥接（实测签名）

结论：**langchain4j 1.23.0 有现成 listener 接口，Spring AI.0.0-RC2 没有**。所以 `ModelListener` 是端口层自研接口，两边各写一个 adapter；Spring AI 侧靠 Flux 回调或 `ObservationHandler` 反调。

**langchain4j 1.23.0**（`javap` 实测，jar 取自 Maven Central）：

| 事实 | 证据 |
| --- | --- |
| `dev.langchain4j.model.chat.listener.ChatModelListener` 三个方法全是 `default` | `onRequest(ChatModelRequestContext)` / `onResponse(ChatModelResponseContext)` / `onError(ChatModelErrorContext)` |
| 三个 Context 都带 `attributes()`，返回 `Map<Object,Object>` | `ChatModelResponseContext` 另有 `chatResponse()`；`ChatModelErrorContext` 另有 `error()` |
| 两个 builder 都能挂 listener | `OpenAiChatModel.builder().listeners(List<ChatModelListener>)` / `listeners(ChatModelListener...)`；`OpenAiStreamingChatModel` 同 |
| 接口上也有 `listeners()` | `ChatModel.listeners()` / `StreamingChatModel.listeners()` |
| 流式只有首尾回调，没有逐 token | `StreamingChatModel$1` / `$2` 包装 handler：`onCompleteResponse` 时调一次 `onResponse`（聚合后的 `ChatResponse`），`onError` 时调 `onError` |
| 逐调用属性注入 | `ChatRequestOptions.listenerAttributes()`，经 `ChatModel.chat(ChatRequest, ChatRequestOptions)` 合入 |
| listener 抛异常不影响调用 | `ChatModelListenerUtils` 每个 listener 单独 try/catch，日志原文 `An exception occurred during the invocation of the chat model listener. This exception has been ignored.` |
| embedding 有同款 | `dev.langchain4j.model.embedding.listener.EmbeddingModelListener` + 三个 Context（core 1.23.0 内） |
| **没有官方 OTel 导出模块** | `dev.langchain4j:langchain4j-observability` / `-observability-otel` / `-opentelemetry` 在 1.23.0 三个坐标均 404 |

langchain4j 侧 adapter 就是一次字段映射：

```java
final class Lc4jModelListenerAdapter implements dev.langchain4j.model.chat.listener.ChatModelListener {

    /** 用框架自带的 attributes 传递上下文，不另建 Map —— 不会因为缺回调而泄漏。 */
    private static final Object CALL_KEY = new Object();

    private final ModelListener delegate;

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ModelCall call = new ModelCall(ref(ctx.modelProvider()), CallKind.CHAT,
                ctx.chatRequest().messages().size());
        copy(ctx.attributes(), call.attributes);     // 调用方经 listenerAttributes 预置的 chatId / recordId
        ctx.attributes().put(CALL_KEY, call);
        delegate.onRequest(call);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        ModelCall call = (ModelCall) ctx.attributes().get(CALL_KEY);
        if (call == null) { return; }
        delegate.onResponse(call, result(ctx.chatResponse(), call));
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        ModelCall call = (ModelCall) ctx.attributes().get(CALL_KEY);
        if (call == null) { return; }
        delegate.onError(call, ctx.error(), System.currentTimeMillis() - call.startedAt);
    }
}
```

**Spring AI.0.0-RC2**（`javap` 实测 `spring-ai-client-chat` / `spring-ai-model`）：

| 事实 | 证据 |
| --- | --- |
| 没有 listener 接口 | 两个 jar 内不存在模型回调式的 `*Listener`，只有 advisor 与 observation |
| 模型级扩展点是 Micrometer Observation | `org.springframework.ai.chat.observation.ChatModelObservationContext`（`isStreaming()`、`builder()`，构造器包私有），处理器 `ChatModelCompletionObservationHandler` / `ChatModelMeterObservationHandler` |
| ChatClient 级另有观测 | `org.springframework.ai.chat.client.observation.ChatClientObservationContext` + advisor 观测 |
| 默认约定已带模型 / 参数 / token | `DefaultChatModelObservationConvention` 有 `aiProvider` / `requestModel` / `responseModel` / `requestMaxTokens` 等 `KeyValue` |

桥接二选一，**推荐第一种**：

1. **adapter 内用 Flux 回调反调**（不依赖 Spring AI 的观测设施）：`SpringAi2ChatPort` 拿到 `Flux<ChatResponse>` 后挂 `doOnSubscribe` / `doOnComplete` / `doOnError`，闭包直接持有 `ModelCall`。不碰 `ObservationRegistry`，流式切线程也不丢关联（闭包跟着序列走）。
2. **`ObservationHandler<ChatModelObservationContext>`**：`onStart` → `onRequest`、`onStop` → `onResponse`、`onError` → `onError`。**坑**：流式 observation 跑在 Reactor 线程上，`ThreadLocal` 里的 `attributes` 会丢；要关联必须把 id 放进 Reactor Context（`contextWrite` / `transformDeferredContextual`）。

**同一套 `ModelListener`、两个 adapter、业务层零差异** —— 这正是选它做端口接口、而不是直接把 `ChatModelListener` 暴露给业务的原因：后者会让 `ai.port` 包 import `dev.langchain4j`，违反 §6.2 铁律。

#### 6.11.6 导出到 LangSmith / Langfuse（OTLP）

两家都吃 **OTLP/HTTP**，都不需要厂商 SDK（官方 SDK 只有 Python / JS，Java 侧走 OTel 标准导出即可）。**同一份 `OtlpModelListener`，靠配置切 endpoint 与 headers**，不写两套。

| 平台 | Endpoint | 认证 | 协议 |
| --- | --- | --- | --- |
| Langfuse Cloud（EU 默认） | `https://cloud.langfuse.com/api/public/otel` | `Authorization: Basic base64(publicKey:secretKey)` | OTLP/HTTP；**不支持 gRPC** |
| Langfuse Cloud US / JP / HIPAA | `us.` / `jp.` / `hipaa.cloud.langfuse.com` + 同路径 | 同上 | 同上 |
| Langfuse 自建 | `http(s)://<host>/api/public/otel` | 同上 | 同上（需 ≥ v3.22.0；本机 v3 实测可用） |
| LangSmith | base `https://api.smith.langchain.com/otel`（traces 路径 `/otel/v1/traces`） | `x-api-key` | OTLP/HTTP |
| LangSmith EU / APAC / AWS | `eu.api.` / `apac.api.` / `aws.api.smith.langchain.com/otel` | 同上 | 同上 |

三个容易踩的点：

1. **Langfuse 建议加头** `x-langfuse-ingestion-version: 4`。官方文档说"不加可能延迟 10 分钟"；本机实测没有 10 分钟，但不加确实更慢（≤1s vs 3~5s，见下）。
2. **LangSmith 的 endpoint 是 base URL**：写 `.../otel`，exporter 自己拼 `/v1/traces`；写成 `.../otel/v1/traces` 会变成 `.../v1/traces/v1/traces` → 404（官方文档明确警告）。
3. **LangSmith 项目路由**用 `Langsmith-Project` 头；agent 模式则必须 `Langsmith-Agent-Id` + `Langsmith-Agent-Environment` **成对**出现，单发或与 `Langsmith-Project` 混用都是 400。

##### 本机 Langfuse 实测（2026-11-02）

环境：Langfuse v3 容器（`langfuse-host-deps-langfuse-web-1`），`http://localhost:3000`，Postgres / ClickHouse / Redis 走宿主机依赖；用真实项目密钥（`pk-lf-*` / `sk-lf-*`）打 `POST /api/public/otel/v1/traces`。

| 项 | 实测结果 |
| --- | --- |
| 认证 | `Authorization: Basic base64(publicKey:secretKey)` → 200 |
| 协议 | **OTLP/HTTP JSON 可用**（`Content-Type: application/json`），不必引 protobuf |
| 响应 | 200 + `{"name":"otel-ingestion-job","data":{"payload":{"fileKey":"events/otel/<project>/..."}}}`，耗时 0.03~0.2s |
| 可见延迟（带头） | **≤ 1s**（第一次轮询即命中） |
| 可见延迟（不带头） | **3~5s** |
| trace 名 | 取根 span 名；`langfuse.trace.name` 可覆盖 |
| traceId | 沿用 OTLP traceId（32 位 hex 原样保留） |
| 父子关系 | `parentSpanId` → `parentObservationId`，嵌套正确 |
| 类型判定 | **由 `gen_ai.*` 属性决定，不由 span 名决定**：名为 `answer` 的 span 带 `gen_ai.request.model` 仍是 `GENERATION` |
| 模型字段 | 优先 `gen_ai.response.model`（`gpt-4o-mini-2024`），无则 `gen_ai.request.model` |
| token | `gen_ai.usage.input_tokens` / `output_tokens` → `usageDetails {input, output, total}` |
| 成本 | `costDetails` 为空、`totalCost=0` —— 要 Langfuse 侧配好模型价目表才有数 |
| 错误 | span `status.code=2` + `message` → `level=ERROR`，`statusMessage` 原样保留 |
| 自定义属性 | `chat_id` / `oid` 落到 `metadata.attributes`（**字符串**），不是一级字段 |

**四条纠正**（都改了设计）：

1. **span 名可以语义化**。不必伪装成 `chat <model>`，叫 `sql_generate` / `answer` 一样进 GENERATION —— 类型看属性，不看名字。
2. **`langfuse.*` 是唯一能进一级字段的通道**（实测有效）：

   | 属性 | 落到 |
   | --- | --- |
   | `langfuse.session.id` | `trace.sessionId` → 会话视图按 `chat_id` 聚合 |
   | `langfuse.user.id` | `trace.userId` → 按提问人聚合 |
   | `langfuse.trace.name` | `trace.name`（可放"问数：上月销售额"这类可读标题） |

   其余自定义键（`oid` / `record_id`）只能进 `metadata.attributes`，按它过滤要查 metadata，不能当一级列。
3. **数字会变字符串**。`intValue` 进 `metadata.attributes` 后是 `"7"`；`usageDetails` 里才是数字。别拿 metadata 做数值聚合。
4. **JSON 够用**。官方 OTel Java exporter 只发 protobuf；本机证明 Langfuse 收 JSON，所以 Java 8 分支可自写一个 ~100 行的 JSON 导出器（Jackson 拼串 + 有界队列 + 批量），代价是重试与批量自己做。

##### 落地方案

| 分支 | 做法 | 成本 |
| --- | --- | --- |
| 当前 Java 8 + SB 2.7 | 自写 `OtlpHttpJsonSpanExporter`（Jackson + 现有 HTTP 客户端 + 有界队列），`OtlpModelListener` 只产出 `SpanData` | 中；不引 protobuf，Java 8 无兼容风险 |
| SB 4.1 + Spring AI | 加 `spring-boot-starter-opentelemetry`（4.1.2 已 GA，传递引入 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` 1.62.0），Spring AI 的模型 observation 自动成 span | 低（零代码） |

SB 4 的配置键（`management.opentelemetry.tracing.export.otlp.*`）：

```yaml
management:
  tracing:
    sampling:
      probability: 0.1            # 默认 10%；问数链路建议先 0.1
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTLP_ENDPOINT:}
          headers:
            Authorization: "Basic ${LANGFUSE_AUTH:}"
            x-langfuse-ingestion-version: "4"
```

Java 8 分支的开关（与 §6.13 的开关矩阵一致）：

```yaml
askdata:
  ai:
    observability:
      listeners: health,micrometer     # 默认；otlp 不在默认里
      otlp:
        enabled: false
        vendor: langfuse               # langfuse | langsmith | custom
        endpoint: ${OTLP_ENDPOINT:}
        headers: ${OTLP_HEADERS:}      # "Authorization=Basic xxx,x-langfuse-ingestion-version=4"
        capture-content: false
        queue-size: 2048
        batch-size: 512
        flush-interval-ms: 5000
```

##### span 结构与属性映射

```
trace   name=问数：<问题摘要>   session=chat_id   user=user_id
├─ span  intent           §6.10
├─ span  retrieval        向量召回 + 重排（§6.8）
├─ span  sql_generate     gen_ai.* → GENERATION
├─ span  sql_execute      db.system / db.statement（摘要，不写全量）
└─ span  answer           gen_ai.* → GENERATION（流式也只在结束时收尾一次）
```

| 属性 | 值 | 说明 |
| --- | --- | --- |
| `langfuse.session.id` | `chat_id` | 实测进 `trace.sessionId` |
| `langfuse.user.id` | 用户 id | 实测进 `trace.userId` |
| `langfuse.trace.name` | `问数：<问题前 30 字>` | 实测覆盖 trace 名 |
| `gen_ai.operation.name` | `chat` | |
| `gen_ai.request.model` | `ModelRef.baseModel` | |
| `gen_ai.response.model` | 响应里的模型名 | 有则优先作为 model |
| `gen_ai.usage.input_tokens` / `output_tokens` | `Usage` | 进 `usageDetails` |
| `gen_ai.response.finish_reasons` | `stop` / `length` | |
| `oid` / `record_id` / `step` | 自定义 | 只进 `metadata.attributes` |

**error 映射**：调用失败时 span `status.code=2` + `message=<errorKind>`，Langfuse 侧显示 `level=ERROR`，可直接按 level 过滤失败调用 —— 这是"模型监测"在 UI 上的落点。

##### LangSmith 实现（已设计，未实测）

LangSmith 与 Langfuse 的差别**只有 endpoint 与 headers**，所以是同一份实现换配置：

| 配置项 | Langfuse | LangSmith |
| --- | --- | --- |
| endpoint | `<host>/api/public/otel` | `https://api.smith.langchain.com/otel`（base URL，不带 `/v1/traces`） |
| 认证头 | `Authorization: Basic ...` | `x-api-key: <key>` |
| 项目路由 | 由密钥所属项目决定 | `Langsmith-Project: <name>` |
| 版本头 | `x-langfuse-ingestion-version: 4` | 无 |
| 专有属性 | `langfuse.*` 有效 | `langfuse.*` 无效（当普通属性存） |

实现上必须做到三点，否则接不上：

1. **headers 可配置**，不能写死 `Authorization`（LangSmith 用 `x-api-key`）。
2. **endpoint 只接受 base URL**，由 exporter 拼 `/v1/traces`；配置里出现 `/v1/traces` 时启动即报错，避免运行期 404。
3. **两套专有属性互不污染**：`langfuse.*` 只在 `vendor=langfuse` 时打；LangSmith 下改用 OTel 通用键与 `Langsmith-Project` 头。

**未实测部分**（无账号，§11）：LangSmith 对 `gen_ai.*` 的 run 类型映射、token 与成本展示、`Langsmith-Project` 头的实际行为。当前只保证"能发出去、不报错、不阻塞主链路"，语义正确性待有账号时补验。

**三条红线**：

| 红线 | 内容 |
| --- | --- |
| 默认关闭 | `askdata.ai.observability.otlp.enabled=false`。问数 prompt 里是表名、字段名、样例数据，导出到 SaaS 等于数据出境，必须按部署显式开 |
| 内容默认不采 | 只上报长度 / token / 错误类别；`capture-content=true` 时也要先脱敏（表名白名单 + 值掩码），且只对自建实例默认放开 |
| 失败不影响主链路 | 有界队列满了丢；导出在独立线程，不占模型调用线程；`enabled=false` 时 listener 直接不注册 |

**双投**：一个 exporter 只能一个 endpoint。要同时喂 LangSmith 和 Langfuse，两条路——OTel Collector 扇出（生产推荐），或 SDK 内挂两个 processor（各带一个 exporter，代码 +5 行）。

**不做**：不接厂商专有 ingestion 接口（Langfuse `/api/public/ingestion` 之类）。OTLP 已够用，专有协议要跟着厂商版本改。

#### 6.11.7 主动探活 vs 被动记录

**被动优先。** 真实流量的失败率比主动探测准确得多，且零额外成本。

| 信号 | 来源 | 用途 |
| --- | --- | --- |
| 被动 | `ModelListener` 的 `onResponse` / `onError` → `ModelHealthPort.record`（§6.11.4） | 主判据：连续失败计数、延迟分位 |
| 主动 | 定时 `probe`（默认 5 分钟，仅对无流量的模型） | 兜底：避免「没人用所以永不熔断，一来人就失败」 |

主动探测必须**最小**：一次 `1+1=?`、`max_tokens=1`。不要用真实业务 prompt，否则每 5 分钟烧一次 token。

#### 6.11.8 熔断状态机

| 状态 | 进入条件 | 行为 | 退出 |
| --- | --- | --- | --- |
| CLOSED | 初始 / 探测成功 | 正常调用 | 连续失败 ≥ 5 → OPEN |
| OPEN | 连续失败 ≥ 5 | `allow=false`，选模型时跳过 | 距 `openedAt` 超过 30s → HALF_OPEN |
| HALF_OPEN | 冷却到期 | 放行 1 次试探 | 成功 → CLOSED；失败 → OPEN |

三个阈值都可配（`askdata.ai.health.*`），默认取上表。

**关键约束**：熔断**不改变已选定的模型**，只影响 `ChatModelRouter` 的候选排序。熔断是「换下一个」，不是「报错」。

#### 6.11.9 跨实例一致性

`embeddingModel()` 的缓存用 `AiModelRepository.writeVersion()` 判断失效：

```java
// AiModelRepository.java:31-42
private final java.util.concurrent.atomic.AtomicLong writeVersion = new java.util.concurrent.atomic.AtomicLong();

public long writeVersion() {
    return writeVersion.get();
}
```

这是 **JVM 内计数器**。多实例部署时：

- 实例 A 改模型配置 → A 的计数器 +1 → A 立即失效
- 实例 B 的计数器不变 → 只能等 `EMBEDDING_CACHE_TTL_MS = 60_000`（`ChatModelRouter.java:42`）到期

60 秒窗口内不同实例可能用不同 embedding 模型——维度不同就会写入失败。

**修法**：版本号落库（单行版本表，或读 `MAX(update_time)`），一次轻量查询换掉 JVM 计数器；或至少把 TTL 降到 5 秒并接受该窗口。

模型健康状态同理：熔断计数放进程内存会导致「实例 A 熔断了、实例 B 还在打」。健康状态建议落库（`ai_model_health` 表）或放已有 Redis（`CacheProperties` 已支持 redis）。

#### 6.11.10 指标

| 指标 | 类型 | 用途 |
| --- | --- | --- |
| `ai.model.call.total{model,outcome}` | counter | 失败率 |
| `ai.model.call.latency{model}` | histogram | P50 / P95 / P99 |
| `ai.model.circuit.state{model}` | gauge | 0/1/2 = CLOSED/HALF_OPEN/OPEN |
| `ai.model.probe.total{model,outcome}` | counter | 主动探测结果 |

现有代码没有任何指标入口，`ChatQuestionService` 里只有 `log`。接 Micrometer（SB 自带）比自建调用记录表省事。

#### 6.11.11 验收

| 项 | 判定 |
| --- | --- |
| 默认模型 `enable=false` | 选模型时**跳过**它（当前会选中） |
| 默认模型 `valid=false` | 同上 |
| 改 `api_key` 后 | `valid` 重置为 false，需重新校验才能启用 |
| 校验失败且是默认模型 | 不停用（与 `disable_default_error` 不变式一致），只标 `valid=false` 并告警 |
| 连续 5 次 401 | 熔断打开，下一次选模型跳过它 |
| 熔断期间有其他健康模型 | 自动切换，请求不失败 |
| 熔断期间无其他模型 | 明确报错「所有模型不可用」，不是超时 |
| 冷却 30s 后 | 放行 1 次试探 |
| 多实例 | A 实例熔断后，B 实例 30s 内可见（落库或 Redis） |
| listener 自己抛异常 | 模型调用仍然成功，日志有一条 warn |
| 流式回答 | `onResponse` 只发一次（流结束时），不是 N 次 |
| `otlp.enabled=false` | 不注册 `OtlpModelListener`，无导出、无额外线程 |
| 导出端点不可达 | 队列满后丢 span，提问延迟不变 |
| 同一次提问的 intent / retrieval / sql / answer | 落在同一条 trace，可用 `chat_id` 关联 |

#### 6.11.12 不做的事

| 不做 | 理由 |
| --- | --- |
| 跨供应商自动切换（OpenAI → Anthropic） | 报文格式与能力不同，切换后行为不可比；只在同类型内切换 |
| 基于历史延迟的动态权重路由 | 每个工作空间模型很少，收益小 |
| 探活结果写回 `valid` | `valid` 是「人工校验通过」，探活是「当下可用」，混用会让界面反复跳动 |
| 模型调用全量审计表 | 量太大；指标 + 日志足够 |
| 默认上报 prompt / completion 原文 | 问数场景里就是业务数据，默认只报长度与 token |
| 自建 span 树替代现有 `chat_log` | 观测是旁路，不能变成业务依赖 |

---

### 6.12 Rerank 模型的页面改造

#### 6.12.1 现状：只有一行注释

```java
// AiModelService.java:41
/** 对话模型（ai_model.model_type=0）；向量模型为 1，Rerank 为 2（本期不开放）。 */
public static final int CHAT_MODEL_TYPE = 0;
```

`model_type=2` 在库里没有任何记录，代码里没有常量、没有分支、没有探活路径。页面上的「模型类型」只有对话 / 向量两项，`dimension` 与 `upstream_format` 也只对前两者有意义。

要开 Rerank，页面不是加一个下拉项就够——三类模型的**字段集、校验方式、默认模型语义**都不同。

#### 6.12.2 三类模型的字段矩阵

| 字段 | 对话（0） | 向量（1） | Rerank（2） |
| --- | --- | --- | --- |
| `name` | 必填 | 必填 | 必填 |
| `api_domain` | 必填 | 必填 | 必填 |
| `api_key` | 必填 | 必填 | 必填 |
| `base_model` | 必填 | 必填 | 必填 |
| `protocol` | 1 / 2 | 1 / 2 | **固定 1**（Rerank 只有 OpenAI 兼容风格） |
| `upstream_format` | 0~4 | 0~4 | **隐藏**（不适用） |
| `dimension` | 隐藏 | 必填（校验后回写） | **隐藏** |
| `config_list`（temperature / top_p / max_tokens / enable_thinking / additional_params） | 全量 | 仅 `dimensions` | **仅 `top_n` / `return_documents`** |
| `default_model` | 单选（同类型唯一） | 单选 | 单选 |
| 工作空间映射 | 通用 | 通用 | 通用 |
| 探活端点 | `/chat/completions` | `/embeddings` | **`/rerank`** |
| 探活成功回写 | `valid` | `valid` + `dimension` | `valid` |

#### 6.12.3 页面改造清单

**1）模型列表页（`/system/aimodel`）**

| 改动 | 说明 |
| --- | --- |
| 类型筛选加「Rerank」 | 与对话 / 向量并列，取值 `model_type=2` |
| 行内加类型标签 | 现在列表只有名称与供应商，三类混在一起分不清 |
| 「默认」徽标按类型展示 | 对话默认 / 向量默认 / 重排默认，各自唯一 |
| 状态列 | 现在只有 `enable` / `valid`；需加 §6.11 的健康度（可用 / 熔断中 / 未探测） |

**2）模型编辑页**

按 `model_type` 动态渲染字段组，切换类型时**清空不适用的字段**（否则会带着 `dimension` 或 `upstream_format` 存进去）：

```
对话：api_domain / api_key / base_model / protocol / upstream_format / config_list(全量)
向量：api_domain / api_key / base_model / protocol / upstream_format / config_list(dimensions)
重排：api_domain / api_key / base_model / config_list(top_n, return_documents)
```

按钮文案：对话「测试对话」、向量「测试向量化」、重排「**测试重排**」。

**3）检索配置区（数据源 / 高级应用设置）**

Rerank 不只是模型配置，还需要一组检索参数（§6.8.4）：

| 配置项 | 控件 | 默认 | 说明 |
| --- | --- | --- | --- |
| `rerank-mode` | 三态单选：不使用 / 加权打分 / 重排模型 | **加权打分** | 默认值 = 当前行为 |
| `vector-weight` | 数字 0~1 | 0.4 | 仅「加权打分」时可见 |
| `mention-weight` | 数字 0~1 | 0.6 | 仅「加权打分」时可见；两者之和提示但不强制等于 1 |
| `rerank-model` | 下拉（该工作空间可见的 Rerank 模型） | 空 | 仅「重排模型」时可见；为空时提示「将回落加权打分」 |
| `rerank-top-n` | 数字 | 10 | 重排后保留条数 |

**4）未配置时的引导**

「重排模型」选项在没有可用 Rerank 模型时**置灰**，附一行说明 + 「去配置」跳转。不要允许选中后静默回落——用户会以为生效了。

#### 6.12.4 后端要补的接口

| 接口 | 变化 |
| --- | --- |
| `GET /system/aimodel?model_type=2` | 支持按类型过滤（当前只有 keyword） |
| `POST /system/aimodel/status` | 增加 `model_type=2` 分支，走 rerank 探活 |
| `GET /system/aimodel/{id}/health` | 新增，返回 §6.11 的 `ModelHealth` |
| `GET /system/retrieval/settings` | 新增，返回 `rerank-mode` / 权重 / `rerank-model` |
| `PUT /system/retrieval/settings` | 新增，写入并校验（权重范围、模型归属） |

`RERANK_MODEL_TYPE = 2` 常量补到 `AiModelService`，并把 `dimension` 的「启用前提」校验（`:173`）限定为向量类型——当前写法是 `modelType == EMBEDDING_MODEL_TYPE`，已经正确，新增类型不会误伤。

#### 6.12.5 Rerank 探活契约

请求（与 §6.8.5 的 `RerankPort` 一致）：

```json
{"model": "<base_model>", "query": "测试查询", "documents": ["文档一", "文档二"]}
```

期望响应：

```json
{"results": [{"index": 0, "relevance_score": 0.91}, {"index": 1, "relevance_score": 0.12}]}
```

四个校验点（缺一即判失败）：

1. `results` 是数组且长度等于 `documents` 长度
2. 每个 `index` 落在 `[0, documents.size())`
3. `relevance_score` 是数字
4. 两次相同输入的结果顺序稳定（排除返回随机序的实现）

**探活失败只回写 `valid=false`，不自动 `enable=false`** —— 这条与 §6.11.2 断裂 2 的修法一致，避免一次网络抖动把默认重排模型停用。

#### 6.12.6 权限与命名空间

| 项 | 要求 |
| --- | --- |
| 模型 CRUD | 沿用 `requireAdmin()` |
| 检索配置读写 | 按工作空间管理员，不能跨 `oid` |
| Rerank 模型可见性 | 与对话/向量一致走 `ai_model_workspace_mapping` |
| 默认重排模型 | 按 `model_type` 隔离，`clearDefaults(modelType)` 已支持 |
| 文案 | 全部走 i18n key，不在前端硬编码中文 |

#### 6.12.7 验收（页面级）

| 项 | 判定 |
| --- | --- |
| 新建 Rerank 模型 | 表单不出现 `dimension` / `upstream_format` |
| 类型从「向量」切到「重排」 | `dimension` 被清空，保存后库里为 null |
| 重排探活成功 | `valid=true`，列表状态变「可用」 |
| 重排探活返回条数不符 | 判失败，给出「返回条数与文档数不一致」的具体提示 |
| 无可用 Rerank 模型 | 「重排模型」选项置灰 + 引导 |
| `rerank-mode` 默认 | 加权打分，且权重 0.4 / 0.6 与现状逐位一致 |
| 保存非法权重（>1 或 <0） | 拒绝并提示范围 |

#### 6.12.8 不做的事

| 不做 | 理由 |
| --- | --- |
| 页面上做 A/B 对比排序结果 | 需要离线评估集，属独立功能 |
| 让用户在页面上传/训练重排模型 | 只接第三方 Rerank API |
| 为 Rerank 单独做一套工作空间映射 | 现有 `ai_model_workspace_mapping` 已按模型 id 绑定，通用 |
| 页面上展示 rerank 分数分布 | 分数区间因厂商而异，跨模型不可比，展示会误导 |

### 6.13 可选能力：默认关闭，但必须有实现

**主流程只有一条**：提问 → 检索 → 生成 SQL → 执行 → 图表 / 回答（`ChatQuestionService` 的 SSE 主链）。其余能力全部可选。

但「可选」只表示**默认不启用**，不表示**可以只有接口没有实现**。本文前面出现的每个 `*Port`，发布时必须同时交付：一个可运行的生产实现、一条可关闭的装配路径（`Noop` 或"不注册 Bean"）、一套契约测试。**没有实现的端口不许进主干** —— 那不是抽象，是欠账。

#### 6.13.1 三条判定规则

| 规则 | 判定 | 反例 |
| --- | --- | --- |
| 有实现 | 每个 `*Port` 至少 1 个生产实现类，能被 Spring 装配 | `Langchain4jChatPort` 只留接口 / 只写 TODO |
| 可关闭 | 关闭 = 不注册 Bean 或走 `Noop`，行为等价于"该能力不存在" | 关闭后仍被调用，只是内部抛 `UnsupportedOperationException` |
| 双跑可验 | 关闭时主流程 SSE 帧与基线逐字节一致；开启时只多出该能力的行为 | 只测开启态 |

#### 6.13.2 交付清单

| 能力 | 端口 | 必须交付的实现 | 默认 | 开启方式 | 关闭时行为 |
| --- | --- | --- | --- | --- | --- |
| 主流程（chat + 向量） | `ChatPort` / `EmbeddingPort` / `VectorStore` | `NativeChatPort`（现有 `ChatModelClient` 收口）+ `QdrantVectorStore` | **开** | — | 不存在关闭态 |
| 框架切换 | `ChatPort` | `NativeChatPort` / `SpringAi2ChatPort` / `Langchain4jChatPort` | `native` | `askdata.ai.provider` | 回 `native` |
| 会话记忆 | `MemoryStore` | `TurnMemoryStore`（`chat_turn` 表） | **开** | — | 回退 `priorContextMessages`（现状） |
| 语义记忆 | `SemanticMemoryPort` | `Mem0SemanticMemory` + `NoopSemanticMemory` | 关 | `askdata.ai.memory.semantic.enabled` | 只走 `TurnMemoryStore`，L2 恒空 |
| 意图识别 | `IntentPort` | `RuleIntentPort` / `LlmIntentPort` / `HybridIntentPort` | `rule` | `askdata.ai.intent.provider` | 走现有正则 + 关键词 |
| 重排 | `RerankPort` | `HttpRerankPort`（厂商 `/rerank`）+ `NoopRerankPort` | `off` | `askdata.ai.rerank.mode` | 走 `weighted_score`（现 0.4 / 0.6） |
| 上下文窗口 | `ContextWindow` / `TokenCounter` | `HeuristicTokenCounter`（字符估算）+ `TurnWindow` | **开** | — | 回退"取最近 N 条记录" |
| 模型健康 | `ModelHealthPort` | `DbModelHealthStore` + `NoopModelHealth` | **开** | — | 不熔断、不探活、不按健康度排序 |
| 模型监听 | `ModelListener` | `CompositeModelListener` + `HealthModelListener` / `MicrometerModelListener` / `OtlpModelListener` | 健康 + 指标开，OTLP 关 | `askdata.ai.observability.*` | 不注册 OTLP，指标照出 |
| 观测导出 | `OtlpModelListener` | OTLP/HTTP exporter（Langfuse 已实测 / LangSmith 同实现未实测） | 关 | `askdata.ai.observability.otlp.enabled` | 无 span、无导出线程 |
| 护栏 | `GuardrailPort` | `RuleGuardrail` + `CompositeGuardrail` + `AllowAllGuardrail` | 开（规则） | `askdata.ai.guardrail.*` | 全放行（仅保留 `SqlSafety`） |
| 反馈 | `FeedbackPort` | `DbFeedbackStore`（`chat_feedback`）+ `NoopFeedback` | 开（只落库） | `askdata.ai.feedback.enabled` | 不落库，不采集隐式信号 |
| 评估 | `EvalRunner` | `JdbcEvalRunner`（打真实数据源，离线跑批） | 关 | `--profile eval` | 不存在运行时影响 |
| 多模态输入 | `Message.parts` + `ModelRef.capabilities` | `ContentPart` 中性表示 + 四协议序列化 | 关 | `askdata.ai.multimodal.enabled` | 纯文本；带图请求直接报错 |
| 语音输入（ASR） | `SpeechPort` | `HttpAsrSpeech` + `NoopSpeech` | 关 | `askdata.ai.speech.asr.enabled` | 接口不注册，前端只用文字 |
| 语音输出（TTS） | `SpeechPort` | `HttpTtsSpeech` + `NoopSpeech` | 关 | `askdata.ai.speech.tts.enabled` | 接口不注册，只出文字 |
| 文件导出 | `ArtifactPort` | `ExcelArtifactExporter`（包装现有 `ExcelService`）+ `NoopArtifact` | 关 | `askdata.ai.artifact.enabled` | 接口不注册，不导出 |
| 文件输入 | `FileParsePort` | `PoiPdfFileParser`（POI + PDFBox）+ `NoopFileParser` | 关 | `askdata.ai.file-input.enabled` | 上传接口不注册 |
| 工具调用 | `ToolCallingPort` / `ToolPort` | `Lc4jToolCallingAdapter` / `SpringAiToolCallingAdapter`（委托框架循环）+ `BuiltinToolProvider` + `McpToolProvider`；Java 8 分支 `NativeToolCallingAdapter`（最小自研） | 关（`tool.mode=none`，走固定管线） | `askdata.ai.tool.mode` | 与现状完全一致 |
| 槽位状态（非问数） | `SlotStore` | `DbSlotStore`（会话级槽位快照）+ `NoopSlotStore` | 关 | `askdata.ai.slot.enabled` | 不注册，问数主链零变化 |
| 服务目录（选项型槽位） | `CatalogPort` | `DbCatalogProvider`（服务 / 医生 / 时段）+ `NoopCatalog` | 关 | `askdata.ai.catalog.enabled` | 反问只出纯文本，不出选项 |
| 写操作（预约类） | `ActionPort` | `DbActionExecutor`（check / commit / 幂等 / 补偿）+ `NoopAction` | 关 | `askdata.ai.action.enabled` | 只读，任何写请求直接拒绝 |
| 人工确认 | `ConfirmGate` | `RuleConfirmGate`（写操作前强制复述）+ `AlwaysConfirmGate` | 关 | `askdata.ai.confirm.enabled` | 不确认直接执行（仅问数只读场景可接受） |

**观测导出的验收标准不同**：Langfuse 在本机已跑通（§6.11.6），可直接作为验收基线；LangSmith 无账号，验收只到"配置正确、请求发出、失败不阻塞"，语义映射留待有账号时补验。

**三个 `ChatPort` 实现都要真做**。`Langchain4jChatPort` 不是"留口子"：langchain4j 1.23.0 的 `maven.compiler.release=17`，它只能落在 SB4 / Java 17 分支，但落地形态与 `SpringAi2ChatPort` 完全一样（一个 adapter 类 + 一套契约测试）。不做，就等于把"可选"变成"不可选"。

#### 6.13.3 关闭语义：Noop 不是空壳

`Noop` 必须**返回业务上合理的默认值**，而不是抛异常：

| 端口 | `Noop` 行为 |
| --- | --- |
| `RerankPort` | 原顺序返回，`score` 透传，不重算 |
| `SemanticMemoryPort` | 读返回空列表，写静默丢弃（debug 日志） |
| `ModelHealthPort` | `get` 恒 `available=true`，`allow` 恒 `true`，`record` 空操作 |
| `ModelListener` | 不注册；`CompositeModelListener` 拿到空列表直接短路 |

这样关闭态与"这个能力从来没写过"完全等价，出问题时能二分定位。

#### 6.13.4 验收

| 项 | 判定 |
| --- | --- |
| 每个 `*Port` | 至少 1 个实现类 + 1 个 `Noop`（或明确的"不注册"装配） |
| 全部可选能力关闭 | 主流程 SSE 帧与 `native` 基线逐字节一致（§9.1 契约测试） |
| 全部可选能力开启 | 契约测试全绿，且只多出对应行为，不改主流程帧序 |
| 单个开启、其余关闭 | 也能跑通（正交，互不依赖） |
| `askdata.ai.provider=langchain4j` | 与 `spring-ai` 跑同一套契约测试，结果一致 |

#### 6.13.5 不做的事

| 不做 | 理由 |
| --- | --- |
| 为"以后可能用"预留空实现类 | 空实现 = 未实现，等于没做，还占维护面 |
| 一个开关同时控制多个能力 | 无法二分定位；一个能力一个开关 |
| 可选能力之间互相依赖（如 rerank 依赖 semantic memory） | 关掉任一个就连带失败，违反正交 |

### 6.14 护栏 / 反馈 / 评估（质量闭环）

§6.13 保证"每个能力都有实现"，这一节补的是**质量闭环**：护栏防错、反馈收错、评估量错。三件事都不在主链关键路径上，但缺了就只能靠人工发现问数答错。

#### 6.14.1 现状：SQL 安全做得好，质量闭环没有

| 环节 | 现状 | 证据 |
| --- | --- | --- |
| 输入护栏 | **无** | 问题原文直接拼进 prompt，无长度 / 注入 / 跨空间探测过滤 |
| SQL 护栏 | **有，且不弱** | `SqlSafety`：仅 SELECT / WITH / 只读 UNION；解析失败即拒；多语句拒；CTE 内写操作拒；`INTO OUTFILE` / `EXEC(` / `COPY ... TO PROGRAM` 拒；`version` / `current_user` 等函数拒；元数据查询默认拒 |
| 输出护栏 | **无** | 模型输出直接 `emit` 出帧 |
| 显式反馈 | **无** | `chat_record` 33 列里没有点赞 / 评分 / 原因 |
| 隐式反馈 | **弱** | 有 `regenerate_record_id`（重新生成），但没记录"为什么重生成" |
| 评估 | **无** | 无评估集表、无跑批、无指标 |
| 语料回流 | **半自动** | `training` 表人工 CRUD（`TrainingController`），不吸收反馈 |

一句话：**护栏只防"执行危险 SQL"，不防"答了不该答的"；反馈和评估完全没有。**

#### 6.14.2 护栏：三层，只放在边界上

| 层 | 检查 | 命中后 |
| --- | --- | --- |
| L0 输入 | 超长 / 空问题；注入特征（"忽略上述指令"、`system:`、成块 base64）；跨空间探测（问别的 `oid` 的表名） | **不进模型**，回固定话术 + 记 `chat_log`，省 token 也防注入 |
| L1 SQL | 复用 `SqlSafety`，另加三条：FROM 的表必须在召回集合内（现有 A12 只把 `sqlJson.tables` 当提示，没有强校验）；`setMaxRows` 上限；无时间条件的全表扫描 | 不执行，走既有 `error` 帧 |
| L2 输出 | 敏感值掩码（手机号 / 身份证 / 邮箱 / 银行卡）；查无数据时的兜底话术；图表类型与数据形状不匹配时降级成表格 | 改写文本后再出帧，不改帧序 |

三条硬约束：

1. **fail-closed**：护栏自身异常 → 按"不通过"处理（与 §6.11 权限约定一致），绝不因为护栏炸了就把原文放行。
2. **不改帧序**：只在既有 `error` / `info` 帧里表达，SSE 契约（§6.2 帧层）不动。
3. **不做在线 LLM 审核**：多一次调用 + 秒级延迟，问数场景规则足够；真要做也是异步旁路，不阻塞出帧。

#### 6.14.3 反馈：显式 + 隐式

新表（V6 迁移，`chat_record` 不动）：

```sql
CREATE TABLE chat_feedback (
    id            BIGINT PRIMARY KEY,
    record_id     BIGINT      NOT NULL,
    chat_id       BIGINT,
    oid           BIGINT      NOT NULL,
    create_by     BIGINT      NOT NULL,
    vote          SMALLINT,              -- 1 赞 / -1 踩 / NULL 仅评论
    reason        VARCHAR(64),           -- wrong_sql / wrong_chart / wrong_answer / slow / other
    corrected_sql TEXT,                  -- 用户改对的 SQL（可选）
    comment       TEXT,
    create_time   TIMESTAMP
);
CREATE UNIQUE INDEX uk_feedback_record_user ON chat_feedback (record_id, create_by);
```

| 类型 | 采集方式 | 用途 |
| --- | --- | --- |
| 显式 | 回答下方 👍 / 👎 + 原因单选 + 可选"我改对的 SQL"；同一人同一记录重复提交覆盖 | 主信号 |
| 隐式-重生成 | `regenerate_record_id` 非空 | 该回答大概率不满意 |
| 隐式-重问 | 同一 `chat_id` 5 分钟内重复问相似问题 | 上一次没答到 |
| 隐式-修复 | 修复环被触发（`MAX_SQL_REPAIR_RETRIES` 消耗） | 一次生成质量差 |

**回流三级，逐级人工确认**：

1. 👎 + `corrected_sql` → 写 `training` 表候选，`enable=false` 待审
2. 👎 无修正 → 进"待标注"列表，人工补 SQL 后才进语料
3. 👍 高频问题 → 进评估集候选（6.14.4）

**红线**：反馈**不自动改 prompt、不自动改模型、不自动启用语料**。反馈只是候选池，人确认后才生效——否则一条误点就能污染召回。

#### 6.14.4 评估：离线跑批，不是在线打分

评估集表：

```sql
CREATE TABLE eval_case (
    id              BIGINT PRIMARY KEY,
    question        TEXT    NOT NULL,
    datasource      BIGINT  NOT NULL,
    expected_sql    TEXT,
    expected_columns TEXT,
    tags            VARCHAR(255),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);
```

**判定不看 SQL 字符串相等**（等价写法太多），看**执行结果集等价**：列名集合 + 行数 + 排序后逐行比对（数值容差 1e-6）。没有 `expected_sql` 的用例只判"执行成功且非空"。

| 指标 | 定义 |
| --- | --- |
| `execution_accuracy` | 执行成功且结果集等价 / 用例总数 |
| `sql_valid_rate` | 一次生成即通过 `SqlSafety` 的比例 |
| `repair_rate` | 触发修复环的比例（越低越好） |
| `latency_p50` / `p95` | 端到端耗时 |
| `chart_match` | 图表类型与期望一致的比例 |

**门禁**：换模型 / 改 prompt / 升级框架前后各跑一次，`execution_accuracy` 下降 > 2pp 就阻止合并。这是 §9.1 契约测试（测行为一致性）之外的**质量回归**，两者都要有。

跑批入口用独立 runner（`@Profile("eval")` 或 CLI），**不在 Web 进程里跑**，避免打满连接池。

#### 6.14.5 端口（可选但必须有实现，§6.13）

```java
public interface GuardrailPort {

    /** stage = INPUT / SQL / OUTPUT；不通过时 verdict.reason 给 error key，由调用方转 i18n。 */
    GuardrailVerdict check(GuardrailStage stage, String text, Map<String, Object> ctx);
}

public interface FeedbackPort {

    void record(FeedbackEvent event);

    List<FeedbackEvent> recent(long recordId);
}

public interface EvalRunner {

    EvalReport run(List<EvalCase> cases);
}
```

| 端口 | 生产实现 | Noop | 默认 |
| --- | --- | --- | --- |
| `GuardrailPort` | `RuleGuardrail`（正则 + 表白名单）+ `CompositeGuardrail` | `AllowAllGuardrail` | 开（规则） |
| `FeedbackPort` | `DbFeedbackStore` | `NoopFeedback` | 开（只落库，不回灌） |
| `EvalRunner` | `JdbcEvalRunner`（打真实数据源） | — | 关（离线工具） |

`GuardrailVerdict.reason` 只回 key（如 `guardrail.input.injection`），文案走 i18n —— 与现有 `messageResolver` 的约定一致，不硬编码中文。

#### 6.14.6 验收

| 项 | 判定 |
| --- | --- |
| 输入含"忽略以上指令" | 不进模型，回固定话术，`chat_log` 有记录 |
| 模型生成 `DROP TABLE` / 查 `information_schema` | 仍被 `SqlSafety` 拒（回归用例） |
| FROM 了召回集合外的表 | 拒绝执行并提示 |
| 回答含手机号 / 身份证 | 输出掩码 |
| 点赞 / 点踩 | `chat_feedback` 落一条；同一人重复提交覆盖 |
| 点踩 + 改对 SQL | `training` 出现 `enable=false` 候选，不自动生效 |
| 跑评估集 | 输出 5 个指标；故意改坏 prompt，`execution_accuracy` 明显下降 |
| 护栏自身抛异常 | fail-closed，返回"无法处理"，不执行 SQL |
| 护栏 / 反馈关闭 | 主流程 SSE 帧与基线逐字节一致（§6.13 双跑） |

#### 6.14.7 不做的事

| 不做 | 理由 |
| --- | --- |
| 接 LLM 做在线内容审核 | 多一次调用 + 秒级延迟；规则护栏够用，异步旁路另议 |
| 用反馈自动微调 / 自动改 prompt | 数据量不够，且不可回滚 |
| 用 SQL 字符串相似度做评估 | 等价写法太多，误判率高 |
| 在线实时评估 | 评估是离线门禁，不是运行时功能 |
| 把 `chat_feedback` 做成多级评分 + 维度打分 | 用户不会填；一票 + 原因单选足够 |

### 6.15 工具调用：从固定管线到通用 ToolPort（含 MCP）

#### 6.15.1 现状：只会"生成 SQL"，不会"调用工具"

| 事实 | 证据 |
| --- | --- |
| 模型侧**完全没有** tool-calling | `ChatModelClient` 里没有 `tools` / `tool_choice` / `function` 任何字段 |
| 唯一"工具"是 SQL 执行，且写死在管线里 | `ChatQuestionService` 顺序调用 `generateText` → `SqlSafety` → `JdbcEngine`，没有选择权 |
| 已有有界环，但只修 SQL | `MAX_SQL_REPAIR_RETRIES` 回喂修复（`:403-421`） |
| 我们**已经是 MCP server** | `AskdataMcpTools` 用 `@Tool` / `@ToolArg` 暴露 `datasource_list` / `workspace_list` / `model_list` / `chat_start` / `access_token` / `server_info` |
| 但**不是** MCP client | 没有任何连接外部 MCP server 的代码 |

所以"工具调用"这一格不是缺框架能力，是缺**抽象**：把"调 SQL"提升成"调工具"，再把 MCP 当成工具来源之一。

#### 6.15.2 通用化：ToolPort + ToolSpec

```java
package com.xiongx9527.askdata.ai.port;

public enum ToolSideEffect { READ, EXECUTE, WRITE }

public enum ToolSource { BUILTIN, MCP }

/** 工具的静态描述；inputSchema 是 JSON Schema 字符串，端口层不依赖任何 JSON 库类型。 */
public final class ToolSpec {

    public final String name;          // 唯一名；MCP 工具带 mcp__<server>__ 前缀
    public final String description;   // 给模型看
    public final String inputSchema;   // JSON Schema
    public final ToolSideEffect sideEffect;
    public final ToolSource source;
    public final long timeoutMs;

    public ToolSpec(String name, String description, String inputSchema,
                    ToolSideEffect sideEffect, ToolSource source, long timeoutMs) { ... }
}

public final class ToolCall {

    public final String callId;        // 模型给的 id，用于回灌配对
    public final String name;
    public final String arguments;     // JSON 字符串，原样保存
}

public final class ToolResult {

    public final String callId;
    public final boolean ok;
    public final String content;       // 已截断；超限时 truncated=true
    public final boolean truncated;
    public final String errorKind;     // not_found / denied / timeout / invalid_args / failed
    public final long latencyMs;
}

public interface ToolPort {

    /** 按工作空间 + 用户权限过滤后的工具清单；模型看不到的工具等于不存在。 */
    List<ToolSpec> list(ToolScope scope);

    ToolResult invoke(ToolCall call, ToolContext ctx);
}
```

**工具即权限**：`list()` 里不出现的工具，模型根本看不到——比"调用后拒绝"更安全，也更省 token。

#### 6.15.3 通用封装：业务只调一次 ToolCallingPort

如果每个调用点自己拼 `tools[]`、解析 `tool_calls`、跑循环、截断结果，等于把现在"9 处调用 `ChatModelClient`"的问题复制到 N 个工具上。所以工具调用必须**封装成一个入口**，业务只调一次：

```
业务层（ChatQuestionService / McpAssistantSupport / 定时任务）
      │  toolCalling.call(request, sink)          ← 唯一入口
      ▼
ToolCallingPort（封装层，端口层内）
      ├─ ToolRegistry        合并内置 + MCP 工具，按 scope 过滤，前缀去重
      ├─ ToolCallingStrategy native / prompted / none / auto（§6.15.4）
      ├─ ToolLoop            步数 / 超时 / 截断 / 去重 / 无进展退出（§6.15.5）
      └─ ToolGuard           权限 / 参数校验 / 副作用闸门 / 结果包裹
      ▼
ToolPort（工具来源，可插拔）
      ├─ BuiltinToolProvider  sql.query / schema.lookup / chart.render …
      └─ McpToolProvider      mcp__<server>__<tool>（§6.15.6）
```

```java
public interface ToolCallingPort {

    /** 一次调用搞定：选工具、跑循环、截断、回灌，返回最终文本。 */
    ToolCallingResult call(ToolCallingRequest request, ToolCallingSink sink);
}

public final class ToolCallingRequest {

    public final ModelRef model;
    public final List<Message> messages;
    public final ToolScope scope;     // oid + uid + 数据源 → 决定可见工具
    public final String mode;         // null = auto
    public final int maxSteps;        // 0 = 默认 5
}

public final class ToolCallingResult {

    public final String answer;
    public final List<ToolTrace> traces;   // name / 参数摘要 / ok / latency
    public final Usage usage;              // 循环内所有调用累计
    public final String stopReason;        // final / max_steps / no_progress / error
}

/** 流式：只在最终答案上回调；工具中间过程走 info 帧，不新增帧类型。 */
public interface ToolCallingSink {

    void onDelta(String content, String reasoning);

    /** start / end，供上层发既有 info 帧。 */
    default void onToolEvent(String name, String phase) { }

    default void onError(String message) { }
}
```

**`auto` 的判定**：查模型能力缓存（`CapabilityCache`，按 `apiDomain + baseModel` 缓存 10 分钟）——已知支持 `tools` 走 `native`，已知不支持走 `prompted`，未知先试 `native`，解析不到 `tool_calls` 就**同一次调用内**降级 `prompted`（不重发第二次请求）。

**封装带来的三条收益**：

1. 新增工具 = 实现一个 `ToolProvider`，业务代码零改动；
2. 换模型（支持 / 不支持 function calling）= 策略自动切换，业务零改动；
3. 循环、截断、权限、观测只有一份实现，不会每个工具各写一遍。

**与 `ChatPort` 的关系**：`ToolCallingPort` 内部只用 `ChatPort`，不直接碰 `ChatModelClient`。所以它是端口层的"复合端口"——两个框架下都能跑，`tool.mode=none` 时完全不启用（等价于直接 `ChatPort.call`）。

##### 别自研调用流程规范：能委托就委托

`ToolCallingPort` + `ToolRegistry` + `ToolLoop` + `ToolProvider` 这套概念，两个框架都已经定义过，自己再写一遍就等于复制一份 langchain4j 的调用流程规范：

| 我们的概念 | langchain4j 1.23 | Spring AI.0 |
| --- | --- | --- |
| `ToolSpec` | `ToolSpecification` | `ToolDefinition` / `ToolCallback` |
| `ToolProvider` | `ToolProvider` / `@Tool` 扫描 | `ToolCallbackProvider` |
| `ToolLoop`（多轮） | `AiServices` 内置 tool 循环 | `ChatClient.tools()` 内部循环 |
| `McpToolProvider` | `langchain4j-mcp` | `spring-ai-starter-mcp-client` |
| `ToolGuard` | 包装 `ToolExecutor` | 装饰 `ToolCallback` |
| `ToolCallingSink` | `StreamingChatResponseHandler` | `Stream` + advisor |

所以规则三条：

1. **SB4 分支不自研循环**：`ToolCallingPort` 的实现直接委托框架（`Lc4jToolCallingAdapter` / `SpringAiToolCallingAdapter`），我们只写"映射 + 护栏 + 桥接"三件事。
2. **只有 Java 8 分支自研**（langchain4j 1.23 的 `release=17`，当前线用不了），且必须是最小实现：串行单工具、等 `tool_calls` 完整再解析、只取文本结果、不做流式参数增量。
3. **契约测试反向验证**：同一批用例跑三个 adapter，结果必须一致 —— 自研那份是被规范约束的临时实现，不是第二套规范。

一句话判断标准：**框架已定义的流程只做适配；只有框架给不了的才自己写**——权限过滤后的工具可见性、SSE 帧序、护栏、OTLP 导出，这四样才是我们真正要自研的部分。

#### 6.15.4 三种调用策略（必须都能跑）

| 策略 | 适用 | 实现 |
| --- | --- | --- |
| `none`（默认） | 现有问数主链 | 不走 tool-calling，保持固定管线与逐字帧序 |
| `native` | 模型支持 `tools` 参数（OpenAI 兼容 / Responses） | 把 `ToolSpec` 转成 `tools[]`，解析 `tool_calls` |
| `prompted` | 模型不支持 `tools`（vllm、部分本地模型） | 在 prompt 里给 JSON 协议 `{"tool":"x","args":{}}`，正则提取 + JSON 解析；解析失败即当普通回答 |

`prompted` 是必须的：`protocol=2`（vllm）与部分 `upstream_format` 不支持原生 tools，只有 native 会让能力在自建模型上直接消失。

#### 6.15.5 有界 ToolLoop（硬约束，不靠模型自律）

> 下表是 **Java 8 分支** `NativeToolCallingAdapter` 的约束；SB4 分支的循环由框架跑，同一组上限通过 `ToolGuard` 与框架参数传入（`maxSequentialToolsInvocations` 等），语义保持一致。

| 约束 | 默认 | 超限行为 |
| --- | --- | --- |
| 最大步数 | 5 | 停止，用已有信息作答，标注"信息不足" |
| 单工具超时 | 30s（SQL 执行 60s） | 中断该调用，回 `errorKind=timeout` 给模型 |
| 单次结果上限 | 32KB | 截断 + `truncated=true`，提示模型"结果被截断" |
| 总 token 预算 | 与 `ContextWindow` 共用 | 超预算先丢最早的 tool 结果 |
| 重复调用 | 同 name + 同 arguments | 第 2 次直接返回上次结果（幂等短路） |
| 无进展检测 | 连续 2 步没有新增信息 | 退出循环 |
| 写操作 | 默认不注册 | 需管理员开启 + 二次确认帧 |

**为什么不让模型自主规划**：问数场景 90% 是一步 SQL，步数一多延迟与不可控性陡增；有界环 + 确定性管线更稳。

#### 6.15.6 MCP：两个方向，别混

| 方向 | 状态 | 做法 |
| --- | --- | --- |
| 我们是 **server** | **已有** | `AskdataMcpTools`（`@Tool` / `@ToolArg`）+ `SqlbotMcpCompatController` + `McpSseClusterBridge`；fw-plus MCP starter，传输 `http-sse`：`GET /mcp/sse` 建流 + `POST /mcp/messages/{uuid}` 回传 |
| 我们是 **client** | **新增** | `McpToolProvider implements ToolPort`，把外部 MCP server 的工具挂进同一个 `ToolPort` |

##### 传输与握手

| 传输 | 用途 | 握手 |
| --- | --- | --- |
| `stdio` | 本机子进程（npx / uvx 类工具） | 启动进程，stdin/stdout 走 JSON-RPC；stderr 只进日志，不能污染协议流 |
| `sse`（旧） | 远端 server；**我们自己的 `/mcp/sse` 就是这种** | `GET /sse` 建流 → 收 `endpoint` 事件拿 POST 地址 → `POST /messages?sessionId=...` |
| `streamable_http`（新） | 远端 server，无长连接 | `POST /mcp`，响应头 `Mcp-Session-Id` 复用 |

握手四步，缺一步多数 server 直接断：

1. `initialize`（`protocolVersion` / `capabilities` / `clientInfo`）
2. 收到 `InitializeResult` 后发 `notifications/initialized`
3. `tools/list`（有 `nextCursor` 就翻页取完）
4. `tools/call`

##### 会话与生命周期

- 每个 server 一条长连接 + 一个 session id；`Mcp-Session-Id` 返回 404 表示会话过期 → 重连一次，再失败标记该 server 不可用。
- **懒连接**：首次 `list` / `invoke` 才连，避免启动被外部 server 拖死（外部 server 挂掉不能让应用起不来）。
- 空闲 5 分钟断开；重连退避 1s / 5s / 30s，连续 3 次失败进熔断——复用 §6.11.8 的三态，但**独立实例**，不占模型健康度。
- 超时：`initialize` / `tools/list` 10s，`tools/call` 30s。超时且无法取消时直接断连重连，避免请求悬挂占线程。

##### 工具发现与映射

- `tools/list` → `ToolSpec`：`name` = `mcp__<server>__<tool>`，`inputSchema` 原样透传（本来就是 JSON Schema），`sideEffect` 默认 `READ`。
- 描述截断到 200 字（长描述吃 token）；server 自述的 `annotations.readOnlyHint=false` **不采信**，仍按 `READ` 处理。
- 数量上限：单 server 50 个、全局 100 个，超了按 `tool_allowlist` 截断并告警——工具太多会把 prompt 撑爆。

##### 调用与结果

- 参数：模型给的 JSON 先按 schema 校验类型 / 必填 / 长度；非法直接回 `invalid_args` 给模型，不发给 server。
- 结果：`content[]` 只取 `type=text`，其余（image / resource）丢弃并记 `unsupported_content`；总大小 32KB 截断。
- `isError=true` → `ok=false`，但文本仍回给模型（模型能自我纠正）。
- 结果包 `<tool_result server="x" trusted="false">`；其中的"忽略以上指令"类文本不得改变后续行为（复用 §6.14.2 L2 护栏）。

##### 配置与隔离

```sql
CREATE TABLE mcp_server (
    id             BIGINT PRIMARY KEY,
    oid            BIGINT      NOT NULL,      -- 工作空间隔离
    name           VARCHAR(64) NOT NULL,      -- 工具前缀 mcp__<name>__
    transport      VARCHAR(16) NOT NULL,      -- stdio / sse / streamable_http
    url            VARCHAR(512),
    headers        TEXT,                      -- JSON，密钥用环境变量占位
    tool_allowlist TEXT,                      -- JSON 数组；空表示全部
    allow_write    BOOLEAN     NOT NULL DEFAULT FALSE,
    timeout_ms     BIGINT      DEFAULT 30000,
    enabled        BOOLEAN     NOT NULL DEFAULT FALSE
);
```

五条硬约束：

1. **只有管理员能加 server**，模型不能"发现"任意端点（否则就是 SSRF + 数据外泄入口）。
2. **URL 走 SSRF 校验**：禁内网段与云元数据地址，除非显式白名单。
3. **认证头加密落库**（复用 `DatasourceCrypto`），不回显。
4. **工具名加前缀** `mcp__<server>__<tool>`，避免与内置 `sql.query` 撞名。
5. **默认只读**：`allow_write=true` 才注册写工具，且仍要过 §6.14 护栏。

##### 实现选型：优先 `aj-mcp-client`

| 分支 | 做法 | 状态 |
| --- | --- | --- |
| Java 8（当前线） | **`aj-mcp-client`**（内部封装，优先）；`McpToolProvider` 只做 `ToolSpec` ↔ MCP 的映射 | 坐标与版本**待确认**：本地 `~/.m2` 与仓库 pom 都没有，需要 groupId / artifactId / version |
| Java 8 兜底 | 自写 JSON-RPC client（`sse` + `streamable_http`） | 仅在 `aj-mcp-client` 不可用时启用 |
| SB 4 | `spring-ai-starter-mcp-client`（2.0.0-RC2 已发布）或继续用 `aj-mcp-client` | 待定；若 `aj-mcp-client` 是纯 JDK 实现则两边通用 |

**为什么优先 `aj-mcp-client`**：握手、会话、重连、SSE 解析是纯协议体力活，自己写就是几百行不可测的并发代码；有内部封装的话，`McpToolProvider` 只剩映射层（约 120 行），还能直接对接我们自己的 `/mcp/sse`。

##### 实测状态

本机 askdata 在 `:8000` 上 `GET /mcp/sse` 返回 `{"code":401,"message":"unauthorized"}`（MCP 层要用户 token），**client 端到端未联调**。验证路径已经明确：

1. 取一个测试账号 token（`Authorization` 头）；
2. client 连自己的 `/mcp/sse`，跑 `initialize → tools/list → tools/call(server_info)` 三步；
3. 断言工具清单含 `datasource_list` / `workspace_list` / `model_list` / `chat_start` / `access_token` / `server_info`（`AskdataMcpTools` 现有 6 个）。

这是最省事的真实 server——工具已知、无外部依赖、出错能对照源码。

#### 6.15.7 与现有 SQL 管线的关系

`sql.query` 作为**内置工具**存在（`ToolSource.BUILTIN`，`EXECUTE`），但**默认不参与模型选择**：

- `tool.mode=none`（默认）→ 走现有确定性管线，SQL 生成/校验/执行/修复环完全不变，SSE 帧逐字一致；
- `tool.mode=native|prompted` → SQL 变成可调用工具之一，模型可以组合 `sql.query` + MCP 工具（如查指标口径）。

这样"通用化"不是重写主链，而是**给主链加一条可选分支**。

#### 6.15.8 验收

| 项 | 判定 |
| --- | --- |
| 模型返回 `tool_calls` | 解析 → 权限校验 → 执行 → 结果回灌 → 第二轮 |
| 超过 5 步 | 停止并给出部分答案，不无限循环 |
| 同工具同参数重复调用 | 短路返回缓存结果，不重复执行 |
| 工具返回 40KB | 截断到 32KB 且标记 `truncated` |
| 越权工具 | 不在 `list()` 中，模型看不到 |
| MCP server 不可达 | 该 server 工具消失，内置工具照常 |
| 写工具未开启 | 调用返回 `denied`，不执行 |
| `tool.mode=none` | 与现状逐字节一致（双跑） |
| `prompted` 策略 | 模型不支持 tools 时仍能完成一次工具调用 |

#### 6.15.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 模型自主规划（Plan-and-Execute / 多 Agent 编排） | 问数 90% 一步 SQL，规划收益小于延迟与不可控成本 |
| 让模型动态发现 / 安装 MCP server | SSRF 与数据外泄入口；只认管理员配置 |
| 跨工作空间共享 MCP 工具 | 隔离边界会被打破 |
| 默认开放写工具 | 问数场景不需要，风险远大于收益 |

### 6.16 通用接口一致性评审

前面 15 个端口是分批设计的，放在一起看有九处不一致。这一节统一收口——**接口定得不对，后面每个 adapter 都要跟着歪**。

#### 6.16.1 端口清单（15 个）

| 端口 | 职责 | 主流程 | 阻塞 | 可重试 | 可取消 | 默认实现 |
| --- | --- | --- | --- | --- | --- | --- |
| `ChatPort` | 对话 / 流式 | 是 | 是 | 可（有成本） | 是（`StreamHandle`） | `NativeChatPort` |
| `EmbeddingPort` | 向量化 | 是 | 是 | 幂等，可 | 否 | `NativeEmbeddingPort` |
| `RerankPort` | 重排 | 否 | 是 | 幂等，可 | 否 | `HttpRerankPort` / `NoopRerankPort` |
| `MemoryStore` | 会话记忆（有状态） | 是 | 是 | 否 | 否 | `TurnMemoryStore` |
| `ContextWindow` | 窗口裁剪 | 是 | 否（纯函数） | — | — | `TurnWindow` |
| `TokenCounter` | token 估算 | 是 | 否（纯函数） | — | — | `HeuristicTokenCounter` |
| `SemanticMemoryPort` | 语义记忆 | 否 | 是（须内部异步） | 非幂等，否 | 否 | `Mem0SemanticMemory` / `NoopSemanticMemory` |
| `IntentPort` | 意图识别 | 是 | 是 | 可 | 否 | `RuleIntentPort` / `LlmIntentPort` |
| `ModelHealthPort` | 健康 / 熔断 | 是 | 否（本地） | — | — | `DbModelHealthStore` / `NoopModelHealth` |
| `ModelListener` | 调用观测 | 是 | 否（回调） | — | — | `CompositeModelListener` |
| `GuardrailPort` | 输入 / 输出护栏 | 否 | 是 | 只读，可 | 否 | `RuleGuardrail` / `AllowAllGuardrail` |
| `FeedbackPort` | 反馈落库 | 否 | 是 | 非幂等，否 | 否 | `DbFeedbackStore` / `NoopFeedback` |
| `ToolPort` | 工具来源 | 否 | 是 | 按工具 | 否 | `BuiltinToolProvider` / `McpToolProvider` |
| `ToolCallingPort` | 工具循环 | 否 | 是 | 否 | 是（内部断连） | 三个 adapter（§6.15.3） |
| `EvalRunner` | 离线评估 | 否 | 是 | 否 | 否 | `JdbcEvalRunner`（**不在 `ai.port` 包**） |

#### 6.16.2 六个横切契约（所有端口共同遵守）

**1. 调用上下文只走参数，不走 ThreadLocal**

```java
public final class CallContext {

    public final long oid;          // 作用域标识；0 = 无租户（评估 / CLI / 内部任务）
    public final long uid;          // 操作人；0 = 系统身份
    public final long chatId;       // 0 = 非会话场景
    public final long recordId;     // 0 = 无记录
    public final String traceId;    // 观测关联
    public final long deadlineMs;   // 0 = 用端口默认；>0 = 绝对时间戳
    public final Map<String, Object> attributes;
}
```

现状是四套：`ChatPort` 只收 `ModelRef`、`ToolPort` 收 `ToolContext`、`GuardrailPort` 收 `Map`、`ModelCall` 自带 `attributes`。统一为 `CallContext` 后，权限过滤、观测关联、超时全部有地方放。

**2. 错误只有一种形状**

```java
public enum ErrorKind {
    UNAUTHORIZED,      // 401 / 密钥无效
    QUOTA_EXHAUSTED,   // 402 / 403 insufficient_quota：欠费或额度用尽（§6.26.2）
    RATE_LIMITED,      // 429
    TIMEOUT,           // 连接/读超时
    UNREACHABLE,       // DNS / reset / 5xx
    INVALID_ARGS,      // 400，参数或上下文超长
    DENIED,            // 权限不足（数据权限，不是上游鉴权）
    CONTENT_FILTERED,  // 上游内容审核拒绝（§6.26.2）
    PROTOCOL,          // 报文不符合协议
    INTERNAL
}

public final class PortError {

    public final ErrorKind kind;
    public final String messageKey;   // i18n key，如 aimodel.call.unauthorized
    public final String detail;       // 原始信息，只进日志
    public final boolean retryable;
}

public class PortException extends RuntimeException {
    public final PortError error;
}
```

`StreamSink.onError(String)` 改为 `onError(PortError)`（保留 String 重载过渡）：**只回 key + detail，不拼中文**，与现有 `messageResolver` 约定一致。

**3. 超时统一用 deadline**

端口不再各自硬编码（现在 `ChatModelClient.READ_TIMEOUT_MS = 600000` 是全局常量，导致一次意图识别也有 10 分钟预算）。规则：`CallContext.deadlineMs=0` 用端口默认值；`>0` 为绝对时间戳，超了立即返回 `TIMEOUT`。

| 端口 | 默认 deadline |
| --- | --- |
| `ChatPort.stream` | 600s（现有语义） |
| `ChatPort.call`（意图 / 结构化输出） | 30s |
| `EmbeddingPort` | 30s |
| `RerankPort` | 10s |
| `IntentPort` | 15s |
| `ToolPort.invoke` | `ToolSpec.timeoutMs` |

**4. 重试只给幂等端口**

| 可自动重试 | 不可 |
| --- | --- |
| `EmbeddingPort` / `RerankPort` / `GuardrailPort`（只读） / `IntentPort`（无副作用） | `ChatPort`（成本 + 非幂等） / `SemanticMemoryPort.remember` / `MemoryPort.append` / `FeedbackPort.record` |

重试次数 2、退避 200ms/1s，只对 `retryable=true` 的错误生效。

**5. 异步只允许在端口内部**

`SemanticMemoryPort.remember` 接口保持 `void`，但**实现必须内部异步**（mem0 `infer=true` 实测 42.9s，同步调用会把提问拖死）：有界队列 + 满即丢 + 丢弃计数进指标 + 失败只记日志。

**6. 实现必须线程安全**

所有端口实现注册为单例，**禁止持有请求级状态**；需要缓存的一律 `ConcurrentHashMap` + TTL。`McpToolProvider` 的连接池与 `DbModelHealthStore` 的计数是唯一允许有状态的地方，且必须自管并发。

#### 6.16.3 九处不一致与修法

| # | 问题 | 现状 | 修法 | 影响面 |
| --- | --- | --- | --- | --- |
| 1 | 上下文传递四套 | `ModelRef` / `ToolContext` / `Map` / `ModelCall.attributes` | 统一 `CallContext`（§6.16.2-1） | 全部 15 个端口 |
| 2 | 错误模型四套 | `onError(String)` / `errorKind` / `Verdict.reason` / 未定义 | 统一 `PortError`（§6.16.2-2） | 全部 |
| 3 | 无 deadline | 全局 600s 常量 | `CallContext.deadlineMs` | 全部 |
| 4 | 可取消不一致 | 只有 `StreamHandle.cancel` | 明确"只有流式可取消，其余靠 deadline" | `ChatPort` |
| 5 | 重试语义缺失 | 无定义 | 幂等表（§6.16.2-4） | 5 个端口 |
| 6 | 异步语义缺失 | `remember` 是 `void`，没约束 | 实现内部异步 + 指标 | `SemanticMemoryPort` |
| 7 | 观测有缺口 | `ModelListener` 只覆盖 chat / embedding，工具调用没有 | `CallKind` 增 `TOOL`；`ToolTrace` 挂同一 trace | `ToolPort` |
| 8 | 命名不一致 | `Port` / `Store` / `Runner` 三种后缀，含义没写清 | 定成三类约定：`*Port` = **无状态能力**契约；`*Store` = **有状态存储**契约（`MemoryStore` / `DbModelHealthStore` / `DbFeedbackStore`）；`*Runner` = 离线工具，`EvalRunner` 移出 `ai.port` 包 | 2 个文件 |
| 9 | 演进规则缺失 | 加方法会破坏实现 | 只加 `default` 方法（§6.16.5） | 全部 |

第 8 条**不改名**，只补约定：`*Port` 是无状态能力（可随便换实现），`*Store` 是有状态存储（绑定介质与事务），`*Runner` 是离线工具。所以 `MemoryStore` / `DbModelHealthStore` / `DbFeedbackStore` 保持原名，`EvalRunner` 移到 `com.xiongx9527.askdata.eval`——它不是运行时能力，不该进 `ai.port`。

#### 6.16.4 统一基元落地

```java
// 所有端口方法的推荐形态：context 在前，业务参数在后
public interface RerankPort {
    List<Double> rerank(CallContext ctx, ModelRef model, String query, List<String> documents);
}
```

兼容旧签名的过渡写法（Java 8 无默认参数，只能重载）：

```java
public interface RerankPort {

    List<Double> rerank(CallContext ctx, ModelRef model, String query, List<String> documents);

    /** 过渡重载：内部补一个"无租户"的 CallContext，仅用于测试与离线工具。 */
    default List<Double> rerank(ModelRef model, String query, List<String> documents) {
        return rerank(CallContext.system(), model, query, documents);
    }
}
```

`RerankPort` 返回值约定要写死：**与 `documents` 等长、同序，分数越高越相关**；实现不得过滤或重排返回数组本身。

#### 6.16.5 接口演进规则

| 规则 | 说明 |
| --- | --- |
| 新能力 = 新端口 | 不改老端口语义，避免所有实现跟着改 |
| 加方法必须带 default | 否则所有实现编译失败 |
| 参数只加 `CallContext` | 不再加第 4、5 个业务参数；要加就进 `attributes` |
| DTO 只增字段 | 新字段给默认值 + 新构造器重载，不删不改语义 |
| 语义变更要换名 | 例如 `rerank` 从"返回分数"改成"返回索引"，必须新方法 `rerankWithIndex` |

#### 6.16.6 验收（接口层）

| 项 | 判定 |
| --- | --- |
| 每个端口方法都收 `CallContext` | 无例外；`ContextWindow` / `TokenCounter` 可豁免（纯函数） |
| 每个端口错误都是 `PortError` | 无 `RuntimeException` 直抛 |
| 每个端口都有超时用例 | 契约测试里 `deadlineMs = now + 1` 必须返回 `TIMEOUT` |
| 端口包零框架依赖 | `grep -r "dev.langchain4j\|org.springframework.ai" ai/port` 为空 |
| 关掉任一端口 | 主流程 SSE 帧与基线逐字节一致（§6.13） |
| 端口实现单例无请求状态 | 并发用例：50 线程并发调同一端口，无串号 |

### 6.17 前瞻：将来用 langchain4j-agentic

#### 6.17.1 事实核查（`javap` 实测 `1.23.0-beta33`）

| 项 | 事实 |
| --- | --- |
| 坐标 | `dev.langchain4j:langchain4j-agentic:1.23.0-beta33`——**beta，从未 GA**（§2.3） |
| 字节码 | `major version: 61` = **Java 17**，只能落 SB4 分支，Java 8 物理上跑不了 |
| 编排 | `sequenceBuilder` / `parallelBuilder` / `parallelMapperBuilder` / `loopBuilder` / `conditionalBuilder` / `supervisorBuilder` / `plannerBuilder` |
| 单 Agent | `agentBuilder(Class)` + `@Agent(name, description, outputKey, async)` 注解 |
| 共享状态 | `AgenticScope`：`writeState` / `readState` / `hasState` / `state()` / `memoryId()` |
| 人工介入 | `humanInTheLoopBuilder()`，配 `AgentListener.onAgenticSystemSuspended` |
| 观测 | `AgentListener` 10 个回调，含 `before/afterAgentInvocation`、`onAgentInvocationError`、`onAgentSkipped`、`afterAgenticScopeCreated`、`before/afterAgentToolExecution` |
| 跨进程 | `a2aBuilder(...)`（A2A 协议客户端） |
| 决策 | `decisionAgentBuilder`（不是普通 agent，是"选下一步"） |

#### 6.17.2 能用在哪（三个场景，按收益排序）

| 场景 | 收益 | 风险 | 建议 |
| --- | --- | --- | --- |
| **SQL 修复环升级** | 现在是同模型回喂 `MAX_SQL_REPAIR_RETRIES` 次；agentic 可做"生成 → 校验 → 执行 → 诊断 → 重写"的条件环，不同阶段换不同模型 | 延迟与成本翻倍；路径不可解释 | **先做这一个**，默认关，只在修复环已触发时启用 |
| 多表 / 多步分析 | 拆解 → 并行查多表 → 交叉验证 → 汇总 | 并行查询打满连接池；结果一致性难保证 | 只在"分析 / 预测"子链路，主问答不动 |
| 报告生成 | 多轮取数 + 图表 + 文字，天然适合 `sequenceBuilder` | 输出长、帧序复杂 | 独立入口，不进 SSE 主链 |

#### 6.17.3 不能用的地方（硬边界）

| 不用 | 理由 |
| --- | --- |
| 主问答链路 | 逐字帧序（`finish_step` / `record_id`）+ 首字延迟 + 成本可控；`supervisor` / `planner` 是"模型决定下一步"，三条全破坏 |
| 契约测试与评估跑批 | agent 每次路径可能不同，结果不可比，回放也不成立 |
| Java 8 分支 | 字节码 61，直接排除 |

#### 6.17.4 端口层怎么预留：只留一个口，不引入 agent 概念

按 §6.15.3 的原则，**现在不设计 AgenticPort 的完整形态**——那等于再自研一套编排规范。只留最小契约：

```java
/** 复杂任务编排入口；默认实现直接返回 unsupported，tool.mode=none 时永不调用。 */
public interface WorkflowPort {

    WorkflowResult run(WorkflowRequest request, WorkflowSink sink);
}

public final class WorkflowRequest {

    public final CallContext ctx;
    public final String goal;              // 自然语言目标，如"修好这条 SQL"
    public final ToolScope allowedTools;   // 可见工具（复用 §6.15.2）
    public final WorkflowBudget budget;    // 步数 / token / 时长硬上限
}

public final class WorkflowResult {

    public final String answer;
    public final List<WorkflowStep> steps;   // 每步：名称 / 工具 / 结果摘要 / 耗时
    public final Usage usage;
    public final String stopReason;          // final / budget_exhausted / error
}
```

**端口里只出现 `goal` / `step` / `budget`，不出现 `Agent` / `Scope` / `Supervisor`** —— 换掉 agentic 不用改端口，这是"能委托就委托"在编排层的同一条规则。

拓扑（顺序 / 并行 / 循环）由 SB4 的 `AgenticWorkflowAdapter` 内部用 `sequenceBuilder` / `loopBuilder` 组装，端口不感知。

#### 6.17.5 三个必须提前定的约束

1. **预算必须硬上限**：`planner` / `supervisor` 自己决定步数，必须在 `WorkflowBudget` 给死（步数 ≤ 8、token ≤ 30k、时长 ≤ 120s），由 adapter 强制终止。否则一个 supervisor 能烧掉一天配额。
2. **`AgenticScope` 不能放可写租户数据**：scope 是共享内存态，`oid` / `uid` 由 adapter 注入且**只读**；工具执行前 adapter 用 `CallContext` 重新校验，**不信 scope 里的值**。否则一个 agent 改了 `oid` 就跨租户。
3. **观测必须接现有 listener**：`AgentListener.before/afterAgentToolExecution` → `ToolTrace`，`before/afterAgentInvocation` → `ModelCall`。不接的话，agent 内部调用在 Langfuse 里是断的（现在 §6.11 只覆盖到 `ChatPort` 边界）。

#### 6.17.6 迁移路径

| 阶段 | 做什么 | 前置 |
| --- | --- | --- |
| P1（现在） | 只加 `WorkflowPort` + `UnsupportedWorkflow` 默认实现（约 15 行），不做任何编排 | — |
| P2 | SB4 分支写 `AgenticWorkflowAdapter`，**只接"SQL 修复环"一个场景** | 端口层 + 契约测试 |
| P3 | 评估集对比：agentic 修复环 vs 现状，看 `repair_rate` / `execution_accuracy` / p95 | P2 + §6.14.4 |
| P4 | 结论为正再看分析 / 预测子链路 | P3 |

#### 6.17.7 不做的事

| 不做 | 理由 |
| --- | --- |
| 现在就设计完整 Agent 拓扑 | 等于自研编排规范；beta API 还会变 |
| 用 `supervisor` / `planner` 跑主问答 | 不可控、不可重放、成本无上限 |
| 把 agentic 引入 Java 8 分支 | 字节码 61，不可能 |
| 依赖 A2A 跨进程编排 | 我们只有单服务，没有多 agent 场景 |
| 追最新 beta | 锁定一个 beta 版本，升级单独评估（beta 无兼容承诺） |

#### 6.17.8 验收（将来接的时候）

| 项 | 判定 |
| --- | --- |
| 修复环场景 | `repair_rate` 下降、`execution_accuracy` 不降、p95 ≤ 现状 1.5× |
| 预算 | 构造死循环目标，必须在上限处终止并返回 `budget_exhausted` |
| 租户 | agent 尝试改 `oid`，工具执行仍按原 `oid` |
| 观测 | agent 内每次工具调用在 Langfuse 可见，且挂同一 trace |
| 关闭 | `workflow.enabled=false` 时主流程与现状逐字节一致 |

### 6.18 多模态：当前不用，接口必须先留

问数主链是"文本提问 → SQL"，用不到图片。但**接口现在不留，将来就是破坏性改造**——所以只做"留口"，不做"接图"。

**建议（结论先行）**：现在只做"留口"，不接任何模态；真有需求时按 P1 → P4 逐个开，每开一个都要能单独关掉。

| 优先级 | 能力 | 为什么这个顺序 | 改动面 |
| --- | --- | --- | --- |
| P0（现在） | 只留接口：`Message.parts` / `ContentPart` / `ai_model.capabilities` | 现在接 = 纯负债，主链用不到 | 1 个 DTO + 1 列 |
| P1 | **图片输入（vision）** | 唯一能提升问数准确率的模态（表结构截图、报表截图） | 四协议序列化 + token 估算 + 护栏 |
| P2 | **文件导出（xlsx / csv）** | 与模型无关，用户价值直接；已有 `ExcelService` 可包 | 1 个端口 + 1 个新帧 |
| P3 | 语音输入（ASR） | 体验向；准确率受环境影响，且多一次调用与延迟 | 1 个端口 + INTAKE 步骤 |
| P4 | 语音输出（TTS） | 锦上添花，不影响问数正确性 | 1 个方法 + 异步补链 |

**三条不要做**：

1. 不要为了"看起来支持多模态"把 audio / file part 塞进 `ChatPort`——协议矩阵会从 4 格变 12 格，维护成本翻三倍。
2. 不要用多模态模型替换 bge-m3 做向量化——维度不同，要重建 collection，收益不明确。
3. 不要做"上传任意文件问数"——等于任意格式解析 + 注入面 + 权限，成本远超收益；要文件走 MCP 工具。



#### 6.18.1 现状：`Message` 是纯文本，这是硬阻碍

```java
// ChatModelClient.java:59-67
public static class ModelMessage {
    public final String role;
    public final String content;   // 单个字符串，装不下 parts
}
```

协议侧早就不是字符串了：Chat Completions 的 `content` 可以是数组，Anthropic 是 `content: [{type:"image", source:{...}}]`，Responses 是 `input_image`。现在不改，将来要同时改 DTO、四个协议的序列化、6 个构造点。

#### 6.18.2 端口层怎么留（Java 8，零破坏）

```java
public final class Message {

    public final String role;
    /** 至少一个 TEXT part；现有调用方继续用 text(...) 构造。 */
    public final List<ContentPart> parts;
    /** 派生字段：所有 TEXT part 拼接，保持 6 个现有使用点零改动。 */
    public final String content;

    public static Message text(String role, String content) { ... }

    public static Message of(String role, List<ContentPart> parts) { ... }
}

public final class ContentPart {

    public enum Kind { TEXT, IMAGE_URL, IMAGE_BASE64 }

    public final Kind kind;
    public final String text;       // TEXT
    public final String url;        // IMAGE_URL
    public final String mimeType;   // IMAGE_*
    public final int width;         // 可选，用于 token 估算
    public final int height;
}
```

**`ChatPort` 只做文本 + 图片**：语音、图表、文件走独立端口（§6.18.10），视频不做。
#### 6.18.3 四个协议怎么序列化（矩阵）

| 协议 | 文本 | 图片 | 备注 |
| --- | --- | --- | --- |
| OpenAI 兼容（`protocol=1`，Chat Completions） | `{"type":"text"}` | `{"type":"image_url","image_url":{"url":...}}` | base64 用 data URL |
| Anthropic（`upstream_format=2`） | `{"type":"text"}` | `{"type":"image","source":{"type":"base64","media_type","data"}}` | **不收 URL**，必须先下载转 base64 |
| Responses（`upstream_format=3`） | `input_text` | `input_image` | 结构与 chat 不同，不能复用同一段序列化 |
| Azure | 同 Chat Completions | 同 | — |

`ContentPart` 是**中性表示**，四个 adapter 各自序列化——与 §6.4「协议 × 参数矩阵」同一个思路。

#### 6.18.4 能力声明：不声明就不发

- `ModelRef` 增加 `capabilities: Set<String>`（`vision` / `tools` / `json_mode` / `reasoning`），来源是 `ai_model` 新列 `capabilities`（V6 一起做）。
- 模型没声明 `vision` 时收到带图请求 → **直接报错**（`INVALID_ARGS`），不做静默丢图：丢图会让模型答非所问，比报错更糟。
- 判定以配置声明为主（可靠），探活为辅（发 1×1 图看是否 400）。

#### 6.18.5 问数场景的四个用法（都不在主链）

| 用法 | 收益 | 前置 |
| --- | --- | --- |
| 上传报表截图 → "这个指标怎么算" | 降低使用门槛 | 图片存储 + vision 模型 |
| 上传表结构截图 → 生成数据源 | 免手填字段 | OCR + schema 校验 |
| 图表图片理解 → "为什么这个月下降" | 结合已有图表解释 | 数据已在库，多数情况不需要 vision |
| 票据 / 单据 → 结构化 | 扩展场景 | OCR + 抽取校验 |

主链（文本 → SQL）不依赖多模态，所以 `askdata.ai.multimodal.enabled=false` 默认，开了也不影响帧序。
#### 6.18.6 五个必须提前定的约束

1. **图片不吃黑盒 token**：vision token 按 tile 算（OpenAI 是 `85 + 170 × tiles`），`TokenCounter` 必须加 `count(Message)` 重载，否则预算严重低估。
2. **图片不进观测**：OTLP / 日志只记 `mimeType` + 尺寸 + `sha256` 前 8 位，**不记 URL、不记 base64**（URL 常是签名链接，等于凭据）。
3. **存储与权限**：走现有文件存储，取图必须带登录态或签名 URL（与附录 A.1.3 图表 URL 同一个问题）；TTL 与 `chat_record` 同生命周期。
4. **图片是注入面**：图里的文字可以写"忽略以上指令"。护栏 L0 加分支：本轮含图片时**降级为只读**，不允许调用写工具。
5. **embedding 不跟着变**：bge-m3 是文本模型；多模态 embedding（CLIP / BGE-VL）维度不同，必须**独立集合 + 独立模型类型**（`model_type=4`），不能混进现有 collection。

#### 6.18.7 对已有设计的影响

| 已有设计 | 影响 |
| --- | --- |
| `ChatPort` / `StreamSink` | 不变（流式仍只回文本增量） |
| `Message` | 加 `parts`，`content` 保留为派生字段——唯一破坏性点，现在做是零成本 |
| `TokenCounter` | 加 `count(Message)` 重载 |
| `GuardrailPort` | L0 增加图片分支（§6.14.2） |
| `ModelListener` | `ModelCall.inputSize` 语义扩展为"文本条数 + 图片张数" |
| `OtlpModelListener` | 只报 `image.count` / `image.bytes` 属性 |
| `ai_model` 表 | 加 `capabilities` 列（与 §6.14 的 V6 一起做） |

#### 6.18.8 不做的事

| 不做 | 理由 |
| --- | --- |
| 现在做音频 / 视频 | Chat Completions 不支持音频输入（走独立 realtime API），视频更远 |
| 用多模态模型做 embedding | 维度不同、要独立集合与迁移，收益不明确 |
| 图片生成 | 问数不需要 |
| 任意文件直接进 prompt | 走 MCP 工具（读文件 → 文本）更可控 |

#### 6.18.9 验收（将来开的时候）

| 项 | 判定 |
| --- | --- |
| 带图 + 无 `vision` 能力 | 明确报 `INVALID_ARGS`，不静默丢图 |
| 四个协议各发一张图 | 服务端 200，且能读出图中数字（用"图里写着 42"的用例） |
| token 预算 | 图片请求的估算与真实 `usage` 偏差 < 30% |
| 日志 / OTLP | 搜不到 base64，也搜不到图片 URL |
| `multimodal.enabled=false` | 与现状逐字节一致 |

#### 6.18.10 语音与文件：边界适配器，不是 Pipeline 节点

**语音本质就是文本输入，只是前面多一个 STT。** 所以整条链是这样的：

```
音频 ──STT──► 文本 ──► Pipeline（完全不知道有语音）
文本 ──TTS──► 音频（Pipeline 之外的独立接口）
```

推论：`SpeechPort` **不进 Pipeline、不产帧、不影响帧序**——它是入口 / 出口的适配器；`ArtifactPort` 同理，导出是独立 HTTP 接口，不是 SSE 帧。

再往下推一步：**语音输出也不是一种输出模态**。TTS 的输入就是 `ANSWER` 产出的那段文本，音频只是这段文本的播放形式——问数的输出永远是三种产物：**文本 / 图表 URL / 文件 URL**。

| 模态 | 方向 | 走哪 | 位置 | 说明 |
| --- | --- | --- | --- | --- |
| 文本 | 入 / 出 | `ChatPort` | Pipeline 内 | 现状 |
| 图片 | 入 | `ChatPort`（`ContentPart`） | `GENERATE` | **唯一进 Pipeline 的非文本模态** |
| 语音 | 入 | `SpeechPort.transcribe`（ASR） | **Pipeline 之前** | 音频 → 文本，之后与打字提问完全同一条链路 |
| 语音 | 出 | `SpeechPort.synthesize`（TTS） | **Pipeline 之后** | 输入就是文本答案，输出音频给前端播放；不回灌 Pipeline |
| 图表 | 出 | `ChartRenderService`（外挂） | Pipeline 之后 | 只回 URL，不回字节 |
| 文件 | 出 | `ArtifactPort.export` | Pipeline 之后 | `GET /chat/{recordId}/export?format=xlsx` |
| 文件 | 入 | `FileParsePort` | `UNDERSTAND` | pdf / docx / pptx / xlsx → 文本 / 表格，三种归宿（§6.23） |

**四条结论**：

1. `PipelineState` 里只有 `attachments`（图片），**没有音频字段**——语音在进 Pipeline 之前已经变成 `question`。
2. **不新增 SSE 帧**：TTS 与导出都是独立接口，前端拿到结果后再调，帧契约零改动。
3. 语音失败只影响"能不能用语音提问"，不影响主链；ASR 挂了就返回"请用文字提问"。
4. **输出侧只有文本 / 图表 URL / 文件 URL 三种产物**，音频是文本的播放形式，不算新模态——所以 `ChatPort` 的输出契约永远不需要改。

##### SpeechPort（ASR / TTS）

```java
public interface SpeechPort {

    /** ASR：音频 → 文本。超时 60s；失败抛 PortError，由入口转成"请用文字提问"。 */
    String transcribe(CallContext ctx, AudioRef audio, String languageHint);

    /** TTS：文本 → 音频。超时 30s；失败只影响这一个接口。 */
    AudioRef synthesize(CallContext ctx, String text, String voice);
}

public final class AudioRef {

    public final String url;        // 存储地址，不放字节
    public final String mimeType;   // audio/webm / audio/wav
    public final int durationMs;
    public final long sizeBytes;
}
```

入口两种接法，二选一（推荐第二种）：

| 接法 | 说明 |
| --- | --- |
| `/chat/stream` 同时接受 `question` 与 `audio` | 前端只调一个接口；后端在 controller 里先 STT 再进 Pipeline |
| 独立 `POST /speech/asr` 返回文本，前端再调 `/chat/stream` | 职责清晰、可单独重试；代价是多一次往返 |

##### ArtifactPort（导出）

```java
public interface ArtifactPort {

    /** 结果集 → 文件（xlsx / csv / pdf），返回下载 URL。 */
    ArtifactRef export(CallContext ctx, ResultSetRef result, String format);
}
```

现有 `ExcelService` 与定时任务导出已经在做这件事，`ArtifactPort` 只是把入口收口，不重写。文件必须走签名 URL（与附录 A.1.3 图表 URL 同一套），TTL 与 `chat_record` 一致。

##### 四条约束

1. **字节不进 prompt / 日志 / OTLP**：语音与文件只记 `mimeType` / 大小 / 时长 / URL 摘要。
2. **语音可降级**：ASR 失败返回"请改用文字提问"，不报 500；TTS 失败只影响该接口。
3. **不进主链关键路径**：TTS 与导出都在 Pipeline 之外，主链只负责文本答案。
4. **帧契约零改动**：不新增 `artifact` 帧，避免老客户端困惑——独立接口比新帧更省事。

##### 验收

| 项 | 判定 |
| --- | --- |
| 语音提问 | ASR 成功 → 与文字提问走同一 Pipeline，帧序逐字节一致 |
| ASR 超时 | 明确提示改用文字，不 500 |
| TTS 失败 | 文字答案与主链不受影响 |
| 导出 xlsx | 返回签名 URL，未登录不可下载 |
| 关闭语音 / 导出 | 主流程帧与现状逐字节一致 |

### 6.19 通用流程与问数流程的边界：AUTH 是可选能力

端口层是**通用 AI 能力层**，它不知道"登录""工作空间""租户"。AUTH 只是问数流程的需求，不该被写进端口的前提条件。

#### 6.19.1 三层职责

| 层 | 是否知道 AUTH | 说明 |
| --- | --- | --- |
| 端口层（`ai.port`） | **不知道** | 只认 `CallContext`，字段可为 0；禁止读登录态 |
| 业务流程（问数） | 必须知道 | `AuthGuard` 是 Pipeline 第一阶段，负责 oid / uid / 数据源授权 / 行列权限 |
| 装配层 | 配置决定 | 有 AUTH 的部署装 `AuthGuard`，无 AUTH 的装 `NoAuthGuard` |

#### 6.19.2 租户字段允许为空

- `CallContext.oid=0` / `uid=0` 表示**无租户**：评估跑批、CLI、内部定时任务、MCP 直连。
- 端口实现**不得**因为 `oid==0` 就拒绝；需要会话隔离的端口（如 `MemoryStore`）用 `conversationId`，不用 oid。
- 反例（必须避免）：端口实现里调 `StpUtil.getLoginId()`——端口层禁止 import 任何鉴权库。

#### 6.19.3 四个真实场景，只有两个有 AUTH

| 场景 | 有 AUTH | 上下文来源 |
| --- | --- | --- |
| 问数 SSE | 有（Sa-Token 登录态） | oid / uid 从会话取 |
| MCP 直连（外部 Agent） | 有 token，但不是会话登录 | token → 受限 `CallContext`，oid 来自 token |
| 评估跑批 `JdbcEvalRunner` | 无 | `CallContext.system()`，oid=0 |
| CLI / 批处理 | 无 | 同上 |

#### 6.19.4 对现有代码的要求

- 现在权限、数据源授权、行列权限都在业务层——**这是对的，保持**。
- 但 `ChatModelRouter.defaultChatModel(oid)` 把"工作空间过滤"下沉到了模型选择，等于让 AI 层感知租户。建议改为：业务流程先按 oid 选出候选模型列表，再交给 `ChatPort`——端口只认 `ModelRef`，不认 oid。这样评估场景（无租户）能直接复用同一套端口。
- `AiModelService.listByWs` 这类"public 且不自校验"的方法（附录 B 已列），在新结构里应改成接收显式 `CallContext`，由调用方传，不读登录态。

#### 6.19.5 三条规则

1. **端口层禁止 import 鉴权 / 会话库**——与"禁止 import `dev.langchain4j` / `org.springframework.ai`"同一条 ArchUnit 检查。
2. **需要租户的地方一律从 `CallContext` 取**，缺省 0 = 无租户，不报错。
3. **AUTH 只在流程入口做一次**，端口层不做二次鉴权；工具级权限用 `ToolScope` 表达（空 scope = 无工具可用）。

#### 6.19.6 验收

| 项 | 判定 |
| --- | --- |
| `CallContext.system()`（oid=0）跑 embed / rerank / chat | 正常返回 |
| 端口包 grep `StpUtil` / `sa-token` | 为空 |
| 无 AUTH 部署启动 | 正常，`NoAuthGuard` 生效 |
| 问数流程去掉 AUTH | 明确拒绝，不能裸跑 |

### 6.20 Pipeline 阶段清单（24 个节点，可扩展）

把"一次提问"拆成显式阶段后，节点可以继续加——但必须守三条规则，否则会破坏帧序与可回放性。

> 本节是 **askdata pack 的具体映射**；抽象模型（7 阶段 + Step + 拦截器）见 §6.21，规范以 §6.21 为准。

#### 6.20.1 三条节点规则

1. **节点只传 `PipelineState`**：显式入参出参，不用 ThreadLocal；`PipelineState` 里带 `CallContext`、问题、召回结果、SQL、结果集、图表、用量。
2. **失败策略必须声明**：`FAIL_CLOSED`（终止并报错）/ `DEGRADE`（降级继续）/ `SKIP`（跳过当没发生）。权限与护栏一律 `FAIL_CLOSED`。
3. **新增节点不得改变已有节点的帧输出顺序**：SSE 帧序是 SQLBot 兼容契约（§6.2 帧层）。

#### 6.20.2 节点清单

| # | 节点 | 输入 → 输出 | 必需 | 帧 | 失败策略 |
| --- | --- | --- | --- | --- | --- |
| 0 | `DEBOUNCE` 去抖 / 幂等 | 请求 → 是否重复 | 否 | — | SKIP |
| 1 | `AUTHN` 认证 | token → 身份 | 是（问数） | — | FAIL_CLOSED |
| 2 | `AUTHZ` 授权 | 身份 + 数据源 → 可用范围 | 是 | — | FAIL_CLOSED |
| 3 | `QUOTA` 配额 | 身份 → 剩余额度 | 否 | `info` | FAIL_CLOSED |
| 4 | `SESSION` 会话加载 | chat_id → 历史 / 父记录 | 是 | — | FAIL_CLOSED |
| 5 | `NORMALIZE` 问题归一 | 原文 → 标准化问题 | 否 | — | SKIP |
| 6 | `INTENT` 意图 + 实体 | 问题 → `Intent` | 是 | `info` | DEGRADE（回退规则） |
| 7 | `TERM` 术语解析 | 实体 → 标准词 | 否 | — | SKIP |
| 8 | `RECALL` 召回 | 问题 + 实体 → 候选表/字段/语料 | 是 | — | DEGRADE（只留精确匹配） |
| 9 | `RERANK` 重排 | 候选 → 排序后候选 | 否 | — | SKIP |
| 10 | `CACHE` 结果缓存 | 问题指纹 → 命中结果 | 否 | — | SKIP |
| 11 | `COMPOSE` 上下文拼装 | 候选 + 历史 + 预算 → messages | 是 | — | FAIL_CLOSED |
| 12 | `PROMPT` 模板与版本 | messages + 模板 → 最终 prompt | 是 | — | FAIL_CLOSED |
| 13 | `GEN_SQL` 生成 | prompt → SQL JSON | 是 | `info: sql generating` + `sql` | 进入 REPAIR |
| 14 | `VALIDATE` 校验 | SQL → 通过 / 原因 | 是 | `info` | 进入 REPAIR |
| 15 | `EXECUTE` 执行 | SQL → 结果集 | 是 | — | 进入 REPAIR |
| 16 | `REPAIR` 有界修复 | 失败原因 + SQL → 新 SQL | 否（触发式） | `info` | FAIL_CLOSED（次数用尽） |
| 17 | `SHAPE` 结果整形 | 结果集 → 列 / 行 / 类型 | 是 | — | DEGRADE |
| 18 | `CHART` 图表 | 结果集 → 图表配置 | 否 | `chart` | DEGRADE（降级为表格） |
| 19 | `ANSWER` 回答 | 结果 + 图表 → 文本（流式） | 是 | 文本帧 + `finish_step` | FAIL_CLOSED |
| 20 | `GUARDRAIL_OUT` 输出护栏 | 文本 → 掩码后文本 | 否 | — | FAIL_CLOSED |
| 21 | `PERSIST` 落库 | 全部 → `chat_record` / `chat_log` | 是 | `record_id` | DEGRADE（只记错误日志） |
| 22 | `MEMORY` 写记忆 | 轮次 → L1 / L2 | 否（L2 异步） | — | SKIP |
| 23 | `OBSERVE` 收尾观测 | 全链路 → span / 指标 / 成本 | 是 | — | SKIP |

#### 6.20.3 三类节点

| 类型 | 节点 | 说明 |
| --- | --- | --- |
| 必需（主链） | 1、2、4、6、8、11、12、13、14、15、17、19、21、23 | 少一个就不是问数了 |
| 可选（默认关） | 0、3、5、7、9、10、18、20、22 | 必须有实现 + 开关（§6.13） |
| 触发式 | 16 `REPAIR` | 只在 13/14/15 失败时进，最多 `MAX_SQL_REPAIR_RETRIES` 次 |

#### 6.20.4 帧映射（兼容性硬约束）

| 节点 | 帧 |
| --- | --- |
| 3 / 6 / 14 / 16 | `{"type":"info","msg":"..."}` |
| 13 | `info: sql generating` → `{"type":"sql",...}` |
| 18 | `{"type":"chart",...}` |
| 19 | 文本增量帧 → `{"type":"finish_step",...}` |
| 21 | `{"type":"record_id",...}` |
| 任意节点失败 | `{"type":"error",...}` |

**新增节点只能在"不产帧"的位置插入**（0、5、7、9、10、17、22），或复用既有帧类型；新增帧类型必须能被 SQLBot 客户端忽略。

#### 6.20.5 还能加哪些节点（候选池）

| 候选 | 位置 | 价值 | 代价 |
| --- | --- | --- | --- |
| `TRANSLATE` 问题翻译 | 5 之后 | 中文问、英文库 | 多一次模型调用 |
| `CLARIFY` 反问澄清 | 6 之后 | 问题歧义时反问，而不是瞎猜 | 打断流式体验，需交互协议 |
| `PLAN` 多步计划 | 6 之后 | 复杂分析拆解（§6.17 agentic） | 延迟与成本不可控 |
| `IMAGE_UNDERSTAND` 图理解 | 6 之后 | 截图 / 表结构图转文本（§6.18.5） | 多一次模型调用，可降级 |
| `SAMPLE` 数据探查 | 11 之前 | 先看几行样本再写 SQL，提升准确率 | 多一次查询 |
| `VERIFY` 结果自检 | 17 之后 | 结果异常（全空 / 超量）时回炉 | 多一次模型调用 |
| `EXPLAIN` 解释 SQL | 17 之后 | 给业务解释口径 | 只影响展示 |
| `SUGGEST` 推荐追问 | 19 之后 | 已有 `streamRecommendQuestions` | 已在实现 |
| `AUDIT` 审计 | 21 之后 | 谁问了什么、查了哪张表 | 存储成本 |

**准入标准**：能说清"输入输出 + 失败策略 + 帧影响 + 关闭时的行为"，才允许进主链；否则只做独立入口。

#### 6.20.6 通用内核 vs 业务扩展：CHART 属于后者

24 个节点现在混了三种东西，必须分开——否则"通用 AI 能力层"会被问数业务污染。

| 域 | 节点 | 换业务时 |
| --- | --- | --- |
| **通用内核** | `DEBOUNCE` / `SESSION` / `QUOTA` / `CACHE` / `COMPOSE` / `PROMPT` / `GENERATE` / `ANSWER` / `GUARDRAIL_OUT` / `PERSIST` / `OBSERVE` | 不动 |
| **问数域** | `AUTHN` / `AUTHZ` / `INTENT` / `TERM` / `RECALL` / `RERANK` / **`GEN_SQL`** / `VALIDATE` / `EXECUTE` / `REPAIR` / `SHAPE` | 换成别的 domain pack |
| **呈现域** | `CHART` / `SUGGEST` | 可整体外挂 |

**CHART 其实是两件事，必须拆开**：

| 部分 | 现在 | 建议 |
| --- | --- | --- |
| 图表配置生成（模型产出 chart JSON） | `ChatQuestionService.generateChart`（`:1714`）内联在主链 | 域节点 `CHART_PLAN`，失败降级为表格 |
| 图片渲染（服务端画图） | `ChartImageService`（TTL 24h、`MAX_RENDER_ROWS=200`、集群锁） | **完全外挂**：异步任务 + 签名 URL，不进主链关键路径 |

理由：渲染是 CPU / 内存密集、有独立 TTL 与集群协调（`ChartImageClusterTest`），放进 SSE 主链会让首字延迟被渲染拖住；而且 MCP / IM / 导出复用同一个渲染服务（附录 A.1.3 的取图鉴权问题也在这里）。

**domain pack 机制**：

```java
public interface DomainPack {

    String name();                    // askdata / docqa / ...

    List<PipelineStep> steps();       // 该域独有的节点

    List<ToolSpec> tools();           // 该域内置工具
}
```

- 内核只认 `PipelineStep`，不认"问数"；
- 换业务 = 换一个 pack：`DocQaPack` 用 `RETRIEVE_DOC + CITE` 替掉 `RECALL + GEN_SQL`；
- `CHART_PLAN` 在 askdata pack 里，**`RENDER` 不在任何 pack 里**（外挂服务）。

**`GENERATE` 与 `GEN_SQL` 的区别**（同一处容易混）：

| 节点 | 做什么 | 归属 |
| --- | --- | --- |
| `GENERATE` | 调模型 + 流式回传，**产物格式由域决定** | 内核 |
| `GEN_SQL` | `GENERATE` + SQL JSON schema + 解析 + 失败进 `REPAIR` 环 | 问数域 |

也就是说：内核只知道"让模型输出点什么"，不知道"输出的是 SQL"。`DocQaPack` 里的对应节点叫 `GEN_ANSWER_WITH_CITES`，机制一样、产物不同。

**判定标准**：一个节点"换个业务就没有" → 域节点；"每个业务都要" → 内核节点。

#### 6.20.7 验收

| 项 | 判定 |
| --- | --- |
| 关掉全部可选节点 | SSE 帧与现状逐字节一致 |
| 单个节点失败 | 按声明的策略处理，不静默吞 |
| 节点可回放 | 给定 `PipelineState` 快照能重跑单个节点 |
| 新增节点 | 不改变既有帧顺序，且契约测试全绿 |
| `PipelineState` 无隐藏状态 | 不含 ThreadLocal、不含静态缓存 |

### 6.21 抽象模型：7 阶段 + Step + 拦截器（规范）

§6.20 的 24 个节点是 **askdata 的映射结果**，不是模型本身——换个业务就废了。真正要定的是三件套：**阶段（Stage）固定、步骤（Step）由业务包提供、拦截器（Interceptor）横切**。

#### 6.21.1 三个概念

| 概念 | 定义 | 数量 |
| --- | --- | --- |
| **Stage 阶段** | 一次 AI 请求的宏观阶段，与业务无关 | **7 个，固定** |
| **Step 步骤** | 阶段内的一次转换，有输入输出、失败策略、帧声明 | 由 domain pack 决定 |
| **Interceptor 拦截器** | 包裹每个 Step 的横切关注点 | 3 个（护栏 / 观测 / 配额） |

#### 6.21.2 七个阶段（固定，不随业务变）

| Stage | 语义 | askdata 的映射 |
| --- | --- | --- |
| `INTAKE` | 接住请求：去抖、确认身份与额度、加载会话 | `DEBOUNCE` / `AUTHN` / `AUTHZ` / `QUOTA` / `SESSION` |
| `UNDERSTAND` | 自然语言 → 结构化意图 | `NORMALIZE` / `INTENT` / `TERM` |
| `RETRIEVE` | 找上下文（表 / 文档 / 记忆） | `RECALL` / `RERANK` / `CACHE` |
| `GENERATE` | 调模型产出候选动作 | `COMPOSE` / `PROMPT` / `GEN_SQL` |
| `ACT` | 执行动作并处理失败 | `VALIDATE` / `EXECUTE` / `REPAIR` |
| `PRESENT` | 结果 → 人能看的输出 | `SHAPE` / `CHART` / `ANSWER` / `GUARDRAIL_OUT` |
| `PERSIST` | 落库与记忆 | `PERSIST` / `MEMORY` |

`OBSERVE` **不是阶段**，是拦截器——它包在每一步外面，不是流程里的一站。

#### 6.21.3 Step 契约（Java 8）

```java
public enum Stage { INTAKE, UNDERSTAND, RETRIEVE, GENERATE, ACT, PRESENT, PERSIST }

public enum StepKind { PURE, SIDE_EFFECT }

public enum FailurePolicy { FAIL_CLOSED, DEGRADE, SKIP }

public interface PipelineStep {

    String name();

    Stage stage();

    StepKind kind();

    FailurePolicy failure();

    /** 该步会产出的帧类型；空列表 = 不产帧。 */
    List<String> frames();

    void apply(PipelineState state);
}
```

- `PURE`：可重跑、可缓存、可并行（归一、意图、召回、整形）；
- `SIDE_EFFECT`：不可重跑（执行 SQL、落库、写记忆、发反馈）；
- `frames()` 让"帧序"从口头约定变成**可静态校验**的东西：按 stage 顺序跑完，帧序列必须与基线一致。

#### 6.21.4 拦截器

```java
public interface StepInterceptor {

    void before(PipelineStep step, PipelineState state);

    void after(PipelineStep step, PipelineState state, Throwable error);
}
```

三个内置：

| 拦截器 | 作用 | 关掉时 |
| --- | --- | --- |
| `GuardrailInterceptor` | 按 stage 选 L0 / L1 / L2 护栏（§6.14.2） | 只保留 `SqlSafety` |
| `ObserveInterceptor` | 每步一个 span + 指标 + token 累计 | 无 span |
| `QuotaInterceptor` | 累计 token / 调用次数，超限终止 | 不限额 |

#### 6.21.5 一个业务 = 一个 pack

```java
public interface DomainPack {

    String name();

    /** 按 stage 排序；同一 stage 内保持声明顺序。 */
    List<PipelineStep> steps();

    List<ToolSpec> tools();
}
```

| pack | 替换掉的阶段内容 |
| --- | --- |
| `AskdataPack` | 6.20 的 24 个节点 |
| `DocQaPack` | `RETRIEVE_DOC` / `CITE` 替掉 `RECALL` / `GEN_SQL` |
| `ReportPack` | `FETCH_METRIC` / `RENDER_REPORT` 替掉 `GEN_SQL` / `CHART` |

内核只认 `PipelineStep` 与 `Stage`，不认"问数"。

#### 6.21.6 五条可自动化的校验

| # | 规则 | 检查方式 |
| --- | --- | --- |
| 1 | 每个 stage 至少一个 step | pack 加载时校验 |
| 2 | 同 stage 内 `frames()` 顺序与基线一致 | 差分测试（关掉可选 step 再跑） |
| 3 | `SIDE_EFFECT` 不得声明可重试 | 静态检查 |
| 4 | step name 全局唯一 | pack 加载时校验 |
| 5 | 关闭任一可选 step，帧序列不变 | 契约测试 |

#### 6.21.7 与 §6.20 的关系

- **§6.21 是规范**：7 个阶段 + 3 类 Step + 3 个拦截器；
- **§6.20 是 askdata pack 的具体清单**：24 个节点是规范的一个实例；
- 新增业务只写 pack，不改内核——这是"通用"与"业务"之间唯一需要守住的边界。

#### 6.21.8 多模态在抽象里的位置

多模态不是"另一个流程"，而是 **`PipelineState` 多一个字段 + 两个可选步骤**。语音与文件更简单：它们是 **Pipeline 之外的边界适配器**，压根不进这张表。

| 层 | 多模态怎么进 |
| --- | --- |
| `PipelineState` | 除 `question: String` 外增加 `List<ContentPart> attachments`；`question` 仍是附件中 TEXT 的拼接（与 `Message.content` 同一规则） |
| `INTAKE` | 校验 `capabilities.vision`，没有则 `FAIL_CLOSED`（不静默丢图） |
| `UNDERSTAND` | 可选步骤 `IMAGE_UNDERSTAND`：图 → 文本描述 / 结构化，结果写回 `state`，后续步骤仍按文本处理 |
| `GENERATE` | 带图请求按四协议矩阵序列化（§6.18.3） |
| `PRESENT` | 输出侧只出 **URL**：`CHART_RENDER` 产出签名 URL，SSE 帧里放 URL，不放 base64 |
| `GuardrailInterceptor` | 含图请求降级为只读（§6.18.6 第 4 条） |
| `ObserveInterceptor` | 只记 `image.count` / `image.bytes` / `mimeType`，不记 URL |

三条推论：

1. **帧契约不变**：图片永远不出现在 SSE 帧里，只出 URL —— 帧层（§6.2）零改动。
2. **图理解可降级**：`IMAGE_UNDERSTAND` 失败 → `DEGRADE` 成纯文本继续，不影响主链。
3. **纯文本业务零成本**：`DocQaPack` / `ReportPack` 的 `PipelineState` 里 `attachments` 恒为空，代码不用改。

**语音与文件在 Pipeline 之外**（§6.18.10）：

| 模态 | 位置 | 对 Pipeline 的影响 |
| --- | --- | --- |
| 语音入（STT） | Pipeline 之前，音频 → `question` | 无：进来的已经是文本 |
| 语音出（TTS） | Pipeline 之后，`POST /speech/tts` | 无：独立接口，不产帧 |
| 文件导出 | Pipeline 之后，`GET /chat/{id}/export` | 无：独立接口，不产帧 |

所以 `PipelineState` 里**只有 `attachments`（图片），没有音频字段**；文件输入在 `UNDERSTAND` 阶段被解析成 `state.documents` / `state.tables`（§6.23），之后与普通文本 / 数据源一致。

---
### 6.22 文本输入：归一、边界与锚定

文本是唯一"默认存在"的模态，所以最容易被忽略——现在也确实只有一句 `trim()`。

#### 6.22.1 现状：只有 trim + 空判

| 事实 | 证据 |
| --- | --- |
| 只判 `null` / `trim().isEmpty()` | `ChatQuestionService:251`、`:1870` |
| **没有长度上限** | 只有输出侧 `MAX_GENERATED_TEXT = 32000`（`:72`） |
| 没有 Unicode 归一 | 全角数字、零宽字符、控制符可直达 prompt |
| 归一不一致 | 只有少数路径做 `replaceAll("\\s+", " ")`（`:1873`、`:1885`） |
| 没有语言检测 / 时间锚定 | 全靠模型自己猜 |

#### 6.22.2 五类边界

| 类 | 规则 | 失败策略 |
| --- | --- | --- |
| 空 / 纯符号 | 去掉空白与不可见字符后为空 | `FAIL_CLOSED`（提示"请输入问题"） |
| 长度 | 字符 ≤ 2000，token ≤ 2000（走 `TokenCounter`） | `FAIL_CLOSED`（提示"问题过长，请精简"），**不静默截断** |
| 编码 | Unicode NFKC；全角 → 半角（数字、标点、字母）；去零宽（U+200B–200D、U+FEFF）；去控制符（保留 `\n` `\t`） | 自动 |
| 空白 | 折叠连续空白，保留段落 | 自动 |
| 语言 | 检测 zh / en / ja / ko / th（与 `messages_*` 对齐） | 默认 zh |

#### 6.22.3 三种特殊形态：识别，不要瞎猜

| 形态 | 例子 | 处理 |
| --- | --- | --- |
| 粘贴表格 | 制表符 / 管道分隔的多行 | 标记 `inline_table`，**不当 schema**；提示"已识别到表格数据，将按文本理解" |
| 粘贴 SQL | `SELECT ...` | 有执行权限 → 走"直接执行"分支（跳过 `GEN_SQL`），仍必须过 `SqlSafety` |
| 多问题 | "A？B？" | 不自动拆，交 `INTENT` 判断——拆了会让帧序与记录变复杂 |

#### 6.22.4 相对时间必须锚定

"上个月""近 7 天""今年"这类表达，模型会自己算，**算错就是错数据**。

- 在 `NORMALIZE` 阶段解析成**绝对区间 + 时区**，写进 `state.timeRange`；
- prompt 里显式给出："今天是 2026-11-02，时区 Asia/Shanghai，'上个月' = 2026-10-01 ~ 2026-10-31"；
- 解析不出就留空让模型判断，但**日志记一条**，便于事后排查"为什么查错区间"。

#### 6.22.5 注入面：归一必须在护栏之前

- 文本里的"忽略以上指令"、`system:`、成块 base64、伪造的 `<tool_result>` 标签 → 护栏 L0 拦截（§6.14.2）；
- **顺序不能反**：先归一，再护栏。否则 `忽\u200b略` 这种零宽拆分能绕过关键词匹配；
- 归一后的文本才是护栏输入，也是日志与回放的内容。

#### 6.22.6 位置与实现

- 一个 `PURE` 步骤 `NORMALIZE`（§6.20.2 第 5 节点），**不需要新端口**——纯函数，没有可替换的实现；
- 输出写回 `PipelineState`：`question` / `timeRange` / `language` / `flags`；
- 必须幂等：同一输入跑两次结果完全一致（可缓存、可回放）。

#### 6.22.7 验收

| 项 | 判定 |
| --- | --- |
| 全角数字 "１２３" | 归一为 "123"，SQL 里不出现全角 |
| 零宽字符 "忽\u200b略" | 归一后护栏能命中 |
| 3000 字问题 | 明确拒绝并提示，不截断执行 |
| "上个月" | `timeRange` = 上月首末日，prompt 里带绝对区间 |
| 粘贴 SQL | 走直接执行分支，仍过 `SqlSafety` |
| 同一输入两次 | `NORMALIZE` 输出逐字节一致 |

### 6.23 文件输入：Office / PDF / CSV

#### 6.23.1 三种归宿，别混

| 文件类型 | 归宿 | 路径 |
| --- | --- | --- |
| 纯文本（txt / md / 小 csv） | 直接进 prompt | 解析 → 文本 → `NORMALIZE` |
| 文档（pdf / docx / pptx） | 变成知识（检索） | 解析 → 分块 → 向量 → `RETRIEVE` |
| 表格（xlsx / csv） | 变成数据源（问数） | 解析 → 建临时表 → 走 `GEN_SQL` |

关键：**文件不是"塞进 prompt 的字符串"**，而是三种不同归宿。混着做，不是撑爆上下文，就是让问数查不到。

#### 6.23.2 解析抽象：直接复用 langchain4j 的 `DocumentParser`

langchain4j 1.23 把文档解析做成了三个独立 artifact，接口只有一个方法：

```java
// dev.langchain4j.data.document.DocumentParser
Document parse(InputStream in);   // 只要一个流，不要求文件路径

// dev.langchain4j.data.document.Document
String text();                    // 纯文本
Metadata metadata();              // 键值元数据
```

| artifact（1.23.0-beta33） | 实现类 | 底层依赖 | 字节码版本 | 依赖体积 |
| --- | --- | --- | --- | --- |
| `langchain4j-document-parser-apache-tika` | `ApacheTikaDocumentParser` | tika-core + tika-parsers-standard-package 3.3.3 | **Java 11**（major 55） | ~63MB |
| `langchain4j-document-parser-apache-poi` | `ApachePoiDocumentParser` | poi / poi-ooxml / poi-scratchpad 5.5.1 | Java 8（major 52） | 项目已有 |
| `langchain4j-document-parser-apache-pdfbox` | `ApachePdfBoxDocumentParser` | pdfbox 3.0.8 | Java 8（major 52） | ~10MB |
| 三个 artifact 本体 | — | 只含适配类 | **Java 17**（major 61） | 各几 KB |

**结论先行**：抽象用 `DocumentParser`（一个方法、一个产物），实现用 Tika。但这套类整体是 Java 17 字节码，**Java 8 分支用不了**，只能照同一形状自己包一层（见 §6.23.3）。

**实测（本机 JDK 17，2026-11-02，四个样例文件自建后解析）**

| 文件 | POI 解析器 | PDFBox 解析器 | Tika 解析器 |
| --- | --- | --- | --- |
| docx 2.4KB | ✅ 46 字 / 48ms | ❌ `RuntimeException: Error: End-of-File` | ✅ 48 字 / 739ms（首次含初始化） |
| xlsx 3.4KB | ✅ 42 字 / 25ms | ❌ 同上 | ✅ 47 字 / 146ms |
| pptx 25KB | ✅ 17 字 / 79ms | ❌ `Missing root object specification in trailer` | ✅ 19 字 / 232ms |
| pdf 891B | ❌ `Can't create extractor - unsupported file type: PDF` | ✅ 33 字 / 62ms | ✅ 36 字 / 127ms |

四条实测结论（都是设计约束，不是细节）：

1. **三个实现都只出纯文本，没有结构。** docx 表格变成 `区域\t金额\n华东\t800`；xlsx 变成 `销售\n区域\t月份\t金额\n...`。想拿表格去问数，仍必须走 POI 单元格 API（`ExcelService` 已有），**不能拿 `text()` 反解行列**。
2. **传错解析器抛的是 `RuntimeException`**（内包 `IOException`），不是受检异常，也没有"不支持的类型"专门异常。所以必须按 mime/扩展名先路由，并且 `catch (RuntimeException)`，否则一次错传就是 500。
3. **`metadata()` 默认是空 `{}`。** 文件名、大小、来源都要调用方自己塞（`Document.FILE_NAME` 常量存在，但解析器只拿到 `InputStream`，不会填）。
4. **空文档抛 `BlankDocumentException`。** 这正好是"扫描版 PDF 解析成功但没文字"的判定信号，要接住并转成"未 OCR"的明确提示，不能当异常吞掉。

两个附带发现：

- `new ApacheTikaDocumentParser(true)` 打开的元数据含 `X-TIKA:Parsed-By`、`dc:creator`、`Content-Type` 等一堆 Tika 内部键，**默认必须关**；只想要 Title/Author 时 PDFBox 的 `(true)` 更干净（实测 `{Author=analyst, Title=Q1 Report}`）。
- Tika 首次调用 739~1354ms（解析器注册表初始化），之后 37~386ms。**不要在请求路径上首次加载**，启动时 warmup 一次。

#### 6.23.3 两条分支的实现选择（Java 8 与 Java 17）

| 分支 | 抽象 | 实现 | 原因 |
| --- | --- | --- | --- |
| Java 8（现在） | 自建 `FileParsePort`（形状同 `DocumentParser`） | Tika 2.9.4，或 POI + PDFBox | langchain4j 解析器 major 61；Tika 3 major 55，两者都用不了 |
| SB4 / Java 17 | `FileParsePort` 适配 `DocumentParser` | `ApacheTikaDocumentParser` 3.3.3 | 委托框架，不自研 |

`FileParsePort` 与 `DocumentParser` 形状一致，**迁移时只换实现类，调用方零改动**：

```java
public interface FileParsePort {

    ParsedFile parse(CallContext ctx, FileRef file, ParseOptions options);
}

public final class ParsedFile {

    public final String text;              // 纯文本，保留段落
    public final List<TableData> tables;   // 仅 POI 路径能填；Tika 路径为空
    public final int pageCount;
    public final List<String> warnings;    // 如"第 3 页是扫描件，未 OCR"
}

public final class FileRef {

    public final String url;        // 存储地址
    public final String name;
    public final String mimeType;
    public final long sizeBytes;
    public final String sha256;
}
```

路由表（按扩展名 + mime 双判，判不出直接拒绝）：

| 扩展名 | Java 8 实现 | Java 17 实现 |
| --- | --- | --- |
| `docx` / `pptx` / `xlsx` | POI（`ExtractorFactory` 或单元格 API） | `ApachePoiDocumentParser` |
| `pdf` | PDFBox 3.0.8 | `ApachePdfBoxDocumentParser` 或 Tika |
| `txt` / `md` / `csv` | 直读 | 直读 |
| `doc` / `xls` / `ppt` | POI + poi-scratchpad | `ApachePoiDocumentParser` |
| 其它 | 拒绝（不支持） | Tika 兜底，失败即拒绝 |

**表格永远走 POI 单元格 API**，不走 `text()`——这是实测结论 1 的直接推论。

#### 6.23.4 依赖冲突（必须先解决，否则升级即炸）

实测 `mvn dependency:tree`：项目**已经**有 `org.apache.tika:tika-core:2.4.1`，来自 `x-file-storage-core:2.2.1` 的传递依赖（用途是内容类型探测，`FileStorageTestFactory` 里的 `setTikaContentTypeDetect`）。

三个约束叠在一起：

1. langchain4j 的 Tika 解析器要 `tika-core 3.3.3` + `tika-parsers-standard-package 3.3.3`；
2. Tika 3.x 是 **Java 11 字节码**（实测 major 55），Java 8 分支直接排除；
3. Tika 3 与 2 有 API 变更，升上去要回归 x-file-storage 的内容类型探测。

还有一个反直觉的实测结果：**`tika-parser-microsoft-module` 本身依赖 POI 5.5.1，`tika-parser-pdf-module` 依赖 PDFBox 3.0.8**。也就是说"用 Tika 统一解析 Office/PDF"并不省依赖，反而把项目锁定的 POI 5.2.3 顶到 5.5.1。我按精简集实测过（排除 `tika-parsers-standard-package`，只留 microsoft + pdf 两个模块）：依赖 63MB → 53MB，docx/xlsx/pptx/pdf 四种格式全部解析成功——**省得不多，但版本顶升照样发生**。

选型规则：

| 场景 | 选择 |
| --- | --- |
| Java 8 分支，只要 Office | POI 5.2.3 直读（已有），不引 Tika |
| Java 8 分支，要 PDF | PDFBox 3.0.8（Java 8，~10MB），不引 Tika |
| Java 8 分支，要"什么格式都能读" | Tika 2.9.4（实测 major 52），接受 tika-core 2.4.1 → 2.9.4 小版本升级 + 回归 |
| SB4 / Java 17 分支 | `langchain4j-document-parser-apache-tika`，接受 POI 5.5.1 顶升 + 回归 |

**红线**：无论走哪条，POI 版本顶升后必须回归 `ExcelService` / `ChatExcel` / `ExcelReadSupport` 三处（Excel 导入建数据源、导出、读取工具）。

#### 6.23.5 三个归宿

| 归宿 | 触发条件 | 处理 |
| --- | --- | --- |
| 进 prompt | 解析后 ≤ 2000 token | 拼进 `COMPOSE` |
| 进 RAG | 文档类且超长 | 分块（500 字 / 块，重叠 50）→ 向量 → `RETRIEVE` |
| 进数据源 | 表格类 | 建临时表 `tmp_upload_<id>` → 注册为动态数据源 → 走 SQL 链路；TTL 24h |

"上传 Excel 问数"是最自然的需求，但**它等于开了一个临时数据源**——必须过 §6.14 护栏与权限（谁能看这张临时表）。

#### 6.23.6 五条安全约束

1. **不信任文件内容**：PDF / Word 里可以藏"忽略以上指令"，解析后的文本同样要过护栏 L0；表格单元格值也是注入面。
2. **限制类型与大小**：mime + 扩展名双校验；单文件 ≤ 20MB；解析超时 30s；页数 ≤ 200。
3. **防 zip bomb / 宏**：docx / xlsx 本质是 zip，限制解压后总大小；拒绝 `docm` / `xlsm`。
4. **原始字节不进日志**：只记 `sha256` + 大小 + 页数。
5. **权限与 TTL**：归属 `oid + uid`；临时表与文件同 TTL（24h）；取文件走签名 URL。

#### 6.23.7 与现有能力的关系

| 已有 | 复用方式 |
| --- | --- |
| `ExcelService`（导入 Excel 建数据源） | 文件输入的上传 / 建表路径直接复用，不重写 |
| `x-file-storage` | 存储与签名 URL；其 Tika 2.4.1 传递依赖是版本冲突的来源 |
| POI 5.2.3 | docx / xlsx / pptx 解析与单元格读取 |

缺的是 **PDF 解析**（PDFBox 需新增依赖）、**分块 + 向量化**的接入点，以及"格式 → 解析器"的路由层。

#### 6.23.8 位置与开关

- 放在 `UNDERSTAND` 阶段的一个 `PURE` 步骤 `FILE_PARSE`，产物写 `state.documents` / `state.tables`；
- 开关 `askdata.ai.file-input.enabled`，默认关；
- 关闭时：上传接口不注册，主链零变化。

#### 6.23.9 验收

| 项 | 判定 |
| --- | --- |
| 上传 10 页 PDF | 解析出文本与页数，分块进 RAG，能检索到 |
| 上传 xlsx | 建临时表，能对它问数，24h 后消失 |
| docx / pptx / pdf / xlsx 四种格式 | 全部解析成功（本次实测脚本可直接复跑） |
| 用 PDFBox 解析 docx | 返回"不支持的类型"，不是 500 |
| 扫描版 PDF | 明确提示"未 OCR，无法读取"，不静默空结果 |
| 空文档 | `BlankDocumentException` 转成"文件里没有可读文字" |
| 文件里藏"忽略以上指令" | 护栏命中，不改变行为 |
| 30MB 文件 | 拒绝并提示上限 |
| POI 版本顶升后 | Excel 导入 / 导出 / 读取三处回归通过 |
| 关闭文件输入 | 上传接口不注册，主流程帧不变 |

### 6.24 对外契约：REST + MCP

对外只有两个门：**REST** 和 **MCP**。两者必须共用同一套内核，否则会出现"网页能查、MCP 查不到"的偏差。

#### 6.24.1 现状（代码证据）

| 项 | 现状 | 问题 |
| --- | --- | --- |
| REST 前缀 | 多数 `@RequestMapping("api/v1")` | `ChartImageController` 用 `/api/chart/image`，不统一 |
| 错误体 | `{code, data, msg}`（SQLBot 契约） | 没有 `requestId`，排障要靠时间戳猜 |
| 幂等 | 无 | 重试 / 双击会重复提问、重复扣费 |
| MCP 错误 | `errorJson(...)` 包成**正常结果**返回 | MCP 有 `isError` 字段却没用，客户端无法区分成功与失败 |
| MCP 返回 | 纯 JSON 字符串 | 没有结构化内容，也没有分页 |
| 鉴权 | REST 走 Sa-Token；MCP 走 token；`/mcp*` 在白名单由 MCP 层自校验 | 两套鉴权，口径容易漂 |

#### 6.24.2 一个内核，两个 adapter

```
REST Controller ──┐
                  ├─► PublicChatService ──► Pipeline ──► Port
MCP Tools ────────┘
```

**铁律**：对外接口不许各写一套业务逻辑。现在 `SqlbotMcpCompatController` 与 `AskdataMcpTools` 各自拼装，正是偏差的来源。

#### 6.24.3 REST 契约（8 条）

1. 前缀统一 `/api/v1`；`ChartImageController` 迁过来，旧路径保留 301 一周期。
2. 错误体保持 `{code, data, msg}`（SQLBot 契约不能动），**附加 `requestId`**——老客户端忽略即可。
3. **幂等**：`Idempotency-Key` 头，24h 内同 key 返回首次结果，不重复执行。
4. **限流**：按 API Key / oid 双维度，与配额共用计数器。
5. **流式**：`text/event-stream`；同时支持 `?stream=false` 同步返回（脚本与 MCP 用）。
6. **长任务**（报告 / 导出）：`202 + job_id`，`GET /jobs/{id}` 轮询。
7. 分页统一 `page/size`，返回 `{items, total}`。
8. 版本：破坏性变更开 `/api/v2`，`v1` 至少保留两个版本周期。

#### 6.24.4 MCP 契约（7 条）

1. **错误用协议语义**：`isError=true` + 文本，不再把错误包成 JSON 字符串。
2. 返回**结构化内容** + 文本兜底，不要只回字符串。
3. **工具名稳定**：新增不改老工具名与参数；破坏性变更 = 新工具名（`chat_start_v2`）。
4. 参数 schema 显式（required / optional），与 `ToolSpec.inputSchema` 同源（§6.15.2）。
5. **长任务轮询**：`chat_start` 返回 `chat_id`，再提供 `chat_status` / `chat_result`——MCP 没有流式。
6. **鉴权绑定 `oid + uid + scope`**：工具可见性由 `ToolScope` 过滤（§6.15.6），看不到比拒绝更好。
7. **审计**：每次 tool call 记 `tool_call_log`（谁 / 何时 / 什么工具 / 耗时 / 结果大小）。

#### 6.24.5 两者共享的横切

| 关注点 | REST | MCP |
| --- | --- | --- |
| 认证 | Sa-Token / API Key | token |
| 授权 | 同一套 `AuthzPort` | 同 |
| 限流配额 | 同一计数器 | 同 |
| 幂等 | `Idempotency-Key` | tool call id |
| 观测 | `traceparent` 透传 | 请求 id |
| 错误 | `{code, msg, requestId}` | `isError` + 文本 |

#### 6.24.6 不新增端口

对外契约层只是 adapter：

- `PublicChatService`（REST 与 MCP 共用）→ 调 `Pipeline` / `ChatPort`；
- MCP 暴露的工具 = `ToolPort` 的一个 provider，工具定义复用 `ToolSpec`，不另写一套 schema。

#### 6.24.7 验收

| 项 | 判定 |
| --- | --- |
| REST 错误 | 返回 `{code, msg, requestId}`，HTTP 状态与 code 对应 |
| 同 `Idempotency-Key` 重放 | 返回首次结果，不重复执行 |
| MCP 工具失败 | `isError=true`，客户端能区分 |
| MCP 与网页同一问题 | 结果一致（同一条 Pipeline） |
| 限流 | 超限返回 429 + `Retry-After` |
| 关闭 MCP | `/mcp` 不注册，REST 不受影响 |

#### 6.24.8 不做的事

| 不做 | 理由 |
| --- | --- |
| GraphQL | 多一层查询解析，收益低 |
| MCP 写工具 | 默认只读，写操作走 REST 审批流 |
| 对外 WebSocket | SSE 够用，且帧契约已经稳定 |
| 为 REST / MCP 各维护一套业务逻辑 | 偏差的来源，必须共用 `PublicChatService` |

### 6.25 嵌入方式：iframe / 免登录 token（第三种对外形态）

对外其实是三条路：**REST、MCP、嵌入**。前两条已写，嵌入这条在代码里早就有，但一直没进设计文档。

#### 6.25.1 现状：已有实现（证据）

| 组件 | 作用 |
| --- | --- |
| `EmbeddedController` | `/api/v1/system/embedded` 嵌入配置 CRUD |
| `AssistantPublicController` | 免登录助手入口 |
| `EmbeddedSignatureVerifier` | AES/GCM 信封 token：`assistant_id` / `target` / `sign_time` / `webdriver` / `user_agent` / `timezone`；**±10s 窗口**；Origin / UA / 时区三重校验；密钥可轮换 |
| `EmbeddedSigner` | 宿主侧签发 |
| 请求头 | `SQLBOT-EMBEDDED-TOKEN` |

这套信封比多数产品严格（带 UA 与 webdriver 校验），可以沿用，不用推翻。

#### 6.25.2 三种嵌入形态

| 形态 | 做法 | 适用 | 代价 |
| --- | --- | --- | --- |
| **iframe** | 宿主页嵌 `<iframe src="/assistant/{id}">` + token | 最快接入、样式隔离 | 双向通信受限，白标难 |
| **JS SDK** | 引脚本，SDK 内部 iframe + `postMessage` | 需要深度定制 | SDK 要维护版本 |
| **服务端代理** | 宿主后端调 REST，前端只拿结果 | 不想暴露 askdata 域名 | 宿主承担鉴权与转发 |

#### 6.25.3 token 设计：沿用，补两点

现有信封已经做了 ±10s、Origin、UA、时区、webdriver 五项校验。补两点：

1. **一次一用**：10 秒窗口内同一 token 可重放——加 `nonce` + 服务端短期去重（Redis 10s）。
2. **会话绑定**：token 里加 `session_id`（宿主侧会话），换会话必须换 token，否则一个 token 能在窗口内发起多次提问。

#### 6.25.4 身份与租户

- 宿主用户 → askdata `uid`：由 `assistant_id` 绑定的映射决定，**不接受 token 里直接给 uid**（否则可伪造越权）。
- `oid` = 助手所属工作空间，**token 里不出现**。
- 每次请求：解 token → 查助手 → 得到 `oid` + 数据源范围 + 权限模板。

#### 6.25.5 安全缺口（要补）

| 缺口 | 现状 | 修法 |
| --- | --- | --- |
| **点击劫持** | 全仓 grep 不到 `CSP` / `X-Frame-Options` | 对嵌入页下发 `Content-Security-Policy: frame-ancestors <宿主白名单>` |
| postMessage 校验 | 待确认 | `event.origin` 必须与信封 `target` 严格相等，禁止 `*` |
| token 泄漏 | 若放 URL query 会进日志 / Referer | 只用请求头或 `postMessage` 传递，**禁止放 URL** |
| 助手越权 | 依赖 `assistant_id` 查库 | 查不到 / 已停用 → 403，不做兜底 |
| 配额 | 无 | 按 `assistant_id` / `oid` 计，嵌入流量单独统计 |

#### 6.25.6 与 REST / MCP 的关系

嵌入不是第三种内核，而是 **REST 的另一种消费方式**：

```
iframe / JS SDK ──► AssistantPublicController ──► PublicChatService ──► Pipeline
```

`AssistantPublicController` 只做"解 token + 定身份 + 转发"，不写业务逻辑——与 §6.24 的 `PublicChatService` 收口是同一条规则。

#### 6.25.7 验收

| 项 | 判定 |
| --- | --- |
| 无 token 访问嵌入页 | 401 |
| token 超过 10s | 401 |
| Origin 与 `target` 不符 | 401 |
| 同 token 重放两次 | 第二次 401（nonce 去重） |
| 非白名单域名 iframe 嵌入 | 浏览器被 CSP 拦截 |
| 嵌入提问 | 与网页提问走同一 Pipeline，结果一致 |
| 关闭嵌入 | `/system/embedded` 与公开入口都不注册 |

#### 6.25.8 不做的事

| 不做 | 理由 |
| --- | --- |
| 自研 JS 框架 | 现有前端直接 iframe 足够 |
| token 里带 `uid` | 可伪造，身份必须由服务端查表得出 |
| 嵌入页开放写操作 | 默认只读问答 |
| 多助手共享一个 token | 无法审计，也难撤销 |

### 6.26 模型故障转移：单点、欠费与动态切换

#### 6.26.1 现状：只有一个模型，而且失败不可分辨

| 事实 | 证据 |
| --- | --- |
| 默认模型只返回一个 | `ChatModelRouter.defaultChatModel(oid)` → `Optional<ModelRef>`（`ChatModelRouter:152`）；`ai_model` 只有 `default_model` 布尔，无优先级、无备用字段 |
| 失败被压成一句字符串 | `streamCompletionOpenAi` 里 `code >= 400` → `handler.onError("模型调用失败 HTTP " + code + ...)`（`ChatModelClient:238-241`）；`catch (Exception)` → `onError("模型调用失败: " + msg)`（`:243-246`） |
| 调用方拿不到状态码 | `TokenHandler.onError(String)` 只有字符串；`generate(...)` 失败返回 `null`（`:642`），与"模型返回空"不可区分 |
| 无重试、无熔断、无备用 | `ChatModelClient` 全文无 retry / breaker；`ChatModelRouter` 无候选列表 |
| 一次卡死要 10 分钟 | `READ_TIMEOUT_MS = 600000`（`ChatModelClient:46`） |

欠费（402 / 403 `insufficient_quota`）今天表现为"模型调用失败 HTTP 403: {...}"字符串：用户看到"回答失败"，运营看不出是欠费，也没有备用模型顶上。

#### 6.26.2 先分类：哪些错能转移

| 上游表现 | ErrorKind | 同模型重试 | 转移 | 备注 |
| --- | --- | --- | --- | --- |
| 402 / 403 `insufficient_quota` / 余额不足 | `QUOTA_EXHAUSTED` | ❌ | ✅ 立即 | 欠费不会自愈，长冷却 |
| 401 invalid api key | `UNAUTHORIZED` | ❌ | ✅ 换 key 优先 | 同模型换 key，不换模型 |
| 429 rate limit | `RATE_LIMITED` | ✅ 1 次（读 `Retry-After`） | ✅ | 退避后仍 429 再转移 |
| 5xx / 502 / 503 / 504 | `UNREACHABLE` | ✅ 1 次 | ✅ | — |
| 连接超时 / DNS / reset | `TIMEOUT` / `UNREACHABLE` | ✅ 1 次（短超时） | ✅ | 首次用 8s 连接超时 |
| 400 `context_length_exceeded` | `INVALID_ARGS` | ❌ | ⚠️ 仅转到大窗口模型 | 需 `capabilities` 声明 |
| 400 其它（参数错） | `INVALID_ARGS` | ❌ | ❌ | 换模型也一样错 |
| 内容审核拒绝（`content_filter`） | `CONTENT_FILTERED` | ❌ | ❌ | 换模型只会再被拒，还可能绕过审核 |
| 已出首帧后断连 | — | ❌ | ❌ | 已提交，见 §6.26.4 |

`ErrorKind` 从 8 值扩到 10 值：新增 `QUOTA_EXHAUSTED`、`CONTENT_FILTERED`（§6.16 已同步）。**这是本节的前提**——欠费必须能被程序识别，靠字符串匹配就是现状的病根。

#### 6.26.3 候选链

```text
PINNED(id) / AUTO
  ├─ 该模型
  │   ├─ 同模型其它 key（ai_model_key，按 weight 加权）
  │   ├─ 同供应商其它模型（fallback_priority 升序）
  │   └─ 全局备用模型（可跨供应商，需能力匹配）
  └─ 全部失败 → PortError(QUOTA_EXHAUSTED / UNAUTHORIZED / ...) + attempts
```

三条硬约束：

1. 候选必须对当前 `oid` 可见（`aiModelRepository.isAvailableInWorkspace`），不能因为"是备用"就跨租户。
2. embedding 模型**不参与** chat 转移，反之亦然；向量模型切换要走索引重建，不是运行时转移。
3. 跨供应商转移要校验 `capabilities`：`vision` / `tools` / `json` 缺一不可（与 §6.18 的多模态声明共用同一字段）。

#### 6.26.4 只有"首帧前"能转移

这是最关键约束，也是最容易翻车的地方。

- 流式一旦吐了第一个 token，前端已渲染半句；换模型接着写会风格断层、事实冲突；上游也已计费。
- 所以定义 **commit point**：首个 `content` 回调之前 = 可转移；之后 = 已提交，只能终止。

```java
/** 只有 committed=false 时才允许换模型重放。 */
final class FailoverSink implements StreamSink {

    private final StreamSink delegate;

    private boolean committed;

    @Override
    public void onToken(String content, String reasoning) {
        committed = true;                 // 首个 token 即提交
        delegate.onToken(content, reasoning);
    }

    boolean canFailover() {
        return !committed;
    }
}
```

代价：首字延迟要等首帧才算成功。缓解：连接超时 8s（已有）+ **首帧超时 15s**（新增，独立于 `ChatPort` 默认 deadline）。

对非流式 `generate`（意图识别、图表类型判定）无此限制，可自由转移。

#### 6.26.5 熔断与冷却

| 状态 | 进入条件 | 行为 |
| --- | --- | --- |
| `CLOSED` | 正常 | 正常调用，累计失败 |
| `OPEN` | 连续 3 次失败，或 10 分钟窗口失败率 > 50% 且样本 ≥ 5 | 直接跳过，不再发请求 |
| `HALF_OPEN` | 冷却结束 | 放 1 个探测请求；成功 → `CLOSED`，失败 → `OPEN` 并加倍冷却 |

冷却时长按错误类型分——欠费与限流不能一个待遇：

| ErrorKind | 冷却 |
| --- | --- |
| `QUOTA_EXHAUSTED` | 30 分钟 |
| `UNAUTHORIZED` | 30 分钟 |
| `RATE_LIMITED` | 60 秒 |
| `UNREACHABLE` | 30 秒 |
| `TIMEOUT` | 30 秒 |
| `PROTOCOL` | 5 分钟 |

状态键 = `modelId` + `keyId`。落库 + 本地缓存 5 秒，多实例共享（与 §6.11.9 的"多实例一致性"同一条路）；否则实例 A 熔断、实例 B 还在打挂掉的模型。

#### 6.26.6 密钥多值：欠费时最常见的自救

```sql
CREATE TABLE ai_model_key (
    id             BIGINT PRIMARY KEY,
    model_id       BIGINT        NOT NULL,
    api_key_cipher VARCHAR(4000) NOT NULL,   -- 复用 CryptoUtil 加密
    label          VARCHAR(64),
    weight         INT           NOT NULL DEFAULT 100,
    status         BOOLEAN       NOT NULL DEFAULT TRUE,
    fail_count     INT           NOT NULL DEFAULT 0,
    last_ok_at     BIGINT,
    last_fail_at   BIGINT
);
```

- 选择：按 `weight` 加权轮询；同一次请求内失败不重复选同一把 key。
- `401` → 该 key `status=false` + 30 分钟冷却（同模型换下一把，**不换模型**）。
- `429` → 换下一把 key 立即重试，不转移模型。
- `402` → 换 key；全部 key 都 402 才转移模型。
- 加解密沿用 `ChatModelRouter.toRef` 里的 `CryptoUtil.resolvePlain`，不另起一套。

#### 6.26.7 探活：把欠费从事后变事前

| 方式 | 频率 | 成本 | 用途 |
| --- | --- | --- | --- |
| 主动 | 默认模型 + 备用模型，每 30 分钟 1 次 `max_tokens=1` 的 ping | 可忽略 | 提前发现欠费 / 失效 |
| 被动 | 每次真实调用 | 0 | 立即更新状态 |

- 探活失败只写 `ai_model.valid = false`，**不动 `enable`**（与 P0 #6 同一条规则：`enable` 是人的开关，`valid` 是事实）。
- 探活也花钱，所以只探默认 + 备用，不探全部模型。
- 探活与业务线程隔离，复用 §6.11.9 的健康度任务，不新开调度器。

#### 6.26.8 动态切换的四个入口

| 入口 | 场景 | 实现 |
| --- | --- | --- |
| 自动故障转移 | 上游故障 / 欠费 | 候选链 + 熔断（本节） |
| 人工改默认 | 运营发现某模型质量下降 | 改 `default_model` → 仓储写版本自增 → 缓存立即失效（`ChatModelRouter:200` 已具备） |
| 按次指定 | 用户在会话里选模型 | 现有 `chatModelById(id, oid)`（`:176`）+ `custom_model` |
| 灰度 | 新模型小流量验证 | `fallback_priority` + `oid % 100 < percent`（见 P0 #13） |

#### 6.26.9 表结构与配置

```sql
-- mysql/ 与 postgresql/ 两套迁移都要加
ALTER TABLE ai_model ADD COLUMN fallback_priority INT NOT NULL DEFAULT 100;  -- 越小越优先；-1 = 不参与
ALTER TABLE ai_model ADD COLUMN capabilities VARCHAR(255);                   -- text,vision,tools,json

CREATE TABLE ai_model_health (
    id              BIGINT PRIMARY KEY,
    model_id        BIGINT      NOT NULL,
    key_id          BIGINT,
    state           VARCHAR(16) NOT NULL DEFAULT 'CLOSED',
    fail_count      INT         NOT NULL DEFAULT 0,
    opened_at       BIGINT,
    last_error_kind VARCHAR(32),
    last_error_at   BIGINT,
    updated_at      BIGINT      NOT NULL DEFAULT 0
);
```

`V5__ai_model_states.sql` 里其实只有 `ai_embedding_index_state`，没有模型健康表——这张表要新建，别被文件名误导。

#### 6.26.10 端口契约变更

```java
public final class ChatRequest {

    public final List<Message> messages;
    public final ModelPreference preference;   // AUTO / PINNED(id) / REQUIRE(capability)
    public final CallContext ctx;
}

public final class ChatResult {

    public final String text;
    public final ServedBy servedBy;            // 谁答的
    public final List<Attempt> attempts;       // 试过谁、为什么失败
}

public final class ServedBy {

    public final long modelId;
    public final Long keyId;
    public final int attemptCount;
    public final Long transferredFrom;         // 主模型 id；未转移为 null
}

public final class Attempt {

    public final long modelId;
    public final Long keyId;
    public final ErrorKind error;              // 成功为 null
    public final long costMs;
}
```

- 转移逻辑放在端口内部，**不放 Pipeline**：否则意图、SQL、图表、回答四个调用点各写一遍，必然走样。
- `attempts` 同时进日志与 OTLP span event（§6.11.6），Langfuse 里能直接看到"这次答是哪个模型答的"。
- 框架侧只覆盖"同一模型内重试"（`maxRetries` 之类），跨供应商切换属于产品策略，没有现成实现可委托——这是少数必须自己写的地方之一（对比 §6.15.5 的"能委托就委托"）。

#### 6.26.11 用户可见行为

| 情况 | 表现 |
| --- | --- |
| 转移成功 | 不打扰用户；帧里带 `meta.modelSwitched=true`，回答末尾**不加**"（已切换模型）" |
| 全部失败 | 文案 key `aimodel.call.all_failed`，附"默认模型不可用（余额不足 / 密钥失效），已尝试 3 个模型"，给管理员一个动作入口 |
| 已提交后失败 | 保留已生成内容 + `error` 帧；前端给"重试"按钮（重试是**新请求**，会重新选模型） |
| 内容审核拒绝 | 直接拒绝，不换模型，文案不暴露内部规则 |

#### 6.26.12 验收

| 项 | 判定 |
| --- | --- |
| 主模型返回 402 | 3 秒内切到备用，用户拿到完整答案，帧带 `modelSwitched` |
| 主模型 429 | 先同模型退避 1 次，仍 429 再转移 |
| 主 key 401 | 切同模型第二把 key，`transferredFrom` 为空 |
| 所有模型失败 | 返回 `aimodel.call.all_failed` + 已尝试列表，不返回空答案 |
| 首帧后断连 | 不重试，保留半截 + `error` 帧 |
| 内容审核拒绝 | 不转移，直接拒绝 |
| 熔断打开期间 | 直接走备用，上游日志无请求 |
| 多实例 | 实例 A 熔断后，实例 B 5 秒内也熔断 |
| 探活发现欠费 | `valid=0`，`enable` 不变 |
| 关闭转移开关 | 行为回到今天：单模型、失败即报错 |

#### 6.26.13 不做的事

| 不做 | 理由 |
| --- | --- |
| 多模型投票 / 择优 | 成本 ×N，无评估集时是伪科学 |
| 按"质量"自动选模型 | 需要评估集（§6.14），先有数据再谈 |
| 首帧后续写拼接 | 两段风格与事实都不一致，比重答更糟 |
| 跨维度 embedding 转移 | 维度变了旧向量全废，属于索引重建，不是故障转移 |
| 在 Pipeline 里写转移 | 四个调用点各写一遍，必然不一致 |
### 6.27 大数据量导出：接口分页、流式取数与异步合并

#### 6.27.1 现状：一次性整包返回，没有分页

| 事实 | 证据 |
| --- | --- |
| 导出接口一次性返回整份文件 | `ChatController.exportExcel`（`:140-152`）：`chatService.exportExcel(rid, fmt)` 拿到 `ExportFile`，`out.write(file.content)` 一把写完，**无 cursor、无 pageSize、无 total** |
| 生成过程全内存 | `ChatExcel.buildResultWorkbook`（`:91`）用 `XSSFWorkbook`（全内存 DOM，每 cell 一个对象）；`ExcelUtils.writeXlsx`（`:55`）同样 |
| 交付物是 `byte[]` | `ChatExcel.ExportFile.content`（`:58`），整份文件在堆上再写响应，峰值翻倍 |
| 行数上限靠静默截断 | `MAX_ROWS = 100000`（`:38`），超出直接 `break`（`:99-101`），**用户不知道少了数据** |
| 取数也全内存 | `JdbcEngine.collect()`（`:725`）裸 `while (rs.next())` 塞进 `List<Map>`；全仓无 `setFetchSize` |

#### 6.27.2 问数导出的四个特殊性

问数导出不是"通用报表导出"，有四条独有约束：

| 特征 | 影响 |
| --- | --- |
| **行数不可预知** | 同一个入口可能 3 行也可能 300 万行，不能靠固定策略；必须按 `total` 动态决定"一页搞定"还是"分页拉"还是"异步合并" |
| **必须与回答一致** | 用回答时那条 SQL（`chat_record` 里的记录），**不重新生成 SQL**；重新生成会出现"问一次导出两次结果不同" |
| **底层数据会变** | 两种语义只能选一个：按导出时刻重查（文案要说明"导出的是最新数据"）或按记录快照（只对已落库结果）。**不能不说** |
| **导出是 recordId 维度的** | 入口是 `/chat/record/{rid}/export`，不是"任意 SQL 导出"；权限、审计、cursor 都挂在这个 rid 上 |

推论：**分页的边界必须钉在 rid + SQL 指纹上**，不能只钉在行号上。

#### 6.27.3 三层分页别混：接口分页 ≠ 查询分页 ≠ 文件分 sheet

这是最容易做歪的地方，先分清：

| 层次 | 是什么 | 解决什么 | 不解决什么 |
| --- | --- | --- | --- |
| **接口分页** | 导出接口按 cursor 一次返回一页，一页一个文件 | 响应体积、超时、断点续传、可重试 | — |
| **查询分页** | SQL 层 keyset / 游标，一次只查一页 | 内存峰值 | 交付方式 |
| **文件分 sheet** | 一个 xlsx 里开多个工作表 | 只绕过单 sheet 1048576 行上限 | 体验、内存、下载体积 |

**主方案是接口分页。** 多 sheet 默认不做：

- 用户拿到 10 个 sheet 要自己拼，筛选、透视、跨表公式全都不好用；
- SXSSF 多 sheet 共用一个 workbook，滑动窗口不是 per-sheet，内存并没有按 sheet 释放；
- 更糟的是它把"数据不完整"伪装成"完整"——1048576 是硬上限，拆 sheet 只是把问题挪了个位置。

文件分 sheet 只保留一个场景：调用方**显式要求单个 xlsx** 且总行数 > 1048576。此时必须拆，且要在文件第一个 sheet 写清分片规则与总行数。

#### 6.27.4 easypoi 的分页模型（实测 API）

**一句话说清：easypoi 的分页就是查询分页——每页查一次库，不是一次性查出来再切片。** 这也是它唯一值得抄的地方。

```java
public interface IExcelExportServer {
    /** page 从 1 开始；返回空集合即结束。 */
    List<Object> selectListForExcelExport(Object queryParams, int page);
}

Workbook exportBigExcel(ExportParams params, Class<?> pojoClass,
                        IExcelExportServer server, Object queryParams);
```

实测要点：

- 内部循环 `page = 1,2,3…`，每页写进 `SXSSFWorkbook`（滑动窗口落临时文件）；
- `USE_SXSSF_LIMIT = 1000000`（静态常量实测值），行数超过该阈值才切 SXSSF；
- `ExportParams.maxNum` 控制单 sheet 行数，超出自动开新 sheet。

**注意它的定位**：`IExcelExportServer` 是**查询分页**（对应上表第二层），不是接口分页——它把一整个 workbook 返回给你，仍然要一次性写进响应。所以只能借它的"每页查库 + 滑动窗口写盘"，不能借它的交付形态。

#### 6.27.5 但不要引 easypoi 依赖

**版本现状（实测 Maven Central + GitHub）**：`cn.afterturn:easypoi` / `-base` / `-web` / `-annotation` / `-spring-boot-starter` 五个 artifact 的 `<latest>` 与 `<release>` 全部是 **4.5.0**，`lastUpdated = 2024-02-26`，之后没有新版本；源码仓库 `jueyue/easypoi` 最后一次 push 是 2022-12，且没有打 tag/release。也就是说 **没有 5.x，也没有维护中的新版本**。

版本停在 4.5.0 带来的直接问题：其父 pom 里 `poi.version = 4.1.1`，且依赖 `poi-ooxml-schemas`——该 artifact 在 POI 5 已被 `poi-ooxml-lite` 取代。项目已显式锁 POI 5.2.3，并在 pom 注释里写过"BOM 的 poi 4.1.2 与 poi-ooxml 5.2.3 不兼容"。引 easypoi 等于把踩过的坑再踩一次，而且指望不上后续修复。

结论：**借模型，不借依赖**。用 POI 的 `SXSSFWorkbook` 自己实现同样的三段式，代码量不大。

#### 6.27.6 接口分页契约

```http
GET /chat/record/{rid}/export?format=csv&pageSize=50000[&cursor=<token>]

200 OK
X-Export-Cursor: eyJyaWQiOjEyMywi...
X-Export-Total-Rows: 1234567
X-Export-Page-Rows: 50000
X-Export-Part: 3
Content-Disposition: attachment; filename="季度销售-part-0003.csv"
```

八条规则：

1. **cursor 不透明**：base64url 编码 `{rid, sqlDigest, 排序键值, 签发时间}`，客户端不得解析。**不用 page number**——深 offset 退化，且翻页期间数据变化会导致重复/漏行。
2. 第一页不带 cursor；返回空 `X-Export-Cursor` 表示最后一页。
3. **一页一个文件**，文件名带 `part-000N`；服务端不合并。
4. **幂等**：同一 cursor 重复请求返回同一页（cursor 里固定了边界），重试安全。
5. cursor 有效期 30 分钟；过期返回 410，让调用方从第一页重新开始。
6. `pageSize` 默认 5 万，上限 20 万（再大单页生成就超过网关超时）。
7. 首页就返回 `X-Export-Total-Rows`，调用方据此知道要拉几页、要不要转异步。
8. `sqlDigest` 防串号：拿 A 问题的 cursor 去拉 B 问题，直接 400。

前端/脚本侧就是循环：第一页拿 cursor → 依次拉 → 空 cursor 停 → 本地拼接。服务端每个请求都是短请求，超时、重试、断点续传全都自然成立。

#### 6.27.7 ExportPort

```java
public interface ExportPort {

    /** 取一页并写成文件字节；cursor 为空表示第一页。 */
    ExportPage page(CallContext ctx, ExportPageRequest req);

    /** 多页合并成单文件，异步执行。 */
    ExportJob submit(CallContext ctx, ExportRequest req);
}

public final class ExportPageRequest {

    public final long recordId;     // 问数：按记录导出，不重新生成 SQL
    public final String cursor;     // null = 第一页
    public final int pageSize;      // 交付页，默认 50000，上限 200000
    public final Format format;     // CSV / XLSX
}

public final class ExportPage {

    public final byte[] body;       // 单页 ≤ 20 万行，峰值可控
    public final String nextCursor; // null = 最后一页
    public final long totalRows;
    public final int pageRows;
    public final int partNo;
}
```

**查询批 ≠ 交付页**，两个参数不要合并：

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `fetchBatch` | 2000 | SQL 一批取多少行，控制内存与往返次数 |
| `pageSize` | 50000 | 一个文件装多少行，控制响应体积与文件数 |

单页生成（已去掉多 sheet 循环）：

```java
SXSSFWorkbook wb = new SXSSFWorkbook(500);        // 内存只留 500 行
try {
    wb.setCompressTempFiles(true);
    Sheet sheet = wb.createSheet(name(req));
    writeHeader(sheet.createRow(0), req.columns);
    int rowIndex = 1;
    int written = 0;
    String cursor = req.cursor;
    while (written < req.pageSize) {
        int want = Math.min(req.fetchBatch, req.pageSize - written);
        List<Map<String, Object>> batch = fetch(cursor, want);   // keyset 或游标
        if (batch.isEmpty()) {
            break;
        }
        for (Map<String, Object> row : batch) {
            writeRow(sheet.createRow(rowIndex++), row, req.columns);
            written++;
        }
        cursor = cursorOf(batch.get(batch.size() - 1));
        wb.flushRows(500);                                        // 主动落盘
    }
    wb.write(out);
} finally {
    wb.dispose();                                                 // 必须：删临时文件
}
```

三条硬要求：

1. **`dispose()` 必须调**（`finally`）。`SXSSFWorkbook.close()` 不删临时文件，漏了会把 `/tmp` 撑满。
2. 每批结束主动 `flushRows(500)`，别等窗口满了自动刷——自动刷的时机不可控，峰值会飘。
3. **首屏错误必须在写第一个字节前抛出**。一旦开始写 body 就不能再改状态码，中途失败只能断流。

查询分页方式，按优先级：

| 方式 | 适用 | 代价 |
| --- | --- | --- |
| JDBC 游标 | 自己控 SQL（本项目主路径） | 需 `setFetchSize` + 连接参数（MySQL `useCursorFetch=true`，PG 默认支持） |
| keyset 分页 | 有稳定排序列 | 排序键要唯一化：`(create_time, id)` |
| offset 分页 | 走 ORM / 远程 API，只能这样 | `OFFSET 1000000` 要扫 100 万行，越翻越慢 |

#### 6.27.8 分页处理 Excel：导出侧与导入侧

"分页处理"在两个方向上都成立，但机制完全不同：

| 方向 | 分页对象 | 关键 API | 内存 |
| --- | --- | --- | --- |
| 导出 | 查询结果集 | keyset / 游标 + SXSSF 滑动窗口 | O(fetchBatch + 500 行) |
| 导入 | Excel 的行 | SAX 事件流（`XSSFSheetXMLHandler`） | O(1)，不建 DOM |

**导出侧：一页查询 → 一页 Excel**

```text
total = count(sql)                       // 只算一次，用于告诉调用方几页
for (part = 1; ; part++) {
    page = fetch(cursor, pageSize)       // 查询分页：一次只取一页
    if (page.isEmpty()) break            // 终止条件永远是"查不到"
    bytes = writeXlsx(page)              // 这一页一个 workbook：flushRows + dispose
    emit(part, bytes, nextCursor)        // 输出这一页文件
    cursor = nextCursor
}
```

三条要点：

1. `count` 与分页是两次查询，中间数据可能变化 → `count` 只用来"告诉调用方几页"，**不作循环终止条件**；终止条件永远是"查询返回空"。
2. 每页的 `SXSSFWorkbook` **新建一个，用完 `dispose()`**。不要跨页复用 workbook——复用等于把整本攒在临时文件里，最后 `write()` 时还是一个大文件，分页白做。
3. 页内 `flushRows` 管"单页内部"的内存；页与页之间靠新 workbook 隔离。

**导入侧：不要用 `XSSFWorkbook` 全量加载**

现状两条路都是 DOM，且文件已先读成 `byte[]`，峰值 = 文件字节 + DOM：

| 位置 | 写法 | 问题 |
| --- | --- | --- |
| `ExcelService:263` | `WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))` | 上传文件先整份进内存，再建 DOM |
| `ExcelReadSupport:74` | `WorkbookFactory.create(new ByteArrayInputStream(content))` | 同上，多一层 `byte[]` |

大文件导入必须换事件模型：

```java
// POI 的 SAX 读取：逐行回调，不建 DOM
try (OPCPackage pkg = OPCPackage.open(file);
     XSSFReader reader = new XSSFReader(pkg);
     InputStream sheet = reader.getSheetsData().next()) {
    ContentHandler handler = new XSSFSheetXMLHandler(
            styles, sharedStrings, rowHandler, false);   // rowHandler 里按批处理
    new SAXParser().parse(sheet, handler);
}
```

- `rowHandler` 每 N 行（默认 1000）攒一批交给业务，处理完即丢；
- 建临时表用 JDBC batch（`addBatch` + 每 1000 行 `executeBatch`），不要一行一个 insert；
- 行数上限（如 50 万）与列数上限（如 200）先判，超了直接拒绝，不要边解析边发现。

两条红线：

1. **导入不要"先全量读进 `List<List<String>>` 再处理"**——那只是把 DOM 换成 ArrayList，内存一样炸。
2. **导出不要跨页复用 workbook**——会退化成"一个大文件"。

#### 6.27.9 CSV 应该是大数据量的默认格式

| 维度 | XLSX（SXSSF） | CSV |
| --- | --- | --- |
| 内存 | O(窗口) + 临时文件 | O(1) |
| 5 万行体积 | 数 MB | 1~2 MB |
| 生成速度 | 慢（样式 / 共享字符串） | 快 |
| 结构化能力 | 有（类型、样式） | 无 |

规则：`totalRows > 100000` 时**默认给 CSV**；要 XLSX 需显式请求，并在首页响应里提示"共 N 页，预计 M 秒"。

#### 6.27.10 三种交付形态

| 形态 | 触发 | 行为 |
| --- | --- | --- |
| **接口分页（默认）** | 任何导出 | 一页一文件，cursor 续传，客户端循环拉 |
| 单页即全量 | `totalRows ≤ pageSize` | 一次返回，`X-Export-Cursor` 为空 |
| **异步合并** | `totalRows > 100 万`，或调用方明确要"一个文件" | 后台逐页生成 part → 打包 zip（CSV 可直接拼接）→ 通知下载 |

异步必须落"导出任务"表：

```sql
CREATE TABLE export_task (
    id          BIGINT PRIMARY KEY,
    oid         BIGINT NOT NULL,
    uid         BIGINT NOT NULL,
    record_id   BIGINT NOT NULL,
    sql_digest  VARCHAR(64),        -- 不存明文 SQL
    format      VARCHAR(8) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/running/done/failed/expired
    total_rows  BIGINT,
    part_count  INT,
    file_key    VARCHAR(512),
    error_key   VARCHAR(128),
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL
);
```

文件走 `x-file-storage` + 签名 URL，TTL 24 小时。复用 `ScheduledTaskExecutor:205` 已有的导出路径，不新起调度器。

#### 6.27.11 安全与审计

- 导出必须过与查询**同一套**行/列权限（§6.14 / 附录 B），不能因为"导出"另开一条权限路径；
- cursor 里带 `sqlDigest` 与 `rid`，伪造或换题一律 400/403；
- 导出是数据外流最大口子 → 必须审计（谁、何时、多少行、哪些列、拉了几页），与 P1 #29 同一条；
- 文件带水印/溯源（P2 #42）。

#### 6.27.12 与 P0 #1 的关系

两件事，别混：

| 项 | 上限 | 路径 |
| --- | --- | --- |
| 交互式查询结果（P0 #1） | 1 万行，超出标 `truncated` | 内存结果集 → 前端表格 |
| 导出（本节） | 单页 20 万行，总量走分页 | 流式，不进内存结果集 |

导出**不应该**复用"已经查出来的结果集"——那是给前端渲染用的，本身就有行数上限。大数据量导出必须按 rid 重新走一次流式查询，并用 cursor 把边界钉死。

#### 6.27.13 验收

| 项 | 判定 |
| --- | --- |
| 拉 3 页 | 三页行数之和 = `X-Export-Total-Rows`，无重复无遗漏 |
| 同一 cursor 请求两次 | 返回字节完全一致（幂等） |
| 拿 A 问题 cursor 拉 B 问题 | 400 |
| cursor 过期 | 410，提示从第一页重来 |
| 最后一页 | `X-Export-Cursor` 为空，`X-Export-Part` 序号连续 |
| 单页 5 万行 XLSX | 堆峰值 < 100MB，文件能打开 |
| 总行数 300 万 | 分 60 页可拉完；不触发 OOM |
| 中途 SQL 失败 | 该页返回错误，已生成页不受影响 |
| 客户端断开 | 服务端 5 秒内停止查询 |
| 导出结束 | `/tmp` 无 `poifiles` 残留 |
| 无权限的表 | 导出被拒绝，与查询同一判定 |
| 异步合并 | 任务完成后 zip 可下载，part 数正确，TTL 到期消失 |
| 导入 10 万行 xlsx | SAX 事件流，堆峰值 < 200MB（当前 DOM 路径会到 GB 级） |
| 导入建表 | JDBC batch，1 万行 ≤ 10 次 `executeBatch` |
| 跨页复用 workbook（反例） | 代码评审拦截：每页必须新建 `SXSSFWorkbook` 并 `dispose()` |

#### 6.27.14 不做的事

| 不做 | 理由 |
| --- | --- |
| 用多 sheet 装大数据 | 只是绕过 1048576 上限，体验与内存都没解决（§6.27.3） |
| 引 easypoi | POI 4.1.1 + `poi-ooxml-schemas`，与锁定的 5.2.3 冲突，且已无维护（§6.27.5） |
| 用 page number 做接口分页 | 深 offset 退化 + 翻页期间数据变化导致重复/漏行 |
| 一次性 `byte[]` 返回全量 | 峰值翻倍，正是当前 OOM 的直接原因 |
| 服务端自动合并所有分页 | 又回到"一个大文件"，等于把分页白做 |
| 导出另起一套权限 | 必然漏权限 |
| 导出复用前端结果集 | 结果集本身有行数上限，导出会被它卡死 |
### 6.28 性能与容量：线程模型是当前真正的天花板

#### 6.28.1 先说结论

| 事实 | 证据 | 后果 |
| --- | --- | --- |
| **SSE 跑在 Servlet 请求线程上** | 全仓 grep 无 `AsyncContext` / `SseEmitter` / `WebFlux`；`ChatQuestionService.streamQuestion(body, response)` 同步写 `PrintWriter` | 一条 SSE 连接 = 一个 Tomcat 线程，从提问占到最后一行 |
| **线程上限是默认值 200** | 配置里没有 `server.tomcat.threads.*` | 并发问 > 200 就排队或拒绝 |
| **读超时 600 秒** | `ChatModelClient.READ_TIMEOUT_MS = 600000`（`:46`） | 一个卡死的上游能占住线程 10 分钟 |
| **没有业务指标** | 引了 actuator 但 `management.endpoints.web.exposure.include: health` 只开健康检查；全仓无 `MeterRegistry` / `Timer` / `Counter` | 出问题只能翻日志，没法看 p95 |
| **数据源池 500，按数据源 id LRU** | `askdata.datasource.pool.max-pools: 500` | 导出、回填、业务查询抢同一批池 |
| **4 个自建线程池，没有导出池** | `McpSseClusterBridge`(4) / `EmbeddingIndexService`(single) / `ScheduledTaskScheduler`(N) / `LeaseHeartbeat`(1) | 导出跑在 Tomcat 线程上，长任务直接吃业务线程 |

**算一下就明白天花板在哪**（算例，非实测）：平均 20 秒/问、200 线程 → 理论上限约 10 QPS。若 5% 的请求撞上卡死的上游（600 秒超时），20 个线程被占死 10 分钟，可用线程降到 180，QPS 掉到 9 且**不会自己恢复**——这就是"一个供应商挂了，全站跟着挂"的机制，和 §6.26 的故障转移是同一个问题的两面。

#### 6.28.2 先定指标，否则压测没有意义

| 指标 | 定义 | 采集点 | 现状 |
| --- | --- | --- | --- |
| TTFT | 收到问题 → 第一个 SSE data 帧 | 服务端打点 + 前端 | 未测 |
| E2E p95 | 提问 → 最后一个帧 | 服务端 | 未测 |
| 并发会话 | 同时在线的 SSE 连接数 | 自建 gauge | 上限 ≈ 200（线程） |
| 每问模型调用次数 | 意图 + SQL + 修复 + 图表 + 回答 | 计数器 | 最多 5 次（代码可数） |
| SQL 耗时 | 每次 `JdbcEngine` 查询 | Timer | 未测 |
| 忙线程 | `tomcat.threads.busy` | actuator | 未暴露 |
| 连接池活跃 | 元数据库 + 用户数据源 | actuator | 未暴露 |
| 堆 / GC | 导出与回填期间 | JVM | 未测 |

最小改动：把 actuator 的 `metrics` 打开，再加 3 个 `Timer`（TTFT / E2E / SQL）和 1 个 `Gauge`（活跃 SSE 连接）。不引新依赖。

#### 6.28.3 压测必须 mock 模型

真模型有 TPM/RPM 限流、价格、抖动，压出来的数是**供应商的容量**，不是我们的容量；而且不可重复、不可控成本。两层分开：

| 层 | 用什么 | 并发 | 验证 |
| --- | --- | --- | --- |
| L1 应用层 | 本地 mock 模型（固定延迟 500ms / 2s / 10s 三档，固定 token 流） | 高（200~500） | Pipeline、SSE、DB、线程模型 |
| L2 端到端 | 真实模型 | 低（≤ 10） | 真实链路、限流行为、超时与转移 |

mock 要能模拟四种坏情况，正好对应 §6.26.2 的错误分类：慢首帧、流中断、429、402。一个 `@RestController` 返回固定 SSE 就够，几十行。

#### 6.28.4 场景矩阵

| 场景 | 并发 | 关注 |
| --- | --- | --- |
| 冷启动单问 | 1 | TTFT 分解：JIT、连接池预热、模型 DNS |
| 稳态单问 | 20 | p95、线程占用 |
| 突发 | 0 → 200（10 秒内） | 拒绝策略、是否雪崩 |
| 长会话（50 轮） | 10 | 上下文增长、token 预算、内存 |
| 大结果集 | 5 | 1 万行截断（P0 #3）、堆 |
| 并发导出 | 3 | 连接池、`/tmp` 临时文件、磁盘 IO |
| 回填 + 业务混合 | 回填 1 + 问 50 | 资源抢占，业务 p95 劣化 |
| 模型故障转移 | 20 + 主模型 402 | 切换瞬间是否堆积（§6.26） |

#### 6.28.5 容量模型

```text
单实例并发上限 ≈ min(
    Tomcat 线程数,            // 现在 200，是当前瓶颈
    元数据库连接数,
    用户数据源连接数,
    模型 RPM × 平均响应时长    // 改异步后，这里会变成新瓶颈
)
```

容量规划需要运维先给两个数：**模型账号的 RPM / TPM 配额**、**元数据库最大连接数**。没有这两个数，容量规划就是猜。

#### 6.28.6 通过标准（门槛值）

| 项 | 门槛 |
| --- | --- |
| TTFT p95（mock 首帧 500ms） | < 1.5s |
| E2E p95（mock 模型 2s） | < 6s |
| 200 并发持续 10 分钟 | 错误率 < 0.1%，无 OOM，忙线程 < 80% |
| 突发 0 → 200 | 无 5xx 雪崩；超出部分返回 429 而不是排队 |
| 导出 100 万行 CSV | 堆峰值 < 50MB，耗时 < 3 分钟 |
| 回填 10 万条向量 | 业务 p95 劣化 < 20% |
| 内存 | Full GC 后老年代占用 < 70% |
| 端口层额外开销 | 每请求 ≤ 5ms（listener + 拦截器 + 重试预算） |

#### 6.28.7 环境与数据

- **独立环境，不压生产**；数据量要接近生产（10 万级 `chat_record`），否则索引与分页的收益完全测不出来；
- 压测前先执行 P0 #6 的索引 DDL，否则测的是"没索引"的慢，结论会误导优化方向；
- 压测流量打标（`X-Load-Test: 1`），**不计入成本核算与评估集**，否则污染数据；
- 数据脱敏，压测库不接真实租户。

#### 6.28.8 与升级的关系（这条最容易被忽略）

- **SB4 升级前必须先有 Java 8 基线**，否则"升级后变慢"无法判定。§9.1 的契约测试只保证行为一致，不保证性能。
- 三个端口实现（`native` / `spring-ai` / `langchain4j`）跑同一套压测，形成三列对比；没有这一列，"选哪个框架"就只能靠感觉。
- 端口层每加一层（listener、拦截器、重试、故障转移）都是每请求的固定开销，必须给预算并测出来。

#### 6.28.9 现在就能做的四件事（不用等改造）

1. actuator 打开 `metrics`，暴露忙线程 / 连接池活跃数 / 堆；
2. 加 3 个 `Timer`（TTFT / E2E / SQL）+ 1 个 `Gauge`（活跃 SSE）；
3. 写一个 mock 模型端点（固定 SSE 流），压测与故障转移测试共用；
4. 跑一次 20 并发 × 10 分钟，拿到第一组数——这组数就是后面所有优化的参照物。

#### 6.28.10 不做的事

| 不做 | 理由 |
| --- | --- |
| 用真模型做高并发压测 | 测的是供应商容量，还烧钱，不可重复 |
| 在生产压测 | 问数是长连接 + 大查询，风险不可控 |
| 把 mock 的绝对数当 SLA | mock 省掉了网络与模型抖动，只能横向对比 |
| 只压"正常路径" | 慢首帧、429、402、断流才是真实故障形态 |
| 压测前不建索引 | 结论会指向错误方向 |

---

### 6.29 安全基线：入口 × 攻击面

#### 6.29.1 威胁模型

| 入口 | 攻击者 | 能做的最坏的事 |
| --- | --- | --- |
| 网页 / 嵌入页 | 拿到 token 的匿名用户 | 越权读别人数据、注入 prompt、批量拖数 |
| REST（API Key） | 内部系统 / 泄露的 key | 绕过限流批量拉取、冒用租户 |
| MCP server | 任意 MCP client | 用工具调用绕过页面层权限 |
| 模型上游 | 恶意或被投毒的第三方模型 | 返回注入指令、套出 system prompt、伪造工具调用 |
| 用户数据源 | 数据源本身 | SQL 注入、超大结果集 |
| 文件上传 | 普通用户 | zip bomb、宏、文档内藏的指令 |

#### 6.29.2 已具备 vs 缺口

| 面 | 现状 | 缺口 |
| --- | --- | --- |
| SQL 注入 | `SqlSafety` 有 `DANGEROUS_PATTERNS` / 函数黑名单 / `METADATA_QUERY` | 纯正则；注释绕过、编码绕过、大小写混合未测 |
| **出站请求** | **无任何 host 校验**，9 处 `new URL(apiDomain)` 直接连 | **SSRF**（见 §6.29.3） |
| 嵌入 | AES/GCM + ±10s 窗口 + Origin/UA/时区/webdriver 四项校验 | 无 CSP、无 `X-Frame-Options`（全仓 grep 不到）；token 无 nonce，10 秒内可重放 |
| 图表 URL | 在免登录白名单里 | 拿到 URL 就能看（P0 #5） |
| 限流 | 无 | 单用户可打满模型与连接池 |
| 密钥存储 | `CryptoUtil` 加密落库 | 主密钥来自配置，无轮换、无 KMS |
| 审计 | `AuditLogService` 已有 | 导出 / 嵌入 / MCP 三条出口未覆盖 |
| 内容安全 | 无 | 模型可被诱导输出违规内容（§6.14 护栏只覆盖输入与 SQL） |
| 日志 | — | 可能打印 api_key、完整 SQL、结果集 |

#### 6.29.3 SSRF：最该现在修的一条

```
ChatModelClient.java:214 / :261 / :406 / :685   → new URL(chatCompletionsUrl(apiDomain...))
AiModelService.java:396 / :566                  → new URL(url)  （连通性校验）
ChatModelClient.java:921 / :961 / :979          → new URL(...).getHost() / getPath()
```

攻击路径很直接：`api_domain` 由管理员或租户配置，填 `http://169.254.169.254/latest/meta-data/`，**连通性校验那一步就会真的去请求**，响应片段还可能进错误信息回显。

修法（`OutboundUrlGuard`，所有出站请求统一走）：

| 规则 | 说明 |
| --- | --- |
| 协议白名单 | 只允许 `http` / `https`；禁 `file` / `jar` / `ftp` |
| 解析后校验 IP | 私有段（10/8、172.16/12、192.168/16）、回环、链路本地（169.254/16）、`0.0.0.0`、组播 |
| 禁重定向 | `HttpURLConnection.setInstanceFollowRedirects(false)`，否则白名单可被 302 绕过 |
| DNS 重绑定 | 校验与实际连接用**同一个已解析 IP**（先解析、再连 IP、Host 头保留） |
| 本地例外 | 本地部署要连 `127.0.0.1:11434`（Ollama），所以不能硬禁 → 用 `askdata.security.outbound.allow-private-hosts`，只在 local profile 打开 |

#### 6.29.4 密钥与日志

- `ChatModelRouter.toRef` 会把 `api_key` 解密成明文放进 `ModelRef`；`ModelRef` 目前没有 `toString()`，一旦有人加一个默认实现或打日志，密钥就进日志了。**先给 `ModelRef.toString()` 写脱敏版本**。
- 日志过滤：对 `sk-`、`Bearer `、`api_key`、`password` 做统一打码；SQL 只记 `digest` 不记全文（与 `export_task.sql_digest` 同一思路）。

#### 6.29.5 越权矩阵（做成自动化用例，别靠人工点）

| 资源 | 横向（同租户不同用户） | 纵向（跨租户） |
| --- | --- | --- |
| `chat_record` | 只能看自己的会话 | 换 `rid` 必须 403 |
| 图表图片 | 签名 URL 校验归属 | 换 `chat_id` 必须 403 |
| 导出 | 同上 | cursor 里绑 `rid` + `sqlDigest`（§6.27.6） |
| 数据源 | 工作空间可见性 | 换 `dsId` 必须 403 |
| 嵌入 token | 绑 `assistant_id` + `target` | 伪造 `uid` 必须失败 |
| MCP 工具 | 与页面同一套权限 | 不能因为"是 MCP"就放宽 |

做法：把"资源 × 角色 × 入口"列成矩阵，每个格子一条用例，跑在 CI 里。这类漏洞靠人工测一定漏。

#### 6.29.6 加固清单（可直接排期）

| # | 项 | 落点 |
| --- | --- | --- |
| 1 | `OutboundUrlGuard`（SSRF） | 新增，P0 |
| 2 | CSP `frame-ancestors` + `X-Frame-Options` | §6.25，P1 |
| 3 | 全局限流（`QuotaPort`） | P0 |
| 4 | 日志脱敏过滤器 + `ModelRef.toString()` | 新增，S |
| 5 | 上传文件类型 / 大小 / zip 比率校验 | §6.23.6 |
| 6 | MCP 改 `isError` + 结果包裹 | §6.24 |
| 7 | 审计覆盖导出 / 嵌入 / MCP | P1 |
| 8 | 依赖漏洞扫描进 CI（OWASP dependency-check / trivy） | 新增，S |
| 9 | 越权矩阵自动化用例 | 新增，M |
| 10 | 数据出境评估（模型在境外 API） | 新增，需业务确认 |

#### 6.29.7 验收

| 项 | 判定 |
| --- | --- |
| 配置 `api_domain=http://127.0.0.1:22` | 非 local profile 被拒，且不发出请求 |
| 配置 `http://169.254.169.254/...` | 被拒 |
| 上游返回 302 到内网 | 不跟随 |
| A 租户拿 B 的 `rid` 导出 | 403 |
| 同一嵌入 token 用两次 | 第二次 401（需先实现 nonce） |
| 非白名单域名 iframe | 浏览器被 CSP 拦截 |
| 日志全文搜索 `sk-` | 无命中 |
| SQL 注释绕过用例集 | 全部被 `SqlSafety` 拦下或明确记录为已知缺口 |
| MCP 工具越权调用 | 与页面同样 403 |
### 6.30 场景覆盖矩阵：这套流程到底能覆盖多少

#### 6.30.1 先给数

按五个维度列出 **41 个可验证的场景**（不是按客户需求列，是按代码里能找到的入口 + 一个反例业务列）：

| 覆盖度 | 数量 | 占比 |
| --- | --- | --- |
| ✅ 完整覆盖 | 10 | 24% |
| ◐ 部分覆盖（有入口，但关键环节靠模型猜或缺失） | 19 | 46% |
| ○ 未覆盖 | 12 | 29% |

**数量不是重点**。重点在下面三句话：

1. 完整覆盖的 10 个，恰好都是"主链 + 已有模块"——说明这套设计的价值不在扩场景，在把已有场景做稳。
2. 部分覆盖的 18 个里，有 6 个属于**"看起来有、实际靠模型猜"**，这类最危险，因为测试和演示都看不出来。
3. 未覆盖的 9 个里，只有 2 个是真该做（用户取消、MCP client），其余是明确不做或远期。

#### 6.30.2 A. 交互形态（11）

| # | 场景 | 覆盖 | 靠什么 | 缺什么 |
| --- | --- | --- | --- | --- |
| A1 | 单轮问数 | ✅ | §6.20 主链 24 节点 | — |
| A2 | 多轮追问（"再按月份拆一下"） | ◐ | `priorContextMessages` 把历史塞进 prompt | 指代改写未做（P0 #14），全靠模型猜 |
| A3 | 分析型（一次问产生多 SQL） | ◐ | 有子记录机制 | 一次"分析"子记录会清空多轮上下文（附录 C.1） |
| A4 | 看板 / 仪表盘 | ✅ | `JdbcChartDataExecutor` + `setMaxRows(10000)`（`:61`） | — |
| A5 | 定时任务 | ✅ | `ScheduledTaskExecutor` + 租约（`V3__scheduled_lease.sql`） | — |
| A6 | 嵌入页问答 | ◐ | `EmbeddedController` + AES/GCM token（±10s、四项校验） | 无 CSP / `X-Frame-Options`；token 可重放 |
| A7 | REST API | ◐ | 已有接口 | 前缀不统一、无幂等、无限流（§6.24） |
| A8 | MCP server | ◐ | 7 个工具（`datasource_list` / `server_info` / `workspace_list` / `model_list` / `chat_start` / `access_token` / `question_answer`） | 错误包成正常结果（未用 `isError`） |
| A9 | MCP client | ○ | 规划（P2 #38） | `aj-mcp-client` 坐标未确认 |
| A10 | 文件上传问答 | ○ | 规划（P2 #36） | 解析选型已定（§6.23），未实现 |
| A11 | 语音 / 图片问答 | ○ | 只留口（§6.18） | 接口形状有，实现没有 |

#### 6.30.3 B. 数据形态（10）

| # | 场景 | 覆盖 | 靠什么 | 缺什么 |
| --- | --- | --- | --- | --- |
| B1 | 单表查询 | ✅ | 主链 | — |
| B2 | 多表 join | ◐ | 有 `TableRelationController`（表关系维护页） | 关系没进 prompt（P1 #20），join 全靠模型看 schema 猜 |
| B3 | 宽表 | ◐ | m-schema 注入 | schema 重复注入放大 token（附录 C.2） |
| B4 | 14 种方言 | ◐ | `DatasourceType` 枚举齐全 | 方言 SQL 规则未注入（P1 #19），函数/分页语法靠模型常识 |
| B5 | 大结果集 | ◐ | 看板有 1 万上限 | 主链无上限（P0 #3），导出全内存（P0 #4） |
| B6 | 空结果 / 全 NULL | ◐ | 能返回空表 | 无自检（P1 #18），空结果会被当成"答案是 0" |
| B7 | 无权限表 | ◐ | 有权限判定 | 修复环 fail-open（P0 #8），权限失败会放行 |
| B8 | 向量召回（RAG） | ✅ | Qdrant + 无模型时降级词法 | — |
| B9 | 术语 / 训练样本 | ✅ | `terminology` / `training` 模块 | — |
| B10 | schema 变更后 | ○ | — | 现在没结果缓存所以不暴露；一旦加缓存（P1 #33）必须有失效协议 |

#### 6.30.4 C. 失败形态（10）

| # | 场景 | 覆盖 | 靠什么 | 缺什么 |
| --- | --- | --- | --- | --- |
| C1 | 模型欠费 / 401 / 429 / 5xx | ◐ | 有错误字符串 | 无错误分类、无转移、无熔断（§6.26） |
| C2 | 模型首帧后断流 | ○ | — | 明确不处理（§6.26.4），只保留半截 + 报错 |
| C3 | SQL 报错 → 自动修复 | ✅ | `MAX_SQL_REPAIR_RETRIES = 2`（`:69`） | — |
| C4 | SQL 超时 | ✅ | `statement.setQueryTimeout(conf.getTimeout())`（`JdbcEngine:778`，尽力语义） | 部分驱动不支持，已注明 |
| C5 | 结果超限 | ◐ | 看板截断到 1 万 | 主链无截断标记，用户不知道少了数据 |
| C6 | 用户取消 / 断连 | ○ | — | 全仓无 `isClientAbort` / `AsyncListener`；用户关页面后线程仍跑满 600s |
| C7 | 同会话并发提问 | ◐ | 有会话锁？ | 附录 C.6：静默丢轮 |
| C8 | 内容审核拒绝 | ○ | — | 无 `CONTENT_FILTERED` 处理（§6.26.2 已定义，未实现） |
| C9 | 上游脏 SSE / 半包 | ◐ | `readSse` 有 try/catch | 未做混沌测试（P1 #28） |
| C10 | 导出中断 | ○ | — | 当前一次性 `byte[]`，没有"中断"概念；流式化后才有 |

#### 6.30.5 D. 权限形态（6）

| # | 场景 | 覆盖 | 靠什么 | 缺什么 |
| --- | --- | --- | --- | --- |
| D1 | 多租户隔离 | ✅ | `oid` 贯穿主链 | — |
| D2 | 行 / 列权限 | ◐ | 有 SQL 改写 | 改写正确性未验证（P1 #32） |
| D3 | 匿名嵌入 | ✅ | token + Origin/UA/时区/webdriver 四项校验 | 无 nonce，10 秒内可重放 |
| D4 | 无租户内部任务 | ◐ | `CallContext.oid=0` 是规划 | 未实现，现有代码假设有 oid |
| D5 | MCP 工具权限 | ◐ | 与页面共用同一套 | 错误不区分，越权失败看起来像成功 |
| D6 | 跨入口身份统一 | ○ | — | REST / API Key / MCP / 嵌入四套鉴权，没有统一身份解析链（§6.19） |

#### 6.30.6 E. 非问数业务（预约类，4）

以"智能预约"为代表：本质是表单收集 + 缺参反问 + 写入。详见 §6.31。

| # | 场景 | 覆盖 | 靠什么 | 缺什么 |
| --- | --- | --- | --- | --- |
| E1 | 槽位收集 + 缺参反问 | ○ | `CLARIFY` 是候选节点 | 无槽位状态机（`SlotStore`），反问全靠模型自由发挥 |
| E2 | 写操作提交（含幂等） | ○ | `ACT` 阶段有位置 | 无 `ActionPort`、无幂等键、无补偿（§6.31.7） |
| E3 | 确认与撤销 | ○ | §6.17 提过 `HumanInTheLoop` | 未落地 |
| E4 | 外部系统调用（日历 / HIS / 审批） | ◐ | `ToolPort` 可承载调用 | 没有事务语义（部分成功怎么算） |

**这 4 个不是"不做"**：已排入 `enhancement-plan` 的 P1-b（B1~B7），实现全部默认关闭，问数主链零变化。

#### 6.30.7 真正决定架构的三个场景

不是数量最多的那类，而是这三个——**它们不解决，其它场景做得再好也上不了生产**：

| 场景 | 决定的架构 | 在哪 |
| --- | --- | --- |
| 大结果集（B5 / C5 / C10） | 内存模型：能不能流式、要不要分页 | P0 #3、P0 #4、§6.27 |
| 模型欠费 / 挂掉（C1） | 错误模型：错误能不能被程序识别、能不能转移 | §6.26 |
| 并发长连接（A1 的规模化） | 线程模型：SSE 占不占 Servlet 线程 | §6.28 |

#### 6.30.8 六个"看起来有、实际靠模型猜"的坑

这类最危险——演示能过、测试能过，生产上答错但不报错：

| 坑 | 现状 | 修法 |
| --- | --- | --- |
| 多轮指代 | 把历史塞进 prompt，让模型自己理解"它" | `RESOLVE_REF` 显式改写（P0 #14） |
| 多表 join | 有关系表，但不告诉模型 | join 提示注入（P1 #20） |
| 方言差异 | 有 14 种类型枚举，但不说语法差异 | 方言规则片段注入（P1 #19） |
| 空结果 | 返回空表就算完 | `VERIFY` 自检 + 明确话术（P1 #18） |
| 行/列权限 | 有改写，没验证 | 正确性用例集（P1 #32） |
| MCP 错误 | 错误包成正常 JSON | 改 `isError`（§6.24） |

#### 6.30.9 结论

- **能覆盖 41 个场景中的 29 个**（10 完整 + 19 部分），覆盖率 71%，"完整"只有 24%。
- **37 个场景在当前 Java 8 分支就能做**，不需要等 SB4——升级不是这些场景的前置条件；真正依赖新平台的只有 MCP client、语音、图片、agentic 四类。
- 12 个未覆盖里，5 个是"明确不做或远期"（语音、图片、文件、MCP client、schema 变更缓存），7 个是"该做且已排期"（用户取消、内容审核、导出中断、跨入口身份统一，以及预约三件套：槽位收集、写操作、确认撤销 → P1-b）。
- **如果只能做三件事**：大结果集、模型故障转移、SSE 线程模型。这三个做完，其余场景才有意义。
### 6.31 非问数场景：智能预约（表单收集 + 人工选择 + 写入）

#### 6.31.1 为什么拿预约来验证

问数流程是 **只读 + 单轮为主**；预约流程是 **写入 + 多轮槽位 + 副作用**。这不是"反例"，是**第二类一等场景**：问数内核要同时支撑"只读问答"和"受控写操作"两种形态，否则所谓的通用只是问数的另一种写法。

一句话概括预约：**它本质是一个表单收集过程，缺参数时由 AI 反问用户**。这句话里藏着三个难点——"表单"（结构化槽位）、"缺参数"（状态判定）、"反问"（把判定结果变成一句人话）。

#### 6.31.2 两条流程对比

| 阶段 | 问数 | 预约 |
| --- | --- | --- |
| 鉴权 | AUTH | AUTH |
| 理解 | INTENT（实体 + 时间） | INTENT（槽位抽取） |
| 取数 | RECALL（schema / 术语 / 向量） | **QUERY_AVAILABILITY**（查可约时段，可能要调外部系统） |
| 生成 | GEN_SQL → VALIDATE | — |
| 交互 | **（无）** | **CLARIFY**（缺槽位 / 多候选 → 反问） |
| 执行 | EXECUTE（只读） | **CONFIRM → COMMIT（写）** |
| 修复 | REPAIR（SQL 报错） | 补偿（提交失败 / 部分成功） |
| 呈现 | CHART → ANSWER | ANSWER（卡片：预约号 + 时间 + 地点） |
| 落库 | PERSIST（记录） | PERSIST（预约记录 + 幂等键） |

**结论：节点能对上，机制对不上。** §6.21 的 7 阶段里有 `ACT`，§6.20 的候选池里有 `CLARIFY`，位置都留了；但四个关键机制是空的。

#### 6.31.3 四个真缺口

| # | 缺口 | 现状 | 后果 |
| --- | --- | --- | --- |
| 1 | **槽位状态** | `MemoryStore` 存的是对话轮次，不存结构化槽位 | 每轮都要从历史里重新抽一遍，抽错一次就漂移；用户说"改成 3 点"时无法定位改哪个槽 |
| 2 | **写操作** | `ACT` 阶段有位置，没有 `ActionPort`、没有幂等、没有补偿 | 用户重复点击 = 重复预约；提交成功但回复超时 = 用户以为失败又提一次 |
| 3 | **反问策略** | `CLARIFY` 只是候选节点，没有规则 | 让模型自由决定问什么 → 每次问法不同、漏问、重复问；且**不会先查可用时段再问** |
| 4 | **确认与撤销** | §6.17 提了 `HumanInTheLoop`，没落地 | 直接提交高风险写操作；用户想取消只能人工介入 |

#### 6.31.4 槽位即 schema

槽位定义复用 §6.15 的 `ToolSpec` 形状，不另造一套：

```java
public final class SlotSpec {

    public final String name;          // doctorId
    public final SlotType type;        // STRING / ENUM / DATE / TIME_RANGE / REF
    public final boolean required;
    public final boolean derivable;    // 可从其它槽位反查（科室 ← 医生）
    public final String questionKey;   // i18n key，不由模型生成文案
    public final List<String> options; // ENUM 的候选
}

public final class SlotState {

    public final String name;
    public final Object value;
    public final SlotSource source;    // USER / DERIVED / DEFAULT / EXTERNAL
    public final double confidence;    // 低于阈值按"未填"处理
}

public interface SlotStore {

    SlotState get(CallContext ctx, String sessionId, String name);

    void put(CallContext ctx, String sessionId, SlotState state);

    /** 本次会话的槽位快照，用于幂等键与确认复述。 */
    Map<String, SlotState> snapshot(CallContext ctx, String sessionId);

    void clear(CallContext ctx, String sessionId);
}
```

三条规则：

1. **槽位状态独立于对话历史**：历史可以被裁剪（§6.9 的窗口），槽位不能——裁剪后仍然要能续填。
2. **`derivable` 的槽位不问**：医生选定了，科室反查即可；问了就是噪音。
3. **`confidence` 低于阈值按未填**：用户说"下周三左右"，不能当成确定了日期。

#### 6.31.5 服务目录与选项型槽位（人工选择）

> 通用协议（帧结构、四种触发、数量分档、前端契约）见 §6.32；本节只讲预约特有的目录与时段。

用户不该"凭空说"能约什么。系统必须先给出**可选服务目录**，用户从中选；AI 的角色是把目录变成"一句可读的问题 + 一组结构化选项"。

四类槽位，来源不同：

| 槽位类型 | 选项来源 | 例子 | 数量级 |
| --- | --- | --- | --- |
| 静态枚举 | 配置 | 时段（上午 / 下午）、证件类型 | ≤ 10 |
| **目录查询** | 业务表 | 预约服务（体检 / 门诊 / 咨询）、医生、科室 | 10 ~ 1000 |
| 外部实时查询 | 第三方系统 | 可约时段、号源 | 每次不同 |
| 自由输入 | 用户 | 姓名、备注、症状描述 | — |

**关键：目录是数据，不是 prompt。** 不能把 200 个服务名塞进 prompt 让模型选——会超 token、会幻觉出不存在的服务、也无法分页。

选项型槽位的契约（在 §6.31.4 的 `SlotSpec` 上补三个字段）：

```java
public final class SlotSpec {

    // ... §6.31.4 已有字段
    public final OptionSource optionsSource;  // STATIC / CATALOG / EXTERNAL
    public final String catalogKey;           // CATALOG: 查哪张表 / 哪个接口
    public final int maxOptionsInline;        // 一次最多回传几个，默认 5
}

public final class SlotOption {

    public final String id;                  // 提交时用它，不用 label
    public final String label;               // 展示文案
    public final Map<String, Object> meta;   // 价格 / 时长 / 地点
    public final long validUntil;            // 可约时段的过期时间；0 = 不过期
}
```

**反问帧带选项，不是纯文本**：

```json
{
  "type": "clarify",
  "slot": "serviceId",
  "text": "请选择要预约的服务",
  "options": [
    {"id": "svc_101", "label": "常规体检", "meta": {"duration": 60, "price": 380}},
    {"id": "svc_102", "label": "入职体检", "meta": {"duration": 45, "price": 260}}
  ],
  "hasMore": true,
  "cursor": "c_8f2a"
}
```

前端渲染成按钮 / 卡片，用户点选后回填槽位。**用户提交的是 `id`，不是 `label`**——label 会重名（两个"常规体检"套餐），id 不会。

选项数量分档（这条最容易被忽略）：

| 候选数 | 交互 | 为什么 |
| --- | --- | --- |
| ≤ 5 | 全部平铺 | 一眼看完 |
| 6 ~ 20 | 分组 + 搜索 | 平铺太长 |
| > 20 | 必须搜索 + 分页（cursor） | 和 §6.27 同一个道理：不要一次给完 |

选项过期：

- 可约时段、号源是**实时数据**；`validUntil` 到期或提交时校验失败 → 重新拉目录并提示"刚才的时段已被约走，这是最新的"；
- 不要静默替换成另一个时段——用户会以为约错了。

三条红线：

1. **不让模型生成选项**。模型可以排序、可以推荐（"根据您上次的记录，推荐入职体检"），但选项必须来自目录，`id` 必须可校验。
2. **不让模型决定问几个**。按 `maxOptionsInline` 与候选总数由代码决定，否则模型会把 50 个选项念一遍。
3. **选项与提交必须同源**。提交时用 `id` 回查目录做校验，不能信任前端传来的 label。

对通用内核的影响：`CLARIFY` 帧从"纯文本"变成"文本 + 选项 + 分页游标"，§6.24 的 REST / MCP 对外契约也要一并支持这个形状。

#### 6.31.6 反问必须是确定性的

> 通用规则见 §6.32.2 / §6.32.5；这里只列预约场景的具体顺序。

预约的"缺参数"不是让模型判断，是**代码判定 + 模型只负责表达**：

```text
1. 计算 missing = { required && !derivable && 未填或低置信 }
2. 若 missing 非空：
   a. 若某槽位需要候选（时段 / 医生），先调 ToolPort 查可用性
   b. 一次最多问 3 个，按 SlotSpec 声明顺序
   c. 文案用 questionKey + 参数渲染，不由模型自由生成
3. 若 missing 为空且存在歧义（多个候选）→ 让用户选，不替用户决定
4. 若 missing 为空且无歧义 → 进入 CONFIRM
```

为什么文案不让模型写：问数的答案可以是"生成的"，但**追问必须是稳定的**——同一场景今天问"请问您想约哪位医生"，明天问"您要预约哪个医生呢"，用户会以为是不同系统。文案走 i18n，模型只填参数。

一次最多问 3 个：超过就变成审问式，用户会放弃。

#### 6.31.7 写操作的三条红线

| 红线 | 做法 |
| --- | --- |
| **幂等** | `idempotencyKey = hash(uid + sessionId + 槽位快照)`；同键重复提交返回首次结果，不再写库 |
| **先校验后提交** | 可用性（时段冲突 / 资源占用）用确定性代码校验，模型不参与；校验通过才允许 `CONFIRM` |
| **补偿** | 提交成功但回复失败 → 记录可查（用户凭幂等键重试拿到同一结果）；提交失败 → 明确报错，**不自动重试写操作** |

对应到端口：

```java
public interface ActionPort {

    /** 只读预校验：能不能做，不做副作用。 */
    ActionResult check(CallContext ctx, ActionRequest req);

    /** 提交；同 idempotencyKey 幂等。 */
    ActionResult commit(CallContext ctx, ActionRequest req);
}
```

`CallContext` 需要加 `idempotencyKey`（§6.16 的基元里目前没有）。

#### 6.31.8 共用与差异

| 维度 | 问数 | 预约 | 是否共用 |
| --- | --- | --- | --- |
| 鉴权 / 租户 | ✅ | ✅ | 共用 §6.19 |
| 输入归一 | ✅ | ✅ | 共用 §6.22 |
| 意图抽取 | 实体 + 时间 | 槽位 | 共用 `IntentPort`，输出结构不同 |
| 观测 | ✅ | ✅ | 共用 §6.11 |
| 护栏 | SQL / 输出 | 槽位值 / 写操作 | 共用 `GuardrailPort`，规则不同 |
| 流式 | 必需 | 不必要（结构化卡片） | 可共用，但预约更适合同步 JSON |
| 记忆 | 对话轮次 | 槽位状态 | **不共用**（新增 `SlotStore`） |
| 执行 | 只读 SQL | 写入 + 外部调用 | **不共用**（新增 `ActionPort`） |
| 修复 | SQL 重试 | 补偿 | **不共用** |

#### 6.31.9 对现有设计的影响

| 现有 | 需要改成 |
| --- | --- |
| §6.20 `CLARIFY` 是候选节点 | 对非问数场景，它是**必需节点**（P1 #24 要从"可选"提到"按 domain 必需"） |
| §6.21 `ACT` 阶段只有位置 | 需要补 `ActionPort` 契约（check / commit / 幂等 / 补偿） |
| §6.9 `MemoryStore` | 新增并列的 `SlotStore`，两者职责不同，不要合并 |
| §6.16 `CallContext` | 加 `idempotencyKey` |
| §6.13 可选能力清单 | 加一行：`ActionPort` 默认关闭（问数用不到），但必须有实现 |
| §6.15 `ToolPort` | 反问时查候选要走它（`QUERY_AVAILABILITY`），不要绕过 |

#### 6.31.10 验收（如果真要做预约）

| 项 | 判定 |
| --- | --- |
| 一句话给全信息（医生 + 时间） | 不反问，直接进确认 |
| 缺医生 | 反问 1 次，文案来自 i18n key |
| 只给"下周三左右" | 按低置信处理，反问具体日期 |
| 医生说"张医生"但有两个张医生 | 列候选让用户选，不替用户决定 |
| 用户中途改时间 | 只改时间槽，其它槽保留 |
| 同一请求重复提交 | 只产生一条预约记录 |
| 提交成功但连接断开 | 用户重试拿到同一预约号 |
| 会话历史被裁剪 | 槽位仍在，能继续填 |
| 外部系统超时 | 明确报错，不自动重试写 |

#### 6.31.11 结论：通用流程的边界在哪

- **能复用**：鉴权、输入归一、意图抽取框架、观测、护栏、错误模型、i18n 文案体系。
- **不能复用**：记忆（槽位 vs 轮次）、执行（写 vs 读）、修复（补偿 vs 重试）、呈现（卡片 vs 流式文本）。
- **一句话**：这套流程是**"只读问答"的通用内核**，不是"任何业务流程"的通用内核。要做预约类，需要补 `SlotStore` + `ActionPort` + 确定性反问三件套——位置已经留好（`ACT` 阶段、`CLARIFY` 节点），但机制是空的。

**但这不是"另开一套系统"**：三件套走 §6.13 的可选能力机制——默认关闭、必须有实现、关闭时问数主链帧逐字节不变。预约场景通过 domain pack 编排，不往问数主链里塞分支（不要写 `if (booking)` 这种判断）。

落地顺序见 `enhancement-plan.md` 的 **P1-b**（B1~B7）：槽位 → 目录 → 反问 → 写操作 → 确认 → domain pack → 验收集。
### 6.32 反问与选项：通用交互协议

#### 6.32.1 为什么是通用能力，不是预约专属

问数同样天天需要反问，只是现在都靠模型"自己看着办"：

| 场景 | 反问什么 | 选项来源 |
| --- | --- | --- |
| 多数据源 | "你有 3 个 MySQL 数据源，用哪个？" | 目录 |
| 多候选表 | "找到 2 张订单表：`orders` / `order_item`，查哪个？" | 目录（schema） |
| 指标口径 | "销售额按含税还是不含税？" | 静态 |
| 时间不清 | "'上季度'指 2025Q3 还是财年 Q3？" | 静态 |
| 越界 | "只能查近 12 个月，要改成近 12 个月吗？" | 静态 |
| 高成本确认 | "将导出 120 万行，继续？" | 静态 |

所以反问不是预约的功能，是**整条流程的交互原语**。§6.20 的 `CLARIFY` 节点就是它；之前把它当"候选节点"，是低估了。

#### 6.32.2 四种触发，全部由代码判定

| 触发 | 判定条件 | 例子 |
| --- | --- | --- |
| `MISSING` | 必填槽位未填或置信度低于阈值 | 没说时间范围 |
| `AMBIGUOUS` | 候选 > 1 且无法排序 | 两个"张医生" |
| `OUT_OF_RANGE` | 请求超出能力 / 权限 / 配额 | 查 36 个月，上限 12 |
| `CONFIRM` | 写操作或高成本操作前 | 提交预约、导出 120 万行 |

模型只做两件事：**把判定结果说成人话**、**给候选排序/推荐**。判定本身必须由代码做——让模型判断"缺不缺参数"，就会今天问、明天不问。

#### 6.32.3 反问帧协议

```json
{
  "type": "clarify",
  "id": "clr_01HQ8Z...",
  "reason": "MISSING",
  "slot": "datasourceId",
  "text": "你有 3 个数据源，用哪个？",
  "options": [
    {"id": "ds_1", "label": "生产库（MySQL）", "meta": {"host": "10.0.0.1"}},
    {"id": "ds_2", "label": "测试库（MySQL）", "meta": {}}
  ],
  "multi": false,
  "hasMore": false,
  "cursor": null,
  "expiresAt": 1730000000000
}
```

| 字段 | 作用 |
| --- | --- |
| `id` | 本次反问的唯一标识，用户回填时必须带回——否则多轮并发时会答错问题 |
| `reason` | 四种触发之一，前端据此决定交互形态（缺参 = 选择器，确认 = 二次确认框） |
| `options[].id` | **提交值**；`label` 只用于展示，会重名 |
| `multi` | 是否多选 |
| `hasMore` + `cursor` | 大目录分页，复用 §6.27 的 cursor 思路 |
| `expiresAt` | 过期后前端禁用选项，避免选到已失效的时段/号源 |

回填帧：

```json
{"type": "clarify_answer", "clarifyId": "clr_01HQ8Z...", "slot": "datasourceId", "valueIds": ["ds_1"]}
```

用户用自然语言回答（"用第一个"）也可以，但**必须先解析成 `valueIds` 再走同一套校验**，不能直接当文本塞进 prompt。

#### 6.32.4 选项来源与数量分档

| 来源 | 例子 | 数量级 | 取法 |
| --- | --- | --- | --- |
| 静态枚举 | 含税 / 不含税、上午 / 下午 | ≤ 10 | 配置 |
| 目录 | 数据源、表、医生、预约服务 | 10 ~ 1000 | `CatalogPort` 查业务表 |
| 实时 | 可约时段、号源 | 每次不同 | 外部系统，带 `expiresAt` |
| 自由输入 | 姓名、备注 | — | 不出 `options`，声明 `freeText: true` |

| 候选数 | 交互 |
| --- | --- |
| ≤ 5 | 全部平铺 |
| 6 ~ 20 | 分组 + 搜索 |
| > 20 | 搜索 + 分页（`cursor`） |

**目录是数据，不是 prompt**：200 个表名塞进 prompt 会超 token、会幻觉、也没法分页。

#### 6.32.5 五条硬约束

1. **一次最多问 3 个**。超过 3 个说明槽位设计有问题，不是用户的问题。
2. **同一 slot 不连续问两次**。第一次没答清楚，第二次要换问法或给示例，不能原样重复。
3. **文案走 i18n key，不由模型生成**。同一场景今天问"您想约哪位医生"，明天问"您要预约哪个医生呢"，用户会以为是两个系统。
4. **选项必须可校验**。提交时按 `id` 回查目录；不接受"前端传来的 label"当值。
5. **反问不能无限循环**。设最大反问轮次（默认 3），超过则降级为"取默认值 + 明确告知取了什么"。

#### 6.32.6 前端契约

- 渲染：`reason=CONFIRM` 用二次确认框；其余用选择器/卡片；`freeText` 用输入框。
- 回填：点选直接回 `valueIds`；自由输入先本地解析再回填。
- 超时：前端 5 分钟无响应提示"已过期，请重新提问"；服务端 `expiresAt` 兜底。
- **不打断流**：`clarify` 是中间帧，不是终态；收到后前端保持会话，不重建连接。

#### 6.32.7 与 Pipeline 的关系

- `CLARIFY` 位于 `UNDERSTAND` 与 `GENERATE` 之间（§6.20），也用于 `ACT` 之前的 `CONFIRM`。
- 一次会话内可多次反问，受最大轮次限制；反问本身不产答案帧。
- 观测指标：**反问率**、**反问后完成率**、**平均反问轮次**——这三个是质量指标，进 §6.14 的评估集。
- 开关：`askdata.ai.clarify.enabled`，关闭时走默认值/首个候选，行为回到今天（不阻塞）。

#### 6.32.8 验收

| 项 | 判定 |
| --- | --- |
| 多数据源未指定 | 反问 1 次，选项来自目录，点选后继续 |
| 两个同名医生 | 列候选，不替用户选 |
| 用户回"用第一个" | 正确映射到 `valueIds` |
| 选项过期后提交 | 明确提示"已失效"，重新拉目录 |
| 连续 3 次仍未确定 | 降级取默认值 + 告知 |
| 同场景两次提问 | 文案一致（i18n） |
| 关闭反问开关 | 走默认值，不阻塞，帧与基线一致 |
| 反问期间连接 | 不断流，不重建 |

#### 6.32.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 让模型生成选项列表 | 会幻觉出不存在的表/服务，也无法分页 |
| 无限反问 | 用户会放弃；3 轮封顶 |
| 纯文本反问（不带选项、不声明自由输入） | 用户不知道该答什么格式 |
| 反问判定交给模型 | 今天问明天不问，不可测 |
| 把 `label` 当提交值 | 重名必错 |
### 6.33 人工确认机制（Human-in-the-loop）

#### 6.33.1 什么时候必须让人点头

| 触发 | 例子 | 风险 |
| --- | --- | --- |
| 写操作 | 提交预约、删除记录、改权限 | 不可逆 |
| 高成本 | 导出 120 万行、批量重跑 | 烧钱、占资源 |
| 越权边缘 | 跨工作空间查询、查非本人会话 | 合规 |
| 模型低置信 | 抽取的实体置信度 < 阈值但用户坚持执行 | 错答 |

反问（§6.32）解决"信息不够"，确认解决"要不要做"。两者都是中间帧，都不产最终答案。

#### 6.33.2 四级确认

| 级别 | 交互 | 适用 |
| --- | --- | --- |
| L0 无确认 | 直接执行 | 只读查询、小结果集导出 |
| L1 轻确认 | 一个按钮（"继续导出"） | 大文件导出、跨空间只读 |
| L2 复述确认 | 展示**将要执行的具体动作与参数**，用户核对后点确认 | 预约提交、删除单条、批量修改 |
| L3 双人确认 | 第二个人审批 | 删数据源、改权限、批量删除 |

分级由 `ConfirmGate` 按"动作类型 × 影响行数 × 是否可逆"判定，**不由模型判断**。

#### 6.33.3 确认必须"可核对"，不能只问"确认吗"

```text
❌ 确认提交预约吗？                     ← 用户不知道确认了什么
✅ 确认预约：
   医生：张医生（内科）
   时间：3 月 12 日 14:00–14:30
   地点：门诊楼 3 层
   费用：¥380（医保可报）
   [确认]  [取消]
```

规则：**确认框里出现的每个值，都必须来自已确定的槽位/参数，不能是模型现编的**。用户核对的是"系统理解对不对"，不是"这段话通不通"。

#### 6.33.4 确认令牌（confirmToken）：防"确认 A、提交 B"

确认与提交是两次请求，中间状态会变（时段被约走、权限被改）。必须用令牌绑定：

```java
public final class ConfirmToken {

    public final String token;        // 服务端签发，一次性
    public final String actionDigest; // hash(动作类型 + 参数快照)
    public final long uid;
    public final long expiresAt;      // 默认 5 分钟
}
```

提交时校验三件事：

1. token 未使用过、未过期；
2. `actionDigest` 与本次提交的参数**完全一致**——不一致说明用户在确认后改了参数，必须重新确认；
3. 提交前**再校验一次可用性**（时段是否还在、权限是否还有）。

第 2 条是核心：没有它，攻击者可以拿"确认导出 10 行"的 token 去提交"导出 100 万行"。

#### 6.33.5 确认令牌 ≠ 幂等键

| | confirmToken | idempotencyKey |
| --- | --- | --- |
| 目的 | 防止"确认的和提交的不是一件事" | 防止"同一次提交被执行多次" |
| 生命周期 | 一次性，用后作废 | 固定窗口（24h），可重复提交同一键 |
| 绑定 | 动作摘要 + 参数快照 | 用户 + 会话 + 参数快照 |
| 失败重试 | **需要重新确认**（token 已用） | 直接返回首次结果 |

两者都要，不能合并——合并后要么重试要重新确认（体验差），要么确认可被复用（安全漏洞）。

#### 6.33.6 超时、撤销与失败

| 情况 | 行为 |
| --- | --- |
| 确认框 5 分钟无响应 | token 过期，前端提示"已失效，请重新发起" |
| 用户取消 | token 作废，不产生任何副作用 |
| 提交失败（可重试） | 用同一 `idempotencyKey` 重试，**不需要重新确认** |
| 提交失败（参数已变） | 重新走确认，展示新的参数 |
| 提交成功但响应丢失 | 用户重试拿到同一结果（幂等） |
| 提交后发现错误 | 走撤销/补偿接口，不走"重新提交" |

#### 6.33.7 审计

每次确认至少记四条：**谁确认的、确认了什么（actionDigest）、什么时候、结果如何**。审计与 §6.29.6 的加固清单同一条线——写操作没有审计等于没有确认。

#### 6.33.8 与现有设计的关系

| 现有 | 关系 |
| --- | --- |
| §6.32 反问 | 反问解决缺参（`reason=MISSING/AMBIGUOUS`），确认解决执行（`reason=CONFIRM`），共用同一帧结构 |
| §6.31.7 写操作红线 | 确认是三条红线的前置：先校验、再确认、最后提交 |
| §6.17 `HumanInTheLoop` | agentic 侧的确认入口，落地形态就是本节 |
| §6.13 可选能力 | `ConfirmGate` 默认关闭（问数只读用不到），但必须有实现 |
| §6.16 `CallContext` | 需要加 `idempotencyKey` 与 `confirmToken` |

#### 6.33.9 验收

| 项 | 判定 |
| --- | --- |
| 预约提交（L2） | 确认框展示医生/时间/地点/费用，全部来自槽位 |
| 确认后改了时间再提交 | 被拒，要求重新确认（digest 不一致） |
| 同一 token 提交两次 | 第二次拒绝 |
| 确认后 6 分钟才提交 | 过期，要求重新确认 |
| 提交失败后重试 | 不需重新确认，返回首次结果 |
| 取消确认 | 无任何副作用，无残留记录 |
| 关闭确认开关（只读场景） | 不弹确认，行为与今天一致 |
| 审计 | 能查到"谁在什么时候确认了哪个动作、结果如何" |

#### 6.33.10 不做的事

| 不做 | 理由 |
| --- | --- |
| 所有操作都加确认 | 只读查询也弹框，用户会关掉整个功能 |
| 只问"确认吗"不展示参数 | 用户无法核对，等于没确认 |
| 确认 token 可重复使用 | 一次确认只能对应一次执行 |
| 用 confirmToken 代替幂等键 | 职责不同，合并必出问题（§6.33.5） |
| 让模型决定是否需要确认 | 高风险操作必须由规则判定 |
### 6.34 Skill：本地与远程能力包

#### 6.34.1 现状：只有 tool 和 domain pack，中间是空的

| 现状 | 证据 |
| --- | --- |
| 全仓没有 skill 概念 | grep 只命中 `node_modules`（playwright 自带的） |
| 现有的"可插拔"只是切后端 | `@ConditionalOnProperty` 切 `askdata.lock.type`（db / redis），不是能力包 |
| 有 Tool，有流程，没有"任务模板" | §6.15 `ToolPort` 是单个函数；§6.21 `DomainPack` 是编译期编排 |

缺的这一层，正是"给模型一份可复用的任务说明书 + 限定它能用哪些工具"。

#### 6.34.2 三层区分（先分清，再设计）

| 层 | 是什么 | 谁定义 | 粒度 | 例子 |
| --- | --- | --- | --- | --- |
| **Tool** | 一个函数 | 代码 / `@Tool` | 单次调用 | `exec_sql` |
| **Skill** | 指令 + 工具白名单 + 输入输出契约 + 权限 | 配置文件 / 远程 registry | 一次任务 | "生成月度经营分析报告" |
| **DomainPack** | 流程编排（阶段 + 步骤） | 代码 | 整条链 | 问数 pack |

一句话：**Tool 是原子能力，Skill 是可插拔的任务模板，DomainPack 是编译期流程**。三者不要互相替代。

#### 6.34.3 本地 Skill

形态（借鉴 Claude Code 的 `SKILL.md`：元数据给程序，指令给模型）：

```text
skills/monthly-report/
├── SKILL.md          # frontmatter（id/version/inputs/tools/permissions）+ 指令正文
├── prompt.md         # 可选：分步提示词
└── assets/           # 可选：模板、示例
```

```yaml
---
id: monthly-report
version: 1.2.0
description: 按月份生成经营分析报告（含同比、环比、Top 10 区域）
inputs:
  - name: month
    type: DATE_RANGE
    required: true
  - name: datasourceId
    type: REF
    required: true
    optionsSource: CATALOG
tools: [exec_sql, chart_render, export_xlsx]
permissions: [datasource:read, export:write]
requiresConfirm: false
---
```

加载：启动时扫描 `classpath:skills/` 与 `file:./skills/`，**外部目录优先**（客户自定义不必改包）。

| 优点 | 缺点 |
| --- | --- |
| 随版本发布、可离线、可审计、无网络依赖 | 改一次要发版 |

#### 6.34.4 远程 Skill

发现与调用与 MCP 同构（`list → describe → invoke`），transport 可以是 MCP 或纯 HTTP：

| 环节 | 做法 |
| --- | --- |
| 注册 | 管理员配 registry 地址 + 凭据，落 `skill_registry` 表（含 `enabled` / `last_ok_at`） |
| 发现 | 启动时 `list`，结果缓存 5 分钟；registry 挂了用缓存 |
| 冷启动无缓存 | 该 registry 下的 skill **不可见**，不阻塞主链 |
| 版本 | `skillRef = id@version`；**不自动跟随 latest**，升级要显式操作 |
| 健康 | 复用 §6.26.5 的熔断（失败 3 次 → OPEN，冷却 30 秒） |

```sql
CREATE TABLE skill_registry (
    id          BIGINT PRIMARY KEY,
    oid         BIGINT NOT NULL DEFAULT 0,   -- 0 = 全局
    name        VARCHAR(128) NOT NULL,
    base_url    VARCHAR(512) NOT NULL,
    credential  VARCHAR(4000),               -- 加密存储，复用 CryptoUtil
    transport   VARCHAR(16) NOT NULL,        -- mcp / http
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    last_ok_at  BIGINT,
    fail_count  INT NOT NULL DEFAULT 0
);
```

#### 6.34.5 统一抽象

```java
public interface SkillPort {

    List<SkillDescriptor> list(CallContext ctx);

    SkillDescriptor describe(CallContext ctx, String skillRef);

    SkillResult invoke(CallContext ctx, String skillRef, Map<String, Object> input);
}

public final class SkillDescriptor {

    public final String id;
    public final String version;
    public final String description;          // ≤ 200 字，进 prompt 用
    public final List<SlotSpec> inputs;       // 复用 §6.31.4，直接接上反问
    public final List<String> outputs;
    public final Set<String> toolWhitelist;   // 该 skill 只能用这些 tool
    public final Set<String> permissions;     // 需要的权限点
    public final boolean requiresConfirm;     // 为 true 走 §6.33 的 L2
    public final SkillSource source;          // LOCAL / REMOTE
    public final String transport;            // in-process / mcp / http
}
```

三个字段是关键设计：

- `inputs` 用 `SlotSpec` → **skill 缺参数时自动反问**（§6.32），不用另写一套；
- `toolWhitelist` → 最小权限，skill 不能调白名单外的 tool；
- `permissions` → 与 §6.19 / §6.29 的鉴权链打通，不是另起一套。

#### 6.34.6 描述注入的 token 预算（最容易翻车）

| skill 数量 | 做法 |
| --- | --- |
| ≤ 20 | 描述全量注入（每个 ≤ 200 字，约 2k token 以内） |
| > 20 | 两阶段：先按关键词 / 向量召回 top-5，只注入这 5 个的完整描述；调用前再 `describe` |

不做这个，50 个 skill 的描述就会吃掉几千 token，而且模型选错的概率随数量上升。

#### 6.34.7 选择与编排

- **单 skill**：意图识别直接命中（`IntentPort` 输出 `skillRef`）。
- **多 skill 组合**：交给 §6.17 的 agentic（supervisor / planner），**不要在主链里写死组合**。
- **冲突**：同名 skill 本地优先（可配置），并在日志里记冲突。

#### 6.34.8 安全：远程 skill 等于第三方代码

| 风险 | 措施 |
| --- | --- |
| 结果注入 | 远程返回内容按"用户输入"处理，过护栏 L0 |
| 越权 | 调用前校验 `permissions`；`toolWhitelist` 兜底 |
| 数据外流 | 只传必要参数，**不传 api_key、不传全量 schema** |
| 供应链 | registry 地址白名单（复用 §6.29.3 的 `OutboundUrlGuard`）+ 版本锁定 + 可选摘要校验 |
| 资源 | 超时 + 并发上限 + 熔断 |
| 审计 | 每次 invoke 记 `skillRef` + 输入摘要 + 结果摘要 + 耗时 |

#### 6.34.9 生命周期

| 类型 | 发布 | 灰度 | 回滚 |
| --- | --- | --- | --- |
| 本地 | 随版本发版 | 部署灰度 | 回退版本 |
| 远程 | registry 侧发布 | 按 `oid % 100` 灰度（§6.26.8） | 改 `skillRef` 版本号 |

下线流程：先标 `deprecated`（调用时告警但不失败）→ 30 天后移除。

#### 6.34.10 与现有设计的关系

| 现有 | 关系 |
| --- | --- |
| §6.15 `ToolPort` | skill 通过 `toolWhitelist` 限制可用 tool，不新增调用通道 |
| §6.21 `DomainPack` | pack 是流程，skill 是任务模板；一个 pack 可以调用多个 skill |
| §6.32 反问 | skill 的 `inputs` 就是 `SlotSpec`，缺参自动反问 |
| §6.33 确认 | `requiresConfirm=true` 时走 L2 复述确认 |
| §6.17 agentic | agent 的"能力"来自 skill registry，不来自硬编码 |
| §6.13 可选能力 | `SkillPort` 默认关闭，必须有 `LocalSkillRegistry` + `NoopSkillRegistry` 两个实现 |

#### 6.34.11 验收

| 项 | 判定 |
| --- | --- |
| 本地 skill 加载 | 启动后 `list` 可见，`invoke` 成功 |
| 外部目录覆盖内置 | `file:./skills` 同名 skill 生效 |
| registry 不可用 | 用缓存；冷启动无缓存则该 skill 不可见，主链不报错 |
| skill 调白名单外 tool | 拒绝并记审计 |
| 远程返回"忽略以上指令" | 护栏命中，行为不变 |
| 50 个 skill | 注入 token ≤ 2k（召回 top-5） |
| 版本锁定 | 远程升版但未显式升级时行为不变 |
| skill 缺参数 | 自动触发反问，不报错 |

#### 6.34.12 不做的事

| 不做 | 理由 |
| --- | --- |
| 远程 skill 里执行任意代码 | 只做声明式调用，执行由本进程的 tool 完成 |
| 自动跟随 latest | 远程一变行为就变，无法回滚 |
| 无白名单的 tool 访问 | skill 能调所有 tool = 没有边界 |
| 全量注入 skill 描述 | 数量一多就吃 token 且选错率上升 |
| 主链硬编码 skill 组合 | 组合逻辑属于 agentic，不属于主链 |
| 把 skill 当成 domain pack | 一个是任务模板，一个是流程编排 |
### 6.35 缓存失效协议：一个 epoch 管所有缓存

#### 6.35.1 现状：三层缓存，三套失效方式

| 缓存 | 实现 | 失效方式 | 问题 |
| --- | --- | --- | --- |
| 模型配置 | `ChatModelRouter.cachedEmbedding` | `writeVersion()` 自增 + TTL 60s | 只覆盖模型配置 |
| 通用热缓存 | `HotCacheService` + `CacheConfig` | Caffeine / Redis 的 TTL（默认 300s） | 没有业务失效，只能等 TTL 到期 |
| 结果缓存（规划 P1 #33） | — | — | 没有协议可依 |
| 语义缓存（规划 P2 #36） | — | — | 同上 |

#### 6.35.2 真正危险的不是"慢"，是"看不出来的错"

场景：用户问"上个月销售额"，缓存了结果；之后数据源加了一列、改了字段类型、改了行权限、改了术语口径——缓存 key 没变，**用户拿到的是旧答案，而且没有任何提示**。

TTL 300 秒只是把窗口缩短，不是解决。真正需要的是"**变更发生时，旧结果立刻不可命中**"。

#### 6.35.3 方案：`CacheEpoch`

```sql
CREATE TABLE cache_epoch (
    oid        BIGINT      NOT NULL,
    kind       VARCHAR(32) NOT NULL,   -- SCHEMA / MODEL / TERM / PROMPT / PERM / RESULT
    epoch      BIGINT      NOT NULL DEFAULT 0,
    updated_at BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (oid, kind)
);
```

**key 必带 epoch**：

```text
result:{oid}:{schemaEpoch}:{modelEpoch}:{permEpoch}:{sha256(question + dsId)}
semantic:{oid}:{schemaEpoch}:{modelEpoch}:{vectorId}
```

**推进 epoch 是 O(1)**：一条 `UPDATE ... SET epoch = epoch + 1`，不需要遍历删除任何 key——旧 key 因为前缀变了，永远不会再被命中，等 TTL 自然回收。

读取流程：

```text
1. 读本地 epoch 缓存（5 秒 TTL）；miss 则查 DB
2. 用 epoch 组 key
3. 命中 → 返回
4. miss → 计算 → 写缓存（带同一 epoch）
```

#### 6.35.4 失效矩阵：什么事件推进哪个 kind

| 事件 | SCHEMA | MODEL | TERM | PROMPT | PERM | RESULT |
| --- | --- | --- | --- | --- | --- | --- |
| 数据源表结构变更（DDL 探测到差异） | ✓ | | | | | ✓ |
| 数据源增删改（地址/账号/库名） | ✓ | | | | | ✓ |
| 模型配置改（默认模型/密钥/参数） | | ✓ | | | | ✓ |
| 术语 / 训练数据改 | | | ✓ | | | ✓ |
| prompt 改 | | | | ✓ | | ✓ |
| 权限 / 工作空间成员变更 | | | | | ✓ | ✓ |
| 向量索引重建完成 | | ✓ | | | | ✓ |

**权限变更必须推 RESULT**：结果缓存不能跨权限复用，否则 A 用户的结果会被 B 用户命中。

#### 6.35.5 三条规则

1. **epoch 只增不减**，回滚时也不减——回滚后旧代码用新 epoch，最坏是缓存全冷，不会读到错数据。
2. **key 必带 epoch**，任何不带 epoch 的结果缓存都是错的。
3. **不做遍历删除**：Redis `KEYS` / `SCAN` 删除在百万 key 下会拖垮实例，epoch 方案从根上避免了这件事。

#### 6.35.6 多实例与降级

| 情况 | 行为 |
| --- | --- |
| 多实例 | DB 为准；本地 epoch 缓存 5 秒，最多 5 秒不一致 |
| DB 不可用 | 用本地最后已知 epoch，**降级为可能读到旧值**，记告警 |
| 推进失败 | 重试 3 次；仍失败则记 ERROR 并让缓存继续用旧 epoch（不阻塞业务） |

#### 6.35.7 与现有设计的关系

| 现有 | 关系 |
| --- | --- |
| `ChatModelRouter.writeVersion()` | 保留，但只作为 MODEL 维度的快速路径；最终以 `cache_epoch` 为准 |
| `HotCacheService` | key 前缀加 epoch，其余不变 |
| P1 #33 结果缓存 | 必须按本节设计，否则不要上线 |
| P2 #36 语义缓存 | 同上；向量 ID 也要带 epoch |
| §6.26 熔断状态 | **不进 epoch**：熔断是运行时状态，与数据版本无关 |

#### 6.35.8 验收

| 项 | 判定 |
| --- | --- |
| 改数据源表结构 | 下一次提问不再命中旧结果 |
| 改行权限 | 被收回权限的用户不再命中缓存 |
| 改 prompt | 旧缓存失效，新答案生效 |
| 多实例 | A 实例推进 epoch 后，B 实例 5 秒内不再命中旧 key |
| 推进 epoch | 耗时 < 5ms，不影响请求 |
| DB 不可用 | 降级可用，有告警 |

#### 6.35.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 用 TTL 代替失效 | 窗口内就是错答案 |
| 用 `KEYS` / `SCAN` 批量删 | 大 key 量下会拖垮实例 |
| 不带 epoch 的结果缓存 | 一定会在 schema 变更后出错 |
| 把熔断状态塞进 epoch | 运行时状态与数据版本是两件事 |

---

### 6.36 质量基线：先把"答对"定义清楚

#### 6.36.1 为什么必须先定义

没有它，下面这些全是空的：

- §6.14 的评估集（5 个指标没有判定标准）
- P0 #16 的灰度（"变好还是变坏"无法判定）
- §6.26 的模型切换（换模型后是否退化）
- §6.35 的结果缓存（缓存命中返回的到底对不对）

#### 6.36.2 三层判定

| 层 | 判定内容 | 方式 |
| --- | --- | --- |
| **L1 可执行** | SQL 能跑通、只读、未超时、未越权 | 自动 |
| **L2 结果等价** | 列集合相同（顺序无关）、行集合相同（顺序无关）、数值容差 1e-6、NULL 相等、时区统一 UTC | 自动 |
| **L3 口径正确** | 与业务口径一致（两个不同 SQL 可能都"等价于参考结果"，但只有一个口径对） | 人工抽检 |

只做 L2 会漏掉"结果对了但口径错了"——比如"销售额"含税与不含税恰好相等时。所以 L3 不能省，但可以抽样。

#### 6.36.3 五个指标

| 指标 | 定义 | 目标 |
| --- | --- | --- |
| 执行成功率 | L1 通过 / 总数 | ≥ 98% |
| 结果匹配率 | L2 通过 / L1 通过 | ≥ 85%（起步基线，跑出来再定） |
| 口径正确率 | L3 通过 / 人工抽检数 | ≥ 90% |
| 拒答正确率 | 该拒的拒了 / 应拒总数 | ≥ 95% |
| 反问正确率 | 该问的问了 / 应问总数 | ≥ 90% |

"起步基线"的意思是：第一次跑出来的数就是基线，后续只比相对变化，不要一开始就定 95%。

#### 6.36.4 数据集怎么建

| 项 | 要求 |
| --- | --- |
| 规模 | 起步 50~100 条 |
| 分层 | 单表 / 多表 join / 时间范围 / 聚合 / 方言差异 / 边界（空结果、全 NULL）/ 应拒答 |
| 来源 | **从 `chat_record` 挑真实问题**，不要自己编（P2 #42） |
| 每条内容 | 问题 + 数据源 + 参考 SQL + 参考结果（或"应拒答 + 原因"） |
| 快照 | 数据源要固定快照，否则同一用例今天过明天不过 |

#### 6.36.5 参考结果怎么来（最容易做错）

- **用参考 SQL 在固定快照上执行一次，把结果落库**，不要每次重跑——重跑会随数据变化而漂移；
- 比较前先归一：列名大小写、别名去掉、行排序、浮点容差、`NULL` 与空串区分；
- 依赖"当前时间"的问题（"最近 7 天"）必须注入 `asOfDate`，数据源提供"截止某日"的视图或参数。

#### 6.36.6 门禁

| 时机 | 门槛 | 动作 |
| --- | --- | --- |
| 合并前 | 结果匹配率 ≥ 基线 − 5%（相对） | 不达标不允许合并 |
| 合并前 | 执行成功率 ≥ 98% | 同上 |
| 灰度 5% | 口径正确率抽检 ≥ 90% | 不达标停止扩量 |
| 线上 | 结果匹配率下降 > 10% | 自动回滚 |

#### 6.36.7 与现有设计的关系

| 现有 | 关系 |
| --- | --- |
| §6.14 `eval_case` / `EvalRunner` | 本节定义判定标准，§6.14 定义存储与运行 |
| P0 #16 灰度 | 门禁数据来自本节 |
| §6.26 模型切换 | 换模型前后各跑一次，对比五个指标 |
| §6.35 缓存 | 缓存命中时不再跑评估，但缓存正确性由 L2 保证 |

#### 6.36.8 验收

| 项 | 判定 |
| --- | --- |
| 50 条用例跑通 | 五个指标都有数，且可复跑 |
| 换模型 | 指标对比表能看出退化或提升 |
| 空结果用例 | 被判为"正确"或"应拒答"，不误判为失败 |
| 时区差异 | 不因时区导致误判 |
| 参考结果 | 从快照取，不随线上数据变化 |

#### 6.36.9 不做的事

| 不做 | 理由 |
| --- | --- |
| 用"模型给自己打分"当准确率 | 自评不可信，且不可复现 |
| 只做 L2 不做 L3 | 口径错但结果碰巧相等的情况会漏 |
| 每次评估都重跑参考 SQL | 数据变化会导致用例漂移 |
| 一开始就定 95% 目标 | 先跑出基线，再定目标 |
### 6.37 单次提问的全链路日志：一个 trace 看到底

#### 6.37.1 现状：有"步骤"，没有"链路"

`chat_log` 表其实已经有雏形（`V1__baseline.sql:232-247`）：

| 已有字段 | 作用 |
| `chat_record_id` / `oid` / `create_by` | 归属 |
| `operate` / `type` | 步骤名 |
| `start_time` / `finish_time` / `duration` | 耗时 |
| `error` | 是否失败 |
| `input_tokens` / `output_tokens` / `total_tokens` | 用量 |
| `message`（LONGTEXT） | 消息全文 |

缺的是四样：

| 缺 | 后果 |
| --- | --- |
| `trace_id` | 与 OTLP（§6.11）对不上，运维看 Jaeger/Langfuse、客服看产品页面，两套对不起来 |
| 父子关系（`parent_id` / `seq`） | 只有平铺列表，看不出"修复重试"是挂在"生成 SQL"下面的子步骤 |
| 结构化属性 | 看不出"这条 SQL 扫了多少行""这个模型是哪个""重试了几次"——只能从 `message` 里读全文 |
| 内容分级 | `message` 存全文，存储放大（附录 C.4 已指出），且无法控制敏感数据 |

#### 6.37.2 目标：一次提问 = 一棵 span 树

```text
trace (rid=123, traceId=4bf92f...)
├─ UNDERSTAND   意图抽取            320ms  OK
├─ RETRIEVE     召回 schema/术语     180ms  OK
├─ GENERATE     生成 SQL           1.2s   OK
├─ VALIDATE     安全校验             12ms  OK
├─ EXECUTE      执行 SQL           840ms  OK   rows=1523, truncated=false
│   └─ RETRY#1  连接超时重试          920ms  OK   attempt=2
├─ REPAIR       修复环（未触发）
├─ CHART        图表类型判定         410ms  OK   model=gpt-4o-mini
└─ ANSWER       生成回答           2.1s   OK   tokens=1.2k
```

**关键：这不是给运维看的 trace，是给"产品内的人"看的**——客服、实施、租户管理员要知道"这次为什么慢/为什么错"，不需要装 Jaeger。

#### 6.37.3 复用 `chat_log`，不新建表

| 改动 | 说明 |
| --- | --- |
| 加 `trace_id VARCHAR(32)` | 与 OTLP 同一个 ID（§6.11），一次生成两处用 |
| 加 `parent_id BIGINT` | 子步骤（重试、工具调用、模型调用）挂父步骤 |
| 加 `seq INT` | 同层顺序，避免同毫秒无法排序 |
| 加 `status VARCHAR(16)` | OK / ERROR / SKIP / TIMEOUT |
| 加 `error_kind VARCHAR(32)` | 复用 §6.16 的 `ErrorKind` |
| 加 `attrs JSON` | 结构化属性：`{rows, truncated, modelId, attempt, sqlDigest, ...}` |
| 改 `message` | 从"全文"改为"摘要"，全文另存（见 §6.37.4） |

不新建表的理由：已有数据、已有查询接口（`ChatRepository.getChatLogHistory`）、已有前端展示；新建表等于两套数据要同步。

#### 6.37.4 记录内容分三级

| 级别 | 内容 | 默认 | 开关 |
| --- | --- | --- | --- |
| **L0 骨架** | 步骤名、耗时、状态、`error_kind`、行数、token、模型 id、重试次数 | **全量必记** | 不可关 |
| **L1 摘要** | SQL digest（不存全文）、prompt 长度 + hash、结果列数 / 行数、召回条数 | 开 | 按租户可关 |
| **L2 全文** | prompt 全文、SQL 全文、结果前 10 行 | **关** | 需权限 + 每次查看写审计 |

为什么 L2 默认关：问数的 prompt 里可能带业务数据，SQL 里可能带表名与条件，结果可能带客户名单。默认存全文，等于把数据又复制一份到日志表。

#### 6.37.5 采样

| 请求类型 | 采样 |
| --- | --- |
| 失败（任何步骤 ERROR） | 100% |
| 慢（E2E > p95） | 100% |
| 正常 | 10%（可配） |

采样只影响 L1 / L2；**L0 永远全量**——否则"这次为什么错"根本查不到。

#### 6.37.6 查询接口

```http
GET /chat/record/{rid}/trace          # 一次提问的完整树
GET /chat/trace/{traceId}             # 跨记录（如一次导出触发的多条提问）
GET /chat/record/{rid}/trace/{spanId}/detail   # 取 L2 全文（需权限 + 审计）
```

返回结构：

```json
{
  "rid": 123,
  "traceId": "4bf92f3577b34da6",
  "e2eMs": 6120,
  "status": "OK",
  "spans": [
    {"spanId": "s1", "parentId": null, "seq": 1, "stage": "UNDERSTAND", "name": "意图抽取", "costMs": 320, "status": "OK", "attrs": {}},
    {"spanId": "s5", "parentId": null, "seq": 5, "stage": "EXECUTE", "name": "执行 SQL", "costMs": 840, "status": "OK", "attrs": {"rows": 1523, "truncated": false}},
    {"spanId": "s5-1", "parentId": "s5", "seq": 1, "stage": "EXECUTE", "name": "重试 #1", "costMs": 920, "status": "OK", "attrs": {"attempt": 2, "errorKind": "TIMEOUT"}}
  ]
}
```

#### 6.37.7 前端：抽屉时间线

- 每条回答下面一个"执行详情"入口，点开是时间线；
- 慢的步骤标黄（> p95 的 50%），失败标红；
- 点开某步看 `attrs`；L2 全文要二次点击（"查看完整内容"）并写审计；
- 不打断主流程：trace 是查询接口，不在 SSE 流里推。

#### 6.37.8 与 OTLP 的关系

| | OTLP（§6.11.6） | trace 表（本节） |
| --- | --- | --- |
| 消费者 | 运维 / SRE（Langfuse、LangSmith） | 客服 / 实施 / 租户管理员 |
| 内容 | 模型调用为主 | 全链路步骤 |
| 保留 | 按外部平台策略 | 90 天（L0）/ 7 天（L2） |
| 关联 | **同一个 `traceId`** | 同上 |

一份埋点，两个消费方。不要在 pipeline 里写两套打点。

#### 6.37.9 存储与清理

- L0/L1 保留 90 天，L2 保留 7 天（与 P0 数据生命周期一致）；
- 按 `start_time` 分区或定期删除，**不要**依赖软删除；
- `attrs` 用 JSON 列（MySQL 5.7+ / PG jsonb），不要 LONGTEXT——要能按属性过滤。

#### 6.37.10 验收

| 项 | 判定 |
| --- | --- |
| 一次正常提问 | trace 树完整，8 个步骤都有耗时 |
| 一次 SQL 失败重试 | 重试挂在 EXECUTE 下，父步骤耗时包含子步骤 |
| 一次超时 | `status=TIMEOUT`，`error_kind=TIMEOUT` |
| 与 OTLP | 两边 traceId 一致，能互相跳转 |
| 查看 L2 全文 | 需权限，且审计表有记录 |
| 关闭 L1 | trace 仍可用（只有骨架） |
| 采样 10% | 失败请求仍 100% 有 L1 |
| 清理 | 90 天前的 L0 被删除，L2 7 天 |

#### 6.37.11 不做的事

| 不做 | 理由 |
| --- | --- |
| 新建一套 trace 表 | `chat_log` 已有雏形，重建等于两套数据同步 |
| 默认存 prompt / SQL 全文 | 等于把业务数据复制进日志表 |
| 只给运维看 | 客服和实施才是"为什么错"的第一问人 |
| 在 SSE 流里推 trace | 干扰主流程，且前端不需要实时 |
| 用 LONGTEXT 存 attrs | 无法按属性过滤 |
### 6.38 SQL 生成能力的通用封装（为工具调用预留）

#### 6.38.1 动机

用户要求：问数核心的「生成 SQL」这一步要封装成**通用、可独立调用**的能力，便于后续把它包装成工具调用（MCP tool / function calling，见 §6.15）。

现状痛点：

- `GEN_SQL` 现在是 pipeline 里的一个步骤（§6.20），和「执行 / 图表 / 回答」耦合在同一条调用链里（`ChatQuestionService` 的提问主流程）；
- 没法单独拿出来给 agent / 工具调用复用——要么整条问数跑完，要么不跑；
- 想要「只生成 SQL 不执行」「把 SQL 生成当成一个工具让模型自己决定调不调」都做不到。

#### 6.38.2 目标：把 SQL 生成提成一等能力

定义 `SqlGenerationCapability`（建议落在未来的「通用接口抽象」模块——即 `aimodel-config-flow-revamp` 归档后新建的 change，不在当前 change 实现）：

~~~java
interface SqlGenerationCapability {
    SqlGenResult generate(SqlGenInput input);
}
~~~

输入 `SqlGenInput`（全部显式，无隐式全局状态）：

| 字段 | 来源 |
| --- | --- |
| `question` | §6.22 归一后的问题（已锚定相对时间） |
| `schemaContext` | 召回的表 / 字段 / 关系（§6.20 `RETRIEVE`） |
| `dialectHint` | 方言提示**字符串**（产品侧由 `DatasourceType.sqlDialectHint()` 映射而来，框架不依赖产品枚举，保证通用） |
| `priorSql` / `refResolution` | 多轮指代消解结果（P0 #12 / §6.9） |
| `constraints` | 行 / 列权限已由 `PermissionEnforcer` 在上游注入（P1 #32），此处只消费最终可见 schema |

输出 `SqlGenResult`：

| 字段 | 说明 |
| --- | --- |
| `sql` | 生成的 SQL（含方言正确的引号 / 分页） |
| `explanation` | 给用户的简短说明 |
| `referencedTables` | 引用到的表，供执行期与审计 |
| `confidence` | 0~1，低置信走 §6.32 反问 |
| `alternatives` | 可选 SQL（歧义时） |

**它不负责**：执行、图表、回答、权限注入——这些都是独立能力，保证 SQL 生成是「纯函数 + 显式输入」。

#### 6.38.3 为什么这样就能做工具调用

工具调用（MCP / function calling）要求三件事，本封装天然满足：

1. **明确输入 schema**：`SqlGenInput` 字段固定，可直接映射成 JSON Schema / tool input；
2. **明确输出 schema**：`SqlGenResult` 固定，模型能稳定解析；
3. **确定性、无副作用**：给定相同 `(question, schemaContext, dialect, model, seed)` 结果一致，不碰库、不发消息。

封装后，两种用法共用同一实现：

- **内联**：问数 pipeline 的 `GEN_SQL` 步骤直接调 `SqlGenerationCapability.generate(...)`；
- **工具**：在 `ToolPort`（§6.15）注册为 `askdata.generate_sql` 工具，模型可在 agentic 流程里自行决定是否调用、调用几次（如先生成再走自我修复环 §6.17 / §6.20）。

#### 6.38.4 与现有代码的关系

- **方言提示已具备**：`DatasourceType.sqlDialectHint()` 覆盖 14 种数据源（MySQL / PG / SQLServer / SQLite / CK / Doris / StarRocks / Oracle / DM / Kingbase / Redshift / ES / Hive / Excel），直接复用，不要再造方言逻辑；
- **召回 / 指代**：复用 §6.20 的 `RETRIEVE` 与 P0 #12 的指代消解，不要在本能力里重做；
- **权限**：消费 `PermissionEnforcer` 之后的「可见 schema」，不在 SQL 生成里再判权限。

#### 6.38.5 落点：放进框架（fw-plus-ai-base），不在产品里

- 用户决定：「通用接口抽象」放到框架层 `fw-plus-ai` 的 **`fw-plus-ai-base`**（该模块目前为空，正是放框架级通用 AI 抽象的位子），`askdata-server` 作为产品侧依赖它并做具体接线；
- 三后端（native / langchain4j / Spring AI）都是挂在 `fw-plus-ai` 下的**框架模块**，端口接口在 `fw-plus-ai-base`，薄适配让它「快速接入、少写代码」：
  - **langchain4j**：`fw-plus-ai-langchain4j` 已现成（含 `-core` 适配 + openai / dashscope / anthropic / milvus / elasticsearch … 各组件子模块）；
  - **Spring AI**：新建 `fw-plus-ai-spring-ai`（同款结构：`fw-plus-ai-spring-ai-core` 基础模块 + `fw-plus-ai-spring-ai-adapter` 作为 base 的具体实现（做 bean 注入、不负责装配）+ 各模型 / 向量组件子模块）；
  - **native**：新建 `fw-plus-ai-native`，按 langchain4j 同款结构拆子模块——**`fw-plus-ai-native-core`（独立基础模块）** 沉淀 native 直连基础能力（被 adapter / 组件子模块依赖）；**`fw-plus-ai-native-adapter` 是 `fw-plus-ai-base` 的【具体实现】（不是装配）**：利用 native 内的 core + 组件子模块把 base 端口真正实现出来、并做 fw-plus-ai 的 bean 注入，同时**实现 / 落地领域封装 `fw-plus-ai-askdata` 里的内容**（用 native 真正执行 SQL 生成等流程，故 adapter 依赖 askdata + base + core/组件）；**装配（选哪个后端、自动配置）由 fw-plus 的 `-boot` starter 体系负责，adapter 本身不关心是 native / langchain4j / spring ai**。其余子模块**按能力域建父工程**（父工程只做聚合，子模块按 `fw-plus-ai-langchain4j` 的划分逐一对齐，含社区模块）：`-model-*`（chat / embedding，26 个）、`-store-*`（向量库 / 图库，30 个）、`-retrieval-*`（Web 搜索等，4 个）、`-document-*`（PDF / Office / Tika 解析，3 个）、`-tool-*`（浏览器 / 网页抓取，2 个）、`-mcp-*`（client / server）、`-skill-*`（local / remote）、`-agentic-*`（workflow / supervisor / planner / a2a）、`-bridge-*`（reactor / reactive-streaming / http-client）。它们只做**具体 AI 组件（大模型 / 向量 / 图 / 检索 / 工具 / 解析 / MCP / 技能 / 编排）的直连实现**，不依赖 langchain4j / Spring AI；`-core` 沉淀 HTTP / SSE / JSON / 重试等直连基础能力，`-adapter` 是唯一做 base + support 契约实现与 bean 注入的子模块。
  - **askdata 领域封装**：新建 **`fw-plus-ai-askdata`**，定义「SQL 生成」领域能力（§6.38：NL2SQL prompt 模板、SQL 后处理 / 修复、方言提示消费），**后端无关、只依赖 `fw-plus-ai-base` 端口**（不直接依赖任何后端 `-adapter`）。它描述「怎么生成 SQL」，具体「怎么调模型」由 base 的 `ChatPort` 等端口承接；**各后端 `-adapter` 来实现 / 落地 askdata 里的内容**——即用该后端（native / langchain4j / spring ai）真正执行 askdata 定义的生成流程。产品 `askdata-server` 只选一个 adapter（boot starter）装配，并做专属接线（14 方言 `DatasourceType`、表结构元数据、`PermissionEnforcer`）。
  - 产品现有 `NativeChatPort` 的直连逻辑回流到 native 对应子模块。

**关于 adapter 的约定**：`*-adapter` 的名称就指定了它属于 native / langchain4j / spring ai 中的哪一个（如 `fw-plus-ai-native-adapter`）。**多个 adapter 可共存于 classpath**，用配置条件（如 Spring profile / `@ConditionalOnProperty`）决定哪个生效，**并非强制只能有一个**——一个具体工程按部署需要启用其一即可（产品 `askdata-server` 在 base + 某后端 adapter 之间装配）。

**`fw-plus-ai-base` 是大家的纽带**：**所有 langchain4j 形状的抽象（含 agentic 编排契约）都在这里**，它只定义端口接口与 DTO——以 **langchain4j 1.23.0** 的核心 API 作为主抽象来源（模型端口 `ChatModel` / `StreamingChatModel` / `LanguageModel` / `StreamingLanguageModel`（legacy）/ `EmbeddingModel` / `TokenCountEstimator`、向量存储 `EmbeddingStore`、检索 `ContentRetriever`、消息 / 工具类型），同时对照 **Spring AI** 等价类型、**吸收 AgentScope** 在编排层的特殊情况（共享状态 / 人工介入 / 观测 / 跨进程），并叠加领域端口（`SqlGenInput` / `SqlGenResult`）。base 之上的**增强抽象（编排 + 横切）落在 `fw-plus-ai-support`**（`CallContext` / `PortError` / `WorkflowPort` / `AgenticServices` / 韧性 / 成本 / 缓存 / 幂等 / 边界 IO / 远程技能），由各后端 adapter 实现。所有后端（native / langchain4j / spring ai / agentscope）都来实现它，领域封装（`fw-plus-ai-askdata`）与产品（`askdata-server`）都只依赖它——后端可热插拔，产品与具体 LLM 框架解耦；产品只依赖 base + 任选一个后端 adapter 的具体实现，**不关心背后是 native / langchain4j / spring ai / agentscope**（它们都实现同一份 base 契约）。详见 §6.40。
- 属于 `aimodel-config-flow-revamp` 归档后新建的 change 里实现（按用户决定）；
- 当前只定接口与契约，不写实现（保持当前 change 零代码改动）。

#### 6.38.6 验收

| 项 | 判定 |
| --- | --- |
| 同一输入，内联调用与工具调用 | 产出 SQL 完全一致 |
| 给定相同输入 + seed | 结果可复现（确定性） |
| 输入缺 `schemaContext` | 明确报错，不靠全局隐式状态 |
| 工具注册 | 在 `ToolPort` 能看到 `askdata.generate_sql`，input schema 与 `SqlGenInput` 一致 |
| 不越权 | 生成的 SQL 只引用权限允许范围内的表 |

#### 6.38.7 不做的事

| 不做 | 理由 |
| --- | --- |
| 在 SQL 生成里执行 / 画图 / 回答 | 破坏纯函数属性，没法当工具 |
| 在生成里再做权限判断 | 权限是上游独立能力（P1 #32） |
| 再造方言逻辑 | `DatasourceType` 已有 14 方言提示 |

#### 6.38.8 接入成本：三个后端都是薄适配

用户要求：native / langchain4j / Spring AI 三种后端都要能「快速接入、不用写过多代码」。

做法：

- `SqlGenerationCapability` 是纯接口，与具体 LLM 框架无关；
- 三个后端各自只写一个**薄适配**（与 `NativeChatPort` / `Langchain4jChatPort` / `SpringAi2ChatPort` 同思路，§6.1）：把各自 SDK 的 message / response 映射到本仓库的 `CallContext` / `PortError`（§6.16），SQL 生成逻辑本身三端共用同一 prompt 模板与后处理；
- 不为某个后端写特例分支——特例说明抽象漏了，应回抽到端口契约。

### 6.40 base 端口层抽象（langchain4j 1.23.0 形状 + 出处 + 分工）

#### 6.40.1 决策

- base 以 **langchain4j 1.23.0（最新版）** 的核心 API 作为主抽象来源，对照 **Spring AI** 等价类型、吸收 **AgentScope** 在编排层的特殊情况；
- **铁律**：base 及其子包禁止 import `dev.langchain4j` / `org.springframework.ai` / `io.agentscope` 具体类；所有类型均为自有中立 API（已在 `package-info.java` + 类 Javadoc 标注出处）；
- 每个抽象都标出来源（langchain4j 类 / Spring AI 等价 / AgentScope 特殊情况），换实现时可直接核对。

#### 6.40.2 base 包结构与出处（已核实 langchain4j 1.23.0）

| base 包 | 抽象 | 出处（langchain4j 1.23.0） | Spring AI 等价 | AgentScope 关系 |
| --- | --- | --- | --- | --- |
| model | ChatModel / StreamingChatModel | dev.langchain4j.model.chat.*（1.x 已更名） | ChatModel / StreamingChatModel | 运行在模型之上 |
| model | LanguageModel / StreamingLanguageModel | dev.langchain4j.model.*（旧式补全） | 无直接等价（归并 ChatModel） | — |
| model | EmbeddingModel（合并 DimensionAwareEmbeddingModel#dimension()） | dev.langchain4j.model.embedding.EmbeddingModel | EmbeddingModel | — |
| model | TokenCountEstimator | dev.langchain4j.model.TokenCountEstimator（旧版 Tokenizer 已在 1.x 更名，**已核实**） | TokenCountEstimator | — |
| store | EmbeddingStore | dev.langchain4j.store.embedding.EmbeddingStore | VectorStore | 复用 |
| retriever | ContentRetriever / Query（含 metadata）/ ContentSegment / RetrievalResult | dev.langchain4j.rag.* | VectorStore#similaritySearch(SearchRequest) | 检索结果作 agent 上下文 |
| message | ChatMessage / System/User/Ai/ToolExecutionResult / Content / TextContent / ImageContent / ContentType | dev.langchain4j.data.message.* | chat.messages.* | 复用 |
| tool | ToolSpecification / ToolExecutionRequest / ToolProvider / ToolExecutor / @Tool / @P / ToolSpecifications / DefaultToolExecutor | dev.langchain4j.agent.tool.* + service.tool.* | ToolDefinition / ToolCall / ToolCallback | @Tool/@ToolParam |
| tool | ToolGroup / ToolGroupScope / SkillToolGroup | 无（langchain4j 1.23.0 只有扁平工具列表，无分组） | 无 | io.agentscope.core.tool.*（技能装载即激活工具组） |
| skill | AgentSkill / SkillRegistry / SkillFilter / SkillBox / SkillParser / AgentSkillRepository / AgentSkillRepositoryInfo | 无（langchain4j 1.23.0 无技能概念） | 无 | io.agentscope.core.skill.*（SKILL.md 技能包 + 资源 + 筛选 + 提示词渲染） |
| template | PromptTemplate / ChatPromptTemplate / Prompt | dev.langchain4j.template.* | PromptTemplate / Prompt | 复用 |
| mcp | McpClient / McpClientConfig / McpToolProvider / McpServer / McpToolExecutor | dev.langchain4j.mcp.* | McpSyncClient / McpAsyncClient / McpServer | 经 MCP 接/暴露工具 |
| service | AiService / @AiService / @UserMessage / @V / AiServices / AiServiceContext / AiServicesFactory | dev.langchain4j.service.AiServices（官方结构：builder + performBasicValidation + removeToolMessages + verifyModerationIfNeeded） | 无直接等价（ChatClient 流式） | agent 作服务 |
| memory | ChatMemory / ChatMemoryProvider / ChatMemoryStore | dev.langchain4j.memory.* + store.memory.chat.* | ChatMemory / ChatMemoryRepository | session / memory |
| model.moderation | ModerationModel / Moderation / ModerationException | dev.langchain4j.model.moderation.* | ModerationModel | — |
| model.chat.request | ChatRequest / ChatRequestParameters / DefaultChatRequestParameters / ToolChoice / ResponseFormat | dev.langchain4j.model.chat.request.* | ChatOptions | — |
| model.chat.response | ChatResponse / ChatResponseMetadata | dev.langchain4j.model.chat.response.* | ChatResponse | — |
| model.scoring | ScoringModel / DecisionScoringModel | dev.langchain4j.model.scoring.* | 无直接等价（reranker） | 可作重排工具 |
| model.decision | DecisionModel | dev.langchain4j.model.decision.* | — | — |
| rag | RetrievalAugmentor / DefaultRetrievalAugmentor | dev.langchain4j.rag.* | Advisor | — |
| rag.aggregator | ContentAggregator / ReRankingContentAggregator | dev.langchain4j.rag.content.aggregator.* | DocumentJoiner / Advisor | — |
| rag.injector | ContentInjector / DefaultContentInjector | dev.langchain4j.rag.content.injector.* | Advisor | — |
| rag.transformer | QueryTransformer | dev.langchain4j.rag.query.transformer.* | QueryTransformer | — |
| rag.router | QueryRouter / DefaultQueryRouter | dev.langchain4j.rag.query.router.* | — | — |
| agentic（`fw-plus-ai-base`）/ support.workflow（`fw-plus-ai-support`） | AgenticServices 及 Agent 构建器（base）/ WorkflowPort / WorkflowRequest/Result/Step/Budget/Sink（support） | langchain4j-agentic（beta, Java 17）sequenceBuilder/loopBuilder/plannerBuilder/supervisorBuilder | agentic（Observation/Graph） | AgenticScope / humanInTheLoop / AgentListener / a2aBuilder |
| support.context（`fw-plus-ai-support`） | CallContext / PortError | 无 | 无 | 无（AgentScope 用 Msg/Reply 形态的上下文，语义不同） |
| support.resilience | ModelListener / ModelHealthPort / QuotaPort / ModelFailoverPort | 无（只有重试装饰器，无跨调用熔断） | 无 | 无 |
| support.cost / support.cache / support.idempotency / support.guard | CostPort / CostUsage / CachePort / IdempotencyPort / OutboundUrlGuard | 无 | 无 | 无 |
| support.io / support.skill | ArtifactPort / SpeechPort / SkillPort | SpeechModel（1.x，形态不同） | 无 | 本地技能装载在 base 的 skill 包，远程目录无对应 |

> base 已包含上述全部接口/类型（含 `EmbeddingStoreContentRetriever` 通用检索实现）。

#### 6.40.3 谁来实现（分工，澄清）

- **base 的 AI 组件端口**（model / store / retriever / message / tool / template / mcp / skill）由**各后端的「组件子模块」**实现（其它子模块只实现 base）：
  - `fw-plus-ai-native-*`（`-openai` / `-dashscope` / `-anthropic` / `-qdrant` / `-milvus` …）直连厂商 API，**不依赖 langchain4j / Spring AI**；
  - `fw-plus-ai-langchain4j-*` 经 langchain4j 实现；
  - `fw-plus-ai-spring-ai-*` 经 Spring AI 实现。
- **`-adapter` 模块**（native / langchain4j / spring-ai 各自一个）**实现 `fw-plus-ai-support`**：即 base 之上的增强抽象——编排（WorkflowPort / AgenticServices）与横切（CallContext / PortError、ModelListener / ModelHealthPort / QuotaPort / ModelFailoverPort、CostPort / CachePort / IdempotencyPort / OutboundUrlGuard、ArtifactPort / SpeechPort / SkillPort）；同时把组件子模块拼装成 base 契约、做 bean 注入，并**实现 `fw-plus-ai-askdata` 定义的领域能力**（如 SqlGenerationCapability）。装配（选哪个后端）由 `-boot` starter 负责，adapter 不关心是哪一个后端。
- **分层依赖单向**：`fw-plus-ai-support -> fw-plus-ai-base`。base 只有 langchain4j 形状的 AI 组件端口，support 只放「增强」端口，两者都不 import 任何具体框架实现类。
- **`fw-plus-ai-agentscope`** 是**编排契约（WorkflowPort / AgenticServices）的 AgentScope 实现**，内部按能力域拆子模块：`fw-plus-ai-agentscope-core`（契约 → AgentScope Agent / AgenticScope / 编排器映射）+ `fw-plus-ai-agentscope-a2a`（A2A 跨进程协作），面向 Java 17 / SB4 分支、默认关，用于 SQL 修复环等（§6.17）。
- **`fw-plus-ai-mcp-server`** 已实现 base 的 `McpServer`，把 base 工具以 MCP 暴露给 SQLBot 等外部调用方。

#### 6.40.4 除 langchain4j 那一套之外，适配器还需要实现的抽象（之前讨论的领域 / 横切端口）

这些不在「langchain4j 形状」里，是 **askdata 领域 + 横切能力**：横切端口定义在 **`fw-plus-ai-support`**（base 的增强抽象层），领域能力定义在 `fw-plus-ai-askdata`，最终由各 `-adapter`（及 agentscope）落地：

1. **SqlGenerationCapability**（§6.38，定义在 `fw-plus-ai-askdata`）：NL2SQL 纯函数 `generate(SqlGenInput) -> SqlGenResult`；各 `-adapter` 实现它（用所选后端真正跑 askdata 定义的生成流程）。这是适配器的核心领域实现。
2. **WorkflowPort / AgenticServices**（WorkflowPort 在 support 的 `workflow` 包；AgenticServices 属 langchain4j 形状，在 base 的 `agentic` 包，§6.17）：agentic 编排；`fw-plus-ai-agentscope` 实现，langchain4j-agentic / spring-ai 可选实现。
3. **ToolPort / ToolCallingPort**（§6.15）：工具调用环（串行单工具 / 流式增量 / 解析 tool_calls）；adapter 落地。
4. **ModelListener**（§6.11）：模型调用边界拦截（请求前 / 响应后 / 异常），三后端各写一个 adapter；Spring AI 侧靠 Flux 回调 / ObservationHandler 反调。
5. **ModelHealthPort**（§6.11.3）：模型健康 / 熔断 `record(modelId, outcome)`；adapter 实现。
6. **ChatMemory / MemoryStore**（§6.x）：对话记忆持久化；langchain4j `ChatMemoryStore` 委托，adapter 做 `Turn` ↔ `Message` 互转（≈40 行）。
7. **SpeechPort**（§6.18.10）：语音边界适配器（ASR / TTS），不进 pipeline 主链。
8. **ArtifactPort**（§6.18.10）：文件 / 导出边界适配器。
9. 公共 DTO：`CallContext` / `PortError`（base）、`SqlGenInput` / `SqlGenResult`（askdata）——所有端口与适配器共用。

> 旧文档里的 `ChatPort` / `StreamingChatPort` 已被 base 的 `ChatModel` / `StreamingChatModel` 吸收，不再单列。

#### 6.40.5 不做的事

| 不做 | 理由 |
| --- | --- |
| base import 任何具体框架类 | 破坏「纽带」解耦，铁律 |
| 在 base 写具体厂商 / 框架实现 | 那是组件子模块 / adapter 的职责 |
| 把 Agent / Scope / Supervisor 概念写进端口 | 换实现不改端口；拓扑由 adapter 内部组装 |
| 把 SQL 生成逻辑塞进 base | 那是 `fw-plus-ai-askdata` 领域封装 |
| 在 Java 8 分支用 AgentScope / langchain4j-agentic | 字节码 61，物理不可行；只走 SB4 分支且默认关 |


#### 6.40.6 抽象出处清单（langchain4j 直抄 vs AgentScope 特殊 vs 自行抽象）

图例：**A = langchain4j 1.23.0 直抄/镜像**（Spring AI 有等价）；**B = AgentScope 特殊**（编排层，langchain4j-agentic 仅 beta）；**C = 自行抽象**（产品领域 / 横切，非 langchain4j 直抄）。

| 抽象 | 出处 | 类别 |
| --- | --- | --- |
| ChatModel / StreamingChatModel | langchain4j 1.23.0 `dev.langchain4j.model.chat.*`（1.x 更名） | A |
| ChatRequest / ChatRequestParameters / DefaultChatRequestParameters / ToolChoice / ResponseFormat | langchain4j 1.23.0 `model.chat.request.*` | A |
| ChatResponse / ChatResponseMetadata | langchain4j 1.23.0 `model.chat.response.*` | A |
| message.Content / TextContent / ImageContent / ContentType | langchain4j 1.23.0 `data.message.*` | A |
| Query（含 metadata） | langchain4j 1.23.0 `rag.query.Query`（text + metadata） | A |
| LanguageModel / StreamingLanguageModel | langchain4j 1.23.0（旧式补全） | A |
| EmbeddingModel（含 `dimension()`） | langchain4j `EmbeddingModel` + `DimensionAwareEmbeddingModel` | A |
| TokenCountEstimator | langchain4j 1.23.0 `dev.langchain4j.model.TokenCountEstimator`（旧 `Tokenizer` 已更名） | A |
| EmbeddingStore | langchain4j `store.embedding.EmbeddingStore` | A |
| ContentRetriever / Query / ContentSegment / RetrievalResult | langchain4j `rag.*` | A |
| ChatMessage / System / User / Ai / ToolExecutionResult | langchain4j `data.message.*` | A |
| ToolSpecification / ToolExecutionRequest / ToolProvider | langchain4j `agent.tool.*` | A |
| PromptTemplate / ChatPromptTemplate / Prompt | langchain4j `template.*` | A |
| McpClient / McpClientConfig / McpToolProvider / McpServer / McpToolExecutor | langchain4j `mcp.*`（server 由 `fw-plus-ai-mcp-server` 实现） | A |
| AiService / @AiService / @UserMessage / @V / AiServices / AiServiceConfig | langchain4j `service.AiServices` | A |
| EmbeddingStoreContentRetriever | 用 base 端口自行组合的通用检索实现 | C（自研通用实现，非直抄） |
| WorkflowPort / WorkflowRequest/Result/Step/Budget/Sink（support.workflow） | 自行抽象的 agentic 编排端口（§6.17；只暴露 goal/step/budget） | C |
| AgenticServices（静态工厂，base 的 `agentic` 包）+ AgentBuilder / Sequential / Parallel / Loop / Conditional / Supervisor / Planner / HumanInTheLoop / A2AClient / UntypedAgent | langchain4j-agentic 的 {@code dev.langchain4j.agentic.AgenticServices}（静态 builder 形状，beta）；语义对齐 AgentScope | B |
| AgentSkill / SkillRegistry / SkillFilter / SkillBox / SkillParser / AgentSkillRepository / AgentSkillRepositoryInfo | AgentScope 2.0.4 `io.agentscope.core.skill.*`（SKILL.md 技能包约定；langchain4j 1.23.0 与 Spring AI 均无对应类型） | B |
| ToolGroup / ToolGroupScope / SkillToolGroup | AgentScope 2.0.4 `io.agentscope.core.tool.*`（工具分组 + 技能绑定激活） | B |
| IllegalConfigurationException / ModerationException | langchain4j 1.23.0 {@code exception.*} / {@code service.ModerationException}；**IllegalConfigurationException 继承框架 BizException**（CODE=50_016_001，SERVER+CONFIG） | A（集成 BizException） |
| CallContext / PortError（support.context） | 自研横切 DTO（§6.40.4；langchain4j 的 InvocationParameters 只作用于 RAG） | C |
| ModelListener / ModelHealthPort（support.resilience） | 自研模型边界监听与健康熔断（§6.11 / §6.11.3） | C |
| QuotaPort / ModelFailoverPort（support.resilience） | 自研配额限流与故障转移候选链（§6.16 / §6.26） | C |
| CostPort / CostUsage（support.cost） | 自研成本核算（单价表 + 每次调用落库） | C |
| CachePort（support.cache） | 自研缓存 + 纪元失效（§6.35 cache_epoch 失效矩阵） | C |
| IdempotencyPort（support.idempotency） | 自研请求幂等（Idempotency-Key 三段式） | C |
| OutboundUrlGuard（support.guard） | 自研出站地址校验（§6.29.3，防 SSRF） | C |
| ArtifactPort / SpeechPort（support.io） | 自研边界端口（§6.18.10；签名 URL / ASR / TTS） | C |
| SkillPort（support.skill） | 自研远程技能目录（本地 SKILL.md 装载在 base 的 skill 包） | C |
| SqlGenerationCapability + SqlGenInput / SqlGenResult | 产品领域 NL2SQL（§6.38，落在 `fw-plus-ai-askdata`） | C |
| ToolPort / ToolCallingPort | 自研工具调用环（§6.15） | C |
| ModelListener / ModelHealthPort | 自研模型边界拦截 / 健康（§6.11；两框架形态不一，故抽中性接口） | C |
| ChatMemory / MemoryStore | 自研记忆持久化（§6.x；`Turn` ↔ `Message` 互转） | C |
| SpeechPort / ArtifactPort | 自研边界适配器（§6.18.10） | C |
| CallContext / PortError | 自研公共 DTO | C |

> 一句话：**A 类照 langchain4j 1.23.0 抄，B 类照 AgentScope 收口，C 类是我们自己的领域/横切抽象**——三者都只落在 base/askdata，具体实现由组件子模块与 adapter 完成（§6.40.3）。

#### 6.40.7 100% 对齐 langchain4j 1.23.0 的进度（三批）

基线：`langchain4j-core-1.23.0.jar` 中 model / data / rag / store / memory / service / agent / template 八个包的顶层类型共 **267** 个；首批落地时 base 仅覆盖 **60** 个（缺 207），且存在 5 处形状偏差。按三批补齐：

| 批次 | 范围 | 状态 |
| --- | --- | --- |
| ① | 形状纠正 + 消息/数据层 | **已完成** |
| ② | 监听器（7 组）+ 请求响应 DTO + store Filter DSL + EmbeddingSearchRequest/Result + EmbeddingStoreIngestor | 待做 |
| ③ | model 扩展（Disabled* / ModelCatalog / ChatModelRouter / 流式事件 / JSON Schema）+ Batch* + TokenStream + 注解与 SPI | 待做 |

批次①已落地的形状纠正：

| 原偏差 | 现状（对齐官方） |
| --- | --- |
| `Content` 在根包且只有 `text()` | 改为 `rag.content.Content`：`textSegment()` + `metadata(): Map<ContentMetadata,Object>`，新增 `ContentMetadata`（SCORE / RERANKED_SCORE / EMBEDDING_ID）与 `DefaultContent` |
| `Query.metadata()` 用通用 KV | 改为 `rag.query.Metadata`（chatMessage / systemMessage / chatMemoryId / chatMemory / invocationContext / invocationParameters）；`Query` 去掉 maxResults / minScore（归检索器配置） |
| `ImageContent` 存 `String url` | 改为持有 `data.image.Image`（`url() -> URI`、`revisedPrompt()`），并提供 `detailLevelSpecified()` |
| `Metadata` 合并为通用 KV 放根包 | 改为 `data.document.Metadata`，方法集与官方一致（getString / getUUID / getInteger / getLong / getFloat / getDouble / put 重载 / putAll / remove / copy / toMap / from / metadata / merge） |
| `TextSegment` / `Embedding` / `EmbeddingMatch` 在 `data/` | 归位到 `data.segment.TextSegment`、`data.embedding.Embedding`、`store.embedding.EmbeddingMatch` |
| `ContentRetriever` 返回 `RetrievalResult` | 改为返回 `List<Content>`（官方形状）；删除 base 自造的 `ContentSegment` / `RetrievalResult` |
| `RetrievalAugmentor.augment(String)` | 改为 `augment(AugmentationRequest) -> AugmentationResult` + 默认 `augmentAsync` |
| `EmbeddingModel` 自带 `dimension()` | 新增 `DimensionAwareEmbeddingModel`（官方抽象类）承接维度感知 |

批次①新增的类型：

- `data.document`：`Document`（接口 + FILE_NAME / ABSOLUTE_DIRECTORY_PATH / URL 常量）、`DefaultDocument`、`DocumentLoader`、`DocumentParser`、`DocumentSource`、`DocumentSplitter`、`DocumentTransformer`、`BlankDocumentException`
- `data.segment`：`TextSegment`、`TextSegmentTransformer`
- `data.image` / `data.audio` / `data.video` / `data.pdf`：`Image` / `Audio` / `Video` / `PdfFile`
- `data.embedding` / `store.embedding`：`Embedding` / `EmbeddingMatch`
- `invocation`：`InvocationContext`（含 Builder）、`InvocationParameters`、`LangChain4jManaged`
- `rag`：`AugmentationRequest`、`AugmentationResult`
- `model`：`ModelProvider`（枚举项与官方一致）、`DimensionAwareEmbeddingModel`
- `message`：`AudioContent`、`VideoContent`、`PdfFileContent`、`CustomMessage`、`ChatMessageJsonCodec`；`ChatMessageType` 补 `CUSTOM`
- `exception`：`LangChain4jException`（`BlankDocumentException` 的父类）

验证：`javac` 全量编译 base 通过（144 个 class），`fw-plus-ai-support` 依赖 base 编译通过。

### 6.39 非业务数据源扩展：图库（Neo4j / Nebula）与向量库

#### 6.39.1 问题

用户问：Neo4j / Nebula（图库）、向量库（Milvus / Qdrant）能不能纳入数据源体系？并明确：**这些不是业务数据源**——不是「自然语言 → SQL → 图表」的问数对象。

#### 6.39.2 现状证据（已核实）

- 数据源类型系统 **DatasourceType**（14 种）已支持**非 JDBC / 非 SQL** 类型：**ES** 标记 **isHttpProtocol()=true**，在 **JdbcEngine** 里走专用分支——连接走 **checkEs()**（:105）、表列表走 **esIndices()**、字段走 **esFields()**、**buildJdbcUrl** 对 ES 直接抛「no jdbc url」（:264）；其 **sqlDialectHint()** 明确返回「NL2SQL 路径不适用；请使用其专用检索接口」。即 ES 是被纳入类型体系、但**排除在 NL2SQL 之外**的先例。
- **SqlSemanticValidator** 基于 JSQLParser（SQL 专用），图库查询（Cypher / nGQL）无法走这套校验。
- 向量库**已在用，但走 RAG 召回而非问数执行**：产品侧 **DatasourceEmbeddingService** / **TableEmbeddingService** / **SchemaContextBuilder** 用 embedding 做 schema / 术语召回（grep 命中）；框架侧 **fw-plus-ai-langchain4j-milvus** 已提供 Milvus 向量存储适配（Qdrant 为本机真实召回底座）。向量库当前角色是「检索增强底座」，不是「被问数的业务表」。

#### 6.39.3 可行性结论

1. **接纳入类型体系：可行**。扩展 **DatasourceType** 加 **NEO4J** / **NEBULA** / **VECTOR**（或 MILVUS / QDRANT），沿用 ES 的 **isHttpProtocol()** / 专用元数据 / 专用执行分支模式即可。建议同时加 **QueryLanguage** 维度（SQL / CYPHER / nGQL / VECTOR_API），让上层按语言分流，而不是靠 getType() 硬判。
2. **当作业务问数目标：不推荐**。图库查询语言与 SQL 异构，向量库语义是「相似度召回」而非「结构化行集 + 图表」。硬塞进 NL2SQL 会扭曲架构（正如 ES 被显式排除）。

#### 6.39.4 推荐落点

| 类型 | 角色 | 怎么接 |
| --- | --- | --- |
| 向量库 | RAG / 召回底座 | 复用框架 **fw-plus-ai-langchain4j-milvus** 与产品 embedding 服务；**不进问数 SQL 执行链** |

> 向量库的具体客户端封装可落在 **fw-plus-ai-native-<vector>** 子模块（直连厂商 API，新增）或复用既有 **fw-plus-ai-langchain4j-milvus**（langchain4j 路径），二者都在框架层、产品不重复造轮子（与 §6.38 子模块结构一致）。
| Neo4j / Nebula | 图查询 / 探索 | 二选一：(a) 像 ES 走「专用检索 / 探索接口」，不进 NL2SQL；(b) 若要做自然语言问图，新增与 §6.38 并列的 **GraphQueryGenerationCapability**（产出 Cypher / nGQL），schema 上下文改用「节点 / 关系 / 属性」而非「表 / 字段」，校验器与权限强制另写（SQL 那套 **PermissionEnforcer** / **SqlSemanticValidator** 不适用） |

#### 6.39.5 与 §6.38 的关系

§6.38 的 **SqlGenerationCapability** 是「关系型 SQL」这一支。要统一，可把端口提成 **QueryGenerationCapability**，输出带 **language** 维度（SQL / CYPHER / nGQL），但**输入 schema 上下文分两套**（关系型 vs 图型），执行器与校验器按语言分发。这属于 P3 级扩展，不在当前 change。

#### 6.39.6 验收（若落地）

| 项 | 判定 |
| --- | --- |
| 加 NEO4J / NEBULA / VECTOR 类型 | 连接检查 / 元数据抓取走专用分支，不进 **buildJdbcUrl** |
| 向量库 | 仅用于召回，问数链路不对其发 SQL |
| 图库 NL2Graph（若做） | 产出 Cypher / nGQL 在其图库可执行；权限 / 校验另覆盖 |
| **QueryLanguage** 分流 | 上层按语言而非 getType() 硬判 |

#### 6.39.7 不做的事

| 不做 | 理由 |
| --- | --- |
| 把向量库当业务表做 NL2SQL | 语义是召回不是行集，扭曲架构 |
| 用 **SqlSemanticValidator** 校验 Cypher / nGQL | 它是 SQL / JSQLParser 专用 |
| 在当前 change 实现 | 等 **aimodel-config-flow-revamp** 归档后新 change；类型扩展与 **QueryLanguage** 维度属产品侧 **DatasourceType** |
