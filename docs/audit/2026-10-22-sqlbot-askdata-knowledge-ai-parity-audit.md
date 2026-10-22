# SQLBot → askdata 知识检索 / AI 模型层 实现保真度审计报告

| 项 | 值 |
| --- | --- |
| 审计对象 | 知识检索（RAG）链路 + AI 模型层 + 术语 / SQL 示例 / 自定义提示词 |
| 基准（真值） | SQLBot backend `@ fc378c37`（`/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend`） |
| 对照目标（实现） | askdata `@ 1ff1bf8` + 工作区未提交改动（`/Users/xiongxiao/Workspaces/Idea/codes/New/java-product/askdata`） |
| 审计日期 | 2026-10-22 |
| 方法 | 只读审计。未修改任何源码；结论全部来自实际代码，非文档自述。 |
| 采信规则 | **不采信** `docs/comet/archive/**/coverage.md` 等历史自述报告；所有判定均以两侧真实代码为准。 |
| 路径约定 | 下文 `SQLBot` = `SQLBot/backend/...`；`askdata` = `askdata/askdata-server/src/main/...` |

---

## 结论摘要

按严重程度排序。

1. **【缺失·严重】自定义提示词的 `ANALYSIS` / `PREDICT_DATA` 两类在 askdata 运行时完全没有注入点。**
   askdata 的召回 SQL 把类型写死为 `GENERATE_SQL`（`chat/service/ChatKnowledgeService.java:277`），而分析/预测链路的提示词构建方法里没有任何自定义提示词插槽（`chat/service/SchemaContextBuilder.java:327-346`）。SQLBot 在分析前注入 `ANALYSIS`、在预测前注入 `PREDICT_DATA`（`apps/chat/task/llm.py:587`、`llm.py:638`）。后果：**UI 里能配置、数据表里能存、但数据分析与数据预测场景 100% 静默失效**——用户看不到任何报错，只会认为"提示词没生效"。

2. **【缺失·严重】`chat.sqlbot_name` / `chat.limit_rows` / `chat.context_record_count` 三个 chat 级参数在后端无任何读取点。**
   SQLBot 在每次会话创建时读取并生效（`apps/chat/task/llm.py:246-264`：`sqlbot_name` 进 7 个提示词模板、`limit_rows` 控制 SQL 行数策略、`context_record_count` 控制历史轮数）。askdata 后端 grep 不到任何一处读取；三者只以种子形式存在于 `resources/schema.sql:199/211/223`，前端 `web/src/stores/chatConfig.ts:63-70` 拉取了 `limit_rows`/`sqlbot_name` 但两个 getter 在整个 `web/src` 内**零调用**。同时 `chat.context_record_count` 在 askdata 被硬编码为 5（`chat/service/ChatQuestionService.java:577-579`）。

3. **【缺失·高】数据量限制策略只停留在"提示词里的一句话"，没有运行时截断。**
   SQLBot 有两层：`sql_sys_question(ds.type, enable_sql_row_limit)` 切换 `query_limit`/`no_query_limit` 两套规则模板（`templates/template.yaml:40-76`），外加结果落库前按 1000 行硬截断并写入 `data_obj['limit']`（`llm.py:1291-1293`）。askdata 只在系统提示词里硬编码"结果行数控制在 1000 行以内"（`chat/service/SchemaContextBuilder.java:883`），且该句**恒定存在**（不受 `chat.limit_rows` 控制），执行结果也不截断。

4. **【打折·高】术语匹配退化为"对原始问题做子串/向量匹配"，未复用抽取后的关键词。**
   SQLBot 的匹配文本是 `extracted_keywords or question`，且含逗号时**逐个关键词精确匹配**（`apps/chat/task/llm.py:384`、`apps/terminology/curd/terminology.py:983-993`）。askdata 在知识链路的**第一步**就拿原始问题去召回（`ChatKnowledgeService.java:218`），LLM 实体抽取发生在其**之后**（`ChatKnowledgeService.java:222`），抽取结果只用于关键词落库与扩展，**没有回流到术语召回**。结果：召回面更宽、精度更低，且与 SQLBot"高精度的抽取词匹配"不是同一语义。

5. **【缺失·高】SQL 示例导入路径不写向量。**
   askdata `data_training` 的 Excel 导入直接 `trainingRepository.insert(...)`，**没有**调用 `saveEmbeddings`（`training/TrainingService.java:327-328`）；新建/编辑路径是有的（`TrainingService.java:176,191`）。SQLBot 在批量导入末尾统一补算向量（`apps/data_training/curd/data_training.py:407-409`，由 `apps/data_training/api/data_training.py:230` 触发）。askdata 只能等**下次启动回填**（`common/startup/EmbeddingBackfillRunner.java:134-148`）才补上，期间导入的示例对向量召回不可见。

6. **【缺失·高】AI 模型层不做 protocol/supplier 分发，且 `additional_params` 实际未生效。**
   SQLBot 有 `LLMFactory` 工厂（openai/tongyi/vllm/azure，`apps/ai_model/model_factory.py:115-120`），`protocol==1 → "openai"` 否则 `"vllm"`（`model_factory.py:176`），并把 `ai_model.config` JSON 整体转成 `additional_params` 传给模型（`model_factory.py:160-166`），还会按需删除 `extra_body.enable_thinking`（`llm.py:196-201`）。askdata 虽在 `ai_model` 表存了 `protocol`/`supplier`（`system/repository/AiModelRepository.java:31,64-78`）并在 API 暴露（`system/service/AiModelService.java:97-100,428-431`），但 `ChatModelRouter.ModelRef` 根本没有这两个字段（`chat/service/ChatModelRouter.java:44-72`），运行时**一律走 OpenAI 兼容的 `/v1/chat/completions`**（`chat/service/ChatModelClient.java:350-365`），请求体只有 `model/messages/stream`（`ChatModelClient.java:109-120`）——`temperature` 只在连通性探测里被用到（`system/service/AiModelService.java:272-273`），**主链路连 temperature 都没发**。

7. **【打折·中】高级应用的知识隔离在 "oid" 维度不等价。**
   SQLBot 用助手自身的 oid：`calculate_oid = self.current_assistant.oid if self.current_assistant.type != 4 else self.oid`（`llm.py:380,410,436`），助手 oid 默认 1（`apps/system/schemas/system_schema.py:119`）。askdata 在嵌入式访问令牌里把 oid **硬编码为 1L**（`embedded/AssistantPublicController.java:67-70`），而知识查询又要求 `oid = ? AND advanced_application = ?`（`terminology/TerminologyRecallService.java:156-160` + `appendScope:176-181`）。若高级应用归属的工作空间不是默认工作空间，其专属术语/示例将**永远召不回**。另外 askdata 缺少 SQLBot 的"高级应用忽略数据源作用域"等价物——SQLBot 对 type==1 显式置 `calculate_ds_id = None`（`llm.py:381-382`），askdata 则在 `advancedApp != null` 时直接 `return`，效果一致（这部分等价）。

8. **【缺失·中】术语存在两套互斥渲染，其中一套是死代码。**
   `ChatKnowledgeService.terminologyTemplate`（`ChatKnowledgeService.java:431-452`）是与 SQLBot `to_xml_string` 同构的 XML（等价）；`TerminologyService.buildTerminologyContext`（`terminology/TerminologyService.java:465-519`）是 `- 术语「…」：…` 的 bullet 形态，**全仓无调用方**（grep 仅命中定义）。双实现长期共存会在后续改造中造成"改了没生效"的事故。

9. **【缺失·中】导出/导入模板的 i18n 丢失，改为硬编码中文。**
   SQLBot 导出表头与模板表头全部走 `trans(...)`（`apps/terminology/api/terminology.py:95-100`、`:143-148`）。askdata 硬编码中文（`terminology/TerminologyService.java:268-273`、`:280-285`；`training/TrainingService.java:230-233`），与 askdata 自身已存在的 5 语言 i18n 体系（`web/src/i18n/{zh-CN,zh-TW,en,ko-KR,th-TH}.json`）不一致。

10. **【更强·中】embedding 层 askdata 整体优于 SQLBot，但 SQL 示例的条数与格式不稳定。**
    askdata 支持"远程 embedding（`ai_model.model_type=1`）→ 本地 ONNX 回退"、768 维归一化（`ai/EmbeddingService.java:22-23,68-85`、`ai/LocalEmbeddingModel.java:57,292`），SQLBot 只有本地 HuggingFace（`apps/ai_model/embedding.py`）。但 SQL 示例条数在两条召回路径上不一致：向量路径上限 5（`training/TrainingRecallService.java:32-33`，对齐 SQLBot `EMBEDDING_DATA_TRAINING_TOP_COUNT=5`），词法兜底路径上限 3（`chat/service/SchemaContextBuilder.java:59`），且两条路径的文本格式不同（`:215` vs `:733`），兜底路径还会**每次提问重算全部样例向量**（`:710`）。

11. **【等价·正面】以下能力已确认对齐**：术语向量阈值/条数（0.4 / 5）、术语 XML 提示格式、关键词抽取与同义词扩展上限（10）、`extracted_keywords`/`expanded_keywords` 落库、术语与 SQL 示例的新增/编辑/启停/导入导出的主流程与错误回执文件名、启动回填的幂等与集群单例保护、术语/示例的高级应用作用域过滤。

---

## 逐项对照表

> 判定口径：**等价** = 行为一致；**打折** = 能力在但语义/覆盖面弱于 SQLBot；**缺失** = SQLBot 有、askdata 无；**更强** = askdata 超出 SQLBot。

### A. 术语（terminology）

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| A1 | 术语匹配文本 | `llm.py:384` `match_text = self.chat_question.extracted_keywords or self.chat_question.question` | `ChatKnowledgeService.java:218` `recallTerms(question, ...)`（用**原始问题**） | 打折 | SQLBot 优先用抽取关键词；askdata 始终用原问题，且抽取发生在召回**之后**（`:222`），抽取结果未回流召回 |
| A2 | 关键词逗号切分逐词匹配 | `terminology.py:983-993`（`',' in question` → 逐词 `select_terminology_by_word`） | 无对应逻辑（整句一次性召回） | 缺失 | SQLBot 用逗号切分规避整体 `ILIKE` 误命中，精度更高 |
| A3 | 术语向量召回 | `terminology.py:886-903` + `embedding_sql`（`:798-806`，pgvector `<=>`），`config.py:106,109`（0.4 / 5） | `TerminologyRecallService.java:152-173`，`:29-33`（余弦 > 0.4，top 5） | 等价 | 阈值与条数完全一致；实现位置不同（应用侧算余弦 vs DB 侧 `<=>`） |
| A4 | 术语词法召回方向 | `terminology.py:856` `':sentence ILIKE %word%'`（仅"词在句中"） | `TerminologyRecallService.java:122-149`（"词∈句" + "句∈词" + 同义词子行 `c.word`） | 更强 | askdata 多一个方向与子术语匹配；风险是误命中率上升 |
| A5 | 术语召回是否用 bigram | 无（纯 ILIKE） | 术语召回无（`:130-131` 纯 `INSTR`）；bigram 只在选库/选表打分 `SchemaContextBuilder.java:754-758` | 等价 | 两侧术语本体都不依赖 bigram |
| A6 | 术语 XML 提示格式 | `terminology.py:952-972` `to_xml_string` + `templates/template.yaml:2-4` | `ChatKnowledgeService.java:431-452` `terminologyTemplate` + `:455-461` `escape` | 等价 | 根/子元素（`terminologies/terminology/words/word/description`）与转义集合（`& < > " '`）一致 |
| A7 | 第二套术语渲染 | 无 | `terminology/TerminologyService.java:465-519` `buildTerminologyContext`（bullet 形态） | 缺失（多余实现） | 全仓无调用方，属死代码 + 双渲染分歧隐患 |
| A8 | 关键词抽取（LLM 实体） | `llm.py:454-489` `extract_keywords`（含历史轮次、ERROR:NOT_DATA_QUERY/EMPTY 语义） | `ChatKnowledgeService.java:309-331` `extractEntitiesWithLlm` + `:334-353` `parseEntities` | 等价 | askdata 抽不到就退化为词法命中（SQLBot 同样有降级路径） |
| A9 | 同义词扩展上限 | `terminology.py:1064` `max_keywords = 10` | `ChatKnowledgeService.java:43` `MAX_EXPANDED_KEYWORDS = 10`（`:385-405`） | 等价 | 上限与"补入主词+同义词"语义一致 |
| A10 | 抽取词/扩展词落库 | `apps/chat/curd/chat.py:936` `save_extracted_keywords` | `chat/repository/ChatRepository.java:194` `saveKeywords` ← `ChatQuestionService.java:252-255`；列 `resources/schema.sql:668-669` | 等价 | 列名与语义一致 |
| A11 | 术语新增/更新写向量 | `terminology.py:245`/`:579` + `:770-795` `save_embeddings` | `terminology/TerminologyService.java:205,229` → `TerminologyRecallService.java:53-80` `saveEmbeddings` | 等价 | 均对父术语与同义词子记录一起写向量 |
| A12 | 术语 Excel 导入写向量 | `apps/terminology/api/terminology.py:242` → `terminology.py:549`（跳过）+ `:562-564`（末尾批量补算） | `terminology/TerminologyService.java:388` → `create()` → `:205`（逐行即时补算） | 等价 | 路径不同（批量 vs 逐行），最终状态一致 |
| A13 | 术语导入失败回执命名 | `apps/terminology/api/terminology.py:187-189`（`<root>_<sha10>`） | `terminology/TerminologyService.java:429` `<root>_<uuid10>_error.xlsx` | 等价 | 命名与 `error_excel_filename` 返回语义一致 |
| A14 | 术语导出/模板表头 i18n | `apps/terminology/api/terminology.py:95-100`、`:143-148`（`trans(...)`） | `terminology/TerminologyService.java:268-273`、`:280-285`（硬编码中文） | 缺失 | askdata 自身 5 语言 i18n 未覆盖导出物 |
| A15 | 术语作用域（数据源/高级应用） | `terminology.py:860-874`（advanced_application 优先，否则 specific_ds/datasource_ids，否则仅全局） | `TerminologyRecallService.java:176-190` `appendScope`（advancedApp 优先并 `return`，否则 specific_ds/datasource_ids，否则全局） | 等价 | 分支优先级与条件完全对应 |
| A16 | 术语启停 | `terminology.py:726` `enable_terminology` | `terminology/TerminologyService.java:246-253` `enable` | 等价 | 父+子同步启停 |

### B. SQL 示例（data_training）

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| B1 | 示例召回向量条数 | `config.py:110` `EMBEDDING_DATA_TRAINING_TOP_COUNT=5`（`data_training.py:506,517` LIMIT 5） | `training/TrainingRecallService.java:32-33` `TOP_COUNT=5` | 等价 | 向量路径一致 |
| B2 | 示例召回条数（词法兜底） | 无独立兜底（ILIKE 命中即返回） | `chat/service/SchemaContextBuilder.java:59` `TRAINING_LIMIT = 3` | 打折 | 兜底路径只有 3 条，与向量路径 5 条不一致 |
| B3 | 示例词法匹配方向 | `data_training.py:535`（双向 `ILIKE`：`句⊃问` 或 `问⊃句`） | `SchemaContextBuilder.java:758` `lexicalScore`（分词命中率 × IDF 近似 + 中文二元组 Jaccard） | 更强 | askdata 词法打分更细，但仅用于兜底路径 |
| B4 | 示例提示词呈现格式 | `data_training.py:587` `{'question':…, 'suggestion-answer':…}` → `:596-616` XML（`<sql-examples><sql-example><question>…`） | `SchemaContextBuilder.java:215` / `:227` `question + "\n" + sql`；兜底路径 `:733` `"问：…\nSQL：…"` | 打折 | 非 XML，且两条路径格式互不相同，模型看到的样例形态不稳定 |
| B5 | 示例基础模板包裹 | `templates/template.yaml:5-7`（`{data_training}` 包裹） | `SchemaContextBuilder.java:287-292`（`【可参考的 SQL 样例】` 段落） | 等价 | 均为独立段落注入 |
| B6 | 示例不按数据源过滤时直接返回空 | `data_training.py:623-624`（无 ds 且无高级应用 → 返回空） | `SchemaContextBuilder.java:194-196`（表不存在 → 空）、`:203-206`（高级应用过滤） | 等价 | 语义一致 |
| B7 | 示例新增/编辑写向量 | `data_training.py:228`（create）、`:279`（update）→ `common/utils/embedding_threads.py` `run_save_data_training_embeddings` | `training/TrainingService.java:176,191` → `trainingRecallService.saveEmbeddings` | 等价 | — |
| B8 | **示例 Excel 导入写向量** | `apps/data_training/api/data_training.py:230` → `data_training.py:407-409` `run_save_data_training_embeddings(inserted_ids)` | `training/TrainingService.java:327-328` `trainingRepository.insert(...)`（**无** `saveEmbeddings`） | 缺失 | 导入的示例在下次启动回填前对向量召回不可见 |
| B9 | 示例不每次提问重算向量 | 向量已落库，召回只算 query 向量 | 向量路径：`TrainingRecallService.recallByDatasource` 用落库向量（`training/TrainingRecallService.java:115,161`）；**兜底路径**：`SchemaContextBuilder.java:710` `embedAll(question, texts)` 对全部行重算 | 打折 | 仅当向量召回无命中时触发，属"按需兜底"而非"每次重算"，但代价随样例总量线性增长 |
| B10 | 示例导出表头 i18n | `apps/data_training/api/data_training.py` 导出走 `trans(...)` | `training/TrainingService.java:230-233`（硬编码中文） | 缺失 | — |
| B11 | 示例作用域过滤 | `data_training.py:540-543`（advanced_application 优先，否则 datasource） | `training/TrainingRecallService.java:99-108,148-154` + `SchemaContextBuilder.java:198-206` | 等价 | 分支一致 |
| B12 | 示例启停 | `data_training.py` `enable_training` | `training/TrainingService.java` enable 接口 | 等价 | — |

### C. 自定义提示词（custom_prompt）

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| C1 | 三种类型建模 | `CustomPromptTypeEnum`（GENERATE_SQL / ANALYSIS / PREDICT_DATA），`llm.py:400` 参数化 | `prompt/CustomPromptService.java:34-36`（三常量）、`:377-379`（类型白名单校验） | 等价 | 存储层等价 |
| C2 | **GENERATE_SQL 注入** | `llm.py:303-305`（`custom_prompt` 段进消息序列） | `ChatKnowledgeService.java:277`（`type='GENERATE_SQL'`）→ `ChatQuestionService.java:272-282` → `SchemaContextBuilder.java:284-286` | 等价 | 注入路径完整 |
| C3 | **ANALYSIS 注入** | `llm.py:587` `filter_custom_prompts(_session, CustomPromptTypeEnum.ANALYSIS, …)` → `chat_model.py:332-334` `analysis_sys_question` → `templates/template.yaml:606-638`（`{custom_prompt}` 在 :630） | **无**：`SchemaContextBuilder.java:327-334` `buildAnalysisMessages` 无自定义提示词槽；`ChatQuestionService.java:419-424` 直接调用 | 缺失 | 分析场景提示词 100% 不生效，且无任何日志/降级提示 |
| C4 | **PREDICT_DATA 注入** | `llm.py:638` `filter_custom_prompts(…, CustomPromptTypeEnum.PREDICT_DATA, …)` → `chat_model.py:339-341` `predict_sys_question` → `templates/template.yaml:639-680`（`{custom_prompt}` 在 :668） | **无**：`SchemaContextBuilder.java:337-346` `buildPredictMessages` 无自定义提示词槽 | 缺失 | 同上 |
| C5 | 作用域（oid + specific_ds/datasource_ids） | `llm.py:409-422`（`calculate_oid` + `calculate_ds_id`；type==1 时 ds 置 None 并传 `assistant.id`） | `ChatKnowledgeService.java:274-299`（oid + advanced_application + specific_ds/datasource_ids） | 等价 | 语义对应 |
| C6 | 许可开关 | `llm.py:402` `if SQLBotLicenseUtil.valid():`（xpack 许可才启用） | 无许可判断（社区版直接启用） | 更强 | askdata 无许可层，功能不受限 |
| C7 | 自定义提示词来源模块 | xpack（`sqlbot_xpack/` 在 backend 中不存在，来源不可见，仅调用点可验证） | 本地实现 `prompt/CustomPromptService.java` + `CustomPromptRepository.java` | 更强 | 源码可见、可维护 |

### D. Embedding 模型

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| D1 | 本地 embedding | `apps/ai_model/embedding.py` `EmbeddingModelCache`（`shibing624/text2vec-base-chinese`，`normalize_embeddings=True`，线程锁缓存） | `ai/LocalEmbeddingModel.java`（ONNX，`EMBEDDING_DIMENSION=768`，`:292` 明确对齐 `normalize_embeddings=True`） | 等价 | 模型与归一化语义一致 |
| D2 | 远程 embedding 优先 + 本地回退 | 无（仅本地） | `ai/EmbeddingService.java:22-23,68-85`（远程 `model_type=1` 优先，失败回退本地） | 更强 | askdata 独有 |
| D3 | 向量维度 | 768（text2vec-base-chinese） | 768（`LocalEmbeddingModel.java:57`） | 等价 | — |
| D4 | 余弦相似度计算位置 | DB 侧 pgvector `<=>`（`terminology.py:801-806`、`data_training.py:501`） | 应用侧 `EmbeddingService.recallByVector`（`:223-247`） | 等价 | 阈值口径一致（0.4） |
| D5 | 启动回填幂等 | `main.py:45-57` `@SingleWorkerGuard.once` + `terminology.py:753-763` `run_fill_empty_embeddings`（只补 `embedding IS NULL`） | `common/startup/EmbeddingBackfillRunner.java:112-148`（只补 `embedding IS NULL`）+ `:87-90` 单 worker 守卫 | 等价 | 均幂等 |
| D6 | 集群单实例保护 | `common/utils/distributed_lock.py`（PG `pg_try_advisory_lock`） | `common/lock/SingleWorkerGuard.java:20-31,164-179`（H2 `SELECT ... FOR UPDATE` 行锁 + `LOCK_TIMEOUT` 1s） | 等价 | 实现因元数据库不同而不同，目标一致；askdata 有清晰的设计说明 |
| D7 | 回填覆盖范围 | 术语 + data_training + core_table + core_datasource（`main.py:72-74`） | 同四类（`EmbeddingBackfillRunner.java:92-95`） | 等价 | — |

### E. AI 模型层

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| E1 | 模型工厂 / 协议分发 | `apps/ai_model/model_factory.py:112-133` `LLMFactory`（openai/tongyi/vllm/azure） | 无工厂；`chat/service/ChatModelClient.java:350-365` 统一 `/v1/chat/completions` | 缺失 | 所有供应商按 OpenAI 兼容处理，Azure/tongyi 专有参数无处安放 |
| E2 | protocol → 类型映射 | `model_factory.py:176` `model_type="openai" if db_model.protocol == 1 else "vllm"` | `protocol` 仅落库/透传（`system/repository/AiModelRepository.java:31,64-78`、`system/service/AiModelService.java:97-100,428-431`），`ChatModelRouter.ModelRef` 不含该字段（`:44-72`） | 缺失 | 配置项存在但运行时完全不参与决策 |
| E3 | `additional_params`（`ai_model.config` JSON） | `model_factory.py:160-166` 整包转 `additional_params` 传给模型 | `chat/service/ChatModelRouter.java:99-110` 只解析 `temperature`，且 `ChatModelClient.java:109-120` 请求体不含它 | 缺失 | `max_tokens`/`top_p`/`extra_body` 等全部丢失；连 temperature 主链路都没发 |
| E4 | `enable_thinking` 处理 | `llm.py:196-201`（按需删除 `extra_body.enable_thinking`） | 无 | 缺失 | 关闭思考模式无路径 |
| E5 | 默认模型选择 | `model_factory.py:148-158`（自定义模型优先 → `default_model=true`） | `chat/service/ChatModelRouter.java:74-78` `defaultChatModel()`（`aiModelRepository.findDefault()`） | 等价 | — |
| E6 | embedding 模型选取 | `apps/ai_model/embedding.py` 固定本地模型 | `chat/service/ChatModelRouter.java:80-87` `embeddingModel()`（`model_type=1`） | 等价/更强 | askdata 可远程 embedding |
| E7 | 密钥解密 | `model_factory.py:168-171` `sqlbot_decrypt` | `chat/service/ChatModelRouter.java:94-95` `CryptoUtil.resolvePlain` | 等价 | — |
| E8 | 流式 SSE | LangChain `stream` | `chat/service/ChatModelClient.java:91-140`（SSE 逐帧） | 等价 | — |
| E9 | LangChain4j 备用链路 | 无 | `ai/AiModelConfig.java` + `ai/AskdataAssistantConfig.java`（无调用方，仅 MCP 路径 `ai/AskdataNl2Sql.java` 使用另一套） | 更强（但存在双链路） | 主链路与 MCP 链路模型装配方式不同 |

### F. 知识作用域（高级应用 / 工作空间）

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| F1 | 高级应用 → 术语作用域 | `llm.py:379-389`（type==1 → `calculate_oid = current_assistant.oid`、`calculate_ds_id = None`、传 `assistant.id`） | `ChatKnowledgeService.java:237` → `TerminologyRecallService.java:176-181`（`advanced_application = ?` 并跳过 ds 过滤） | 等价 | 分支语义一致 |
| F2 | 高级应用 → SQL 示例作用域 | `llm.py:435-443` → `data_training.py:540-543` | `SchemaContextBuilder.java:203-206` + `training/TrainingRecallService.java:148-154` | 等价 | — |
| F3 | 高级应用 → 自定义提示词作用域 | `llm.py:409-417`（传 `assistant.id`） | `ChatKnowledgeService.java:280-284` | 等价 | — |
| F4 | **高级应用的 oid 取值** | `llm.py:380` `self.current_assistant.oid`（助手所属工作空间，`system_schema.py:119` 默认 1） | `embedded/AssistantPublicController.java:67-70` 令牌内 oid **硬编码 1L**（仅 type==0 从 configuration 取） | 打折 | 助手归属非默认工作空间时，askdata 的 `oid = ?` 过滤会落空 |
| F5 | 隔离回归测试 | 无对应测试 | `src/test/java/com/xiongx9527/askdata/chat/service/AdvancedAppKnowledgeScopeTest.java:46-96` | 更强 | askdata 有专项测试（但仅覆盖同 oid 场景，未覆盖 F4） |
| F6 | 工作空间继承（type==4 页面嵌入） | `llm.py:380`（type==4 → `self.oid`，继承用户工作空间） | askdata 无 type==4 概念；`assistant_id` 一律按独立应用隔离 | 缺失 | 页面嵌入型助手的工作空间继承语义未实现 |

### G. 运行时 chat 参数与提示词注入

| # | 能力点 | SQLBot | askdata | 判定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| G1 | `chat.sqlbot_name` | `llm.py:248-250` 读取 → `templates/template.yaml:10,93,293,301,400,609,642,704`（8 处模板插值） | `resources/schema.sql:199` 仅种子；后端无读取 | 缺失 | 助手名不进入任何提示词 |
| G2 | `chat.limit_rows` | `llm.py:251-255` → `enable_sql_row_limit` → `llm.py:295` 选择 `query_limit`/`no_query_limit`（`template.yaml:40-76`）；`llm.py:1291-1293` 结果截断 1000 行 | `resources/schema.sql:211` 仅种子；后端无读取；`SchemaContextBuilder.java:883` 硬编码"1000 行以内" | 缺失 | 开关无效，且提示词里恒定含限制策略 |
| G3 | `chat.context_record_count` | `llm.py:256-263` → `base_message_round_count_limit` → `llm.py:291,314` `get_last_conversation_rounds(…, count_limit)` | `resources/schema.sql:223` 仅种子；后端硬编码 5（`ChatQuestionService.java:577-579`） | 缺失 | 改参数不生效 |
| G4 | 术语注入位置 | `llm.py:306-308`（独立 Human 消息 + AI 确认消息） | `SchemaContextBuilder.java:281-283`（user 消息内的 `【业务术语】` 段） | 打折 | 结构化程度不同（SQLBot 是多轮消息序列，askdata 是单 user 消息拼接） |
| G5 | 自定义提示词注入位置 | `llm.py:303-305`（独立 Human 消息） | `SchemaContextBuilder.java:284-286`（user 消息内 `【自定义提示词】` 段） | 打折 | 同上 |
| G6 | SQL 示例注入位置 | `llm.py:309-311` | `SchemaContextBuilder.java:287-292` | 打折 | 同上 |
| G7 | 关键词扩展词用于表匹配 | `llm.py:894/1376`（expanded_keywords 驱动表匹配） | `ChatQuestionService.java:273-281`（`matchText = knowledge.getExpandedKeywords()`） | 等价 | 意图一致 |
| G8 | 追问上下文注入 | `llm.py:314`（受 `context_record_count` 控制） | `ChatQuestionService.java:251` → `SchemaContextBuilder.java:293-296`（前序 Q&A 注入，但轮数硬编码 5） | 打折 | 语义接近，参数不可配 |

### H. 结论摘要中未列出但已核对的等价项

| # | 能力点 | SQLBot | askdata | 判定 |
| --- | --- | --- | --- | --- |
| H1 | 术语 CRUD/分页/全部查询 | `terminology.py:218/232/245/392/579/719/726` | `terminology/TerminologyService.java` + `TerminologyRepository.java` + `TerminologyController.java` | 等价 |
| H2 | 自定义提示词 CRUD/分页/导出/模板/导入 | xpack（源码不可见） | `prompt/CustomPromptService.java`（`:377-379` 类型白名单）、`CustomPromptRepository.java`、`CustomPromptController.java` | 等价/更强（源码可见） |
| H3 | 知识链路四步 + 降级可观测 | `llm.py:373/400/428` 三个 `filter_*` + `current_logs` | `ChatKnowledgeService.java:166-175` `degradationReasons()` + `ChatQuestionService.java:256-268` 三条 `logOperation` | 更强（askdata 有显式降级标志与落库日志） |
| H4 | 高级应用动态数据源 | `llm.py:175-176` `AssistantOutDsFactory` | `ChatQuestionService.java:602-625` `resolveDynamicDatasource` + `SchemaContextBuilder.buildSqlMessagesForConf` | 等价 |

---

## 打折与缺失明细

### 1. 自定义提示词 ANALYSIS / PREDICT_DATA 未注入（缺失·严重）

**SQLBot 行为**

```python
# apps/chat/task/llm.py:585-590（分析链路）
self.filter_terminology_template(_session, self.oid, ds_id)
self.filter_custom_prompts(_session, CustomPromptTypeEnum.ANALYSIS, self.oid, ds_id)
analysis_msg.append(SystemPromptMessage(content=self.chat_question.analysis_sys_question()))
analysis_msg.append(HumanMessage(content=self.chat_question.analysis_user_question()))
```

```python
# apps/chat/task/llm.py:638-642（预测链路）
self.filter_custom_prompts(_session, CustomPromptTypeEnum.PREDICT_DATA, self.oid, ds_id)
predict_msg.append(SystemPromptMessage(content=self.chat_question.predict_sys_question()))
predict_msg.append(HumanMessage(content=self.chat_question.predict_user_question()))
```

`filter_custom_prompts` 内部按类型查库并把命中提示词写回 `self.chat_question.custom_prompt`，随后由 `chat_model.py:332-334`（分析）/ `chat_model.py:339-341`（预测）把 `custom_prompt` 插进模板——`templates/template.yaml:606-638`（分析，`{custom_prompt}` 在 :630）与 `templates/template.yaml:639-680`（预测，`{custom_prompt}` 在 :668）。

**askdata 现状**

```java
// chat/service/ChatKnowledgeService.java:274-277
private List<String> customPrompts(long oid, Long dsId, Long advancedAppId) {
    try {
        StringBuilder sql = new StringBuilder(
                "SELECT prompt FROM custom_prompt WHERE oid = ? AND type = 'GENERATE_SQL' ");
```

```java
// chat/service/SchemaContextBuilder.java:327-334（分析链路，无自定义提示词槽）
public List<ModelMessage> buildAnalysisMessages(String question, String chartConfigJson, String dataJson) {
    List<ModelMessage> messages = new ArrayList<>();
    messages.add(new ModelMessage("system", analysisSystemPrompt()));
    messages.add(new ModelMessage("user",
            "【用户问题】\n" + question + "\n\n【图表配置】\n" + chartConfigJson
                    + "\n\n【查询结果】\n" + dataJson + "\n\n请输出对结果的分析（中文 Markdown）。"));
    return messages;
}
```

`buildPredictMessages`（`:337-346`）同样无插槽；`ChatQuestionService.streamAnalysisOrPredict`（`:419-424`）直接二选一调用这两个方法。

**差距**

- 类型维度被压扁为 1 种：`ANALYSIS`、`PREDICT_DATA` 在运行时不存在读取点。
- 提示词可配置（前端 `web/src/views/system/prompt/index.vue:463-479` 三个 tab）、可存储（`custom_prompt.type`），但对分析/预测**零作用**。
- 无降级提示：用户与运维都看不到"提示词未生效"的任何信号。

**建议**

1. `ChatKnowledgeService.customPrompts` 增加 `type` 入参，改为按类型分别召回（保留 `GENERATE_SQL` 默认值）。
2. `buildAnalysisMessages` / `buildPredictMessages` 增加 `customPromptText` 形参并在 user 消息追加 `【自定义提示词】` 段（与 SQL 生成段格式保持一致）。
3. `ChatQuestionService.streamAnalysisOrPredict` 中按 `analysis ? ANALYSIS : PREDICT_DATA` 取提示词并传入。
4. 在 `logOperation(OPERATE_ANALYSIS/OPERATE_PREDICT, …)` 的 payload 中带上命中的提示词列表，便于观测。
5. 补一条测试：插入 `type='ANALYSIS'` 的 custom_prompt，断言分析请求的提示词中包含该内容。

---

### 2. `chat.sqlbot_name` / `chat.limit_rows` / `chat.context_record_count` 未被后端消费（缺失·严重）

**SQLBot 行为**

```python
# apps/chat/task/llm.py:246-264
chat_params: list[SysArgModel] = await get_groups(args[0], "chat")
for config in chat_params:
    if config.pkey == 'chat.sqlbot_name':
        if config.pval.strip():
            instance.chat_question.sqlbot_name = config.pval
    if config.pkey == 'chat.limit_rows':
        if config.pval.lower().strip() == 'true':
            instance.enable_sql_row_limit = True
        else:
            instance.enable_sql_row_limit = False
    if config.pkey == 'chat.context_record_count':
        ...
        instance.base_message_round_count_limit = count_value
```

三个参数分别流向：助手名（8 处提示词模板插值）、行数策略（`llm.py:295` 选择 `query_limit`/`no_query_limit`，并在 `llm.py:1291-1293` 落库前截断 1000 行）、历史轮数（`llm.py:291,314`）。

**askdata 现状**

```java
// askdata-server/src/main/resources/schema.sql:199
VALUES (5, 'chat.sqlbot_name', 'SQLBot', 'str', 5);
// :211
VALUES (8, 'chat.limit_rows', 'false', 'str', 8);
// :223
VALUES (11, 'chat.context_record_count', '5', 'str', 11);
```

后端 Java 全量检索 `sqlbot_name|limit_rows|context_record_count` 只命中 `schema.sql`；前端 `web/src/stores/chatConfig.ts:42-44,63-70` 拉取了 `chat.limit_rows`/`chat.sqlbot_name`，但 `getLimitRows`/`getSQLBotName` 两个 getter 在 `web/src` 内**零调用点**（只有定义处与 `useChatConfigStore` 导出）。

硬编码现状：

```java
// chat/service/SchemaContextBuilder.java:883
.append("4. 需要聚合/排序/分页时合理处理，结果行数控制在 1000 行以内。\n");
```

```java
// chat/service/ChatQuestionService.java:577-579
if (blocks.size() > 5) {
    blocks = blocks.subList(blocks.size() - 5, blocks.size());
}
```

**差距**

- 三个参数都是"配置可改、运行不生效"的空转配置；`chat.limit_rows=false` 的用户仍然会被提示词要求"控制在 1000 行以内"。
- SQLBot 的 `no_query_limit` 语义（"默认不限制数据量"）在 askdata 完全不存在。
- SQLBot 的运行期硬截断（`llm.py:1291-1293`，同时写 `data_obj['limit']`）在 askdata 不存在，结果集大小不受控。
- 助手名不进入提示词，无法实现"品牌化小助手"。

**建议**

1. 新增 `SysArgService`（或复用 `system/repository/SysArgRepository.java`）按 `chat` 分组读取参数，做进程内缓存 + 变更失效。
2. `SchemaContextBuilder.sqlSystemPrompt(withTraining, dialect)` 增加 `limitRows` 入参，命中 `limit_rows=true` 时输出"1000 行上限 + 必须在最外层限制"，`false` 时输出"不限制 + 尊重用户显式数量"。
3. `ChatQuestionService.priorContext` 的 5 改为读取 `chat.context_record_count`（缺省 5）。
4. 把 `sqlbot_name` 注入 `sqlSystemPrompt`/`analysisSystemPrompt`/`predictSystemPrompt` 的开头（对齐 `template.yaml:10` 的句式）。
5. 结果集落库前按 `limit_rows` 截断并记录 `limit` 标记（对齐 `llm.py:1285-1298`）。
6. 前端 `getLimitRows`/`getSQLBotName` 要么接上（展示限制提示/品牌名），要么删除，避免"看起来生效"的假象。

---

### 3. 术语匹配未基于抽取关键词（打折·高）

**SQLBot 行为**

```python
# apps/chat/task/llm.py:379-394
if self.current_assistant:
    calculate_oid = self.current_assistant.oid if self.current_assistant.type != 4 else self.oid
    if self.current_assistant.type == 1:
        calculate_ds_id = None
# 使用原始提取的关键词（未扩展）进行术语匹配，避免同义词重复查询
match_text = self.chat_question.extracted_keywords or self.chat_question.question
```

```python
# apps/terminology/curd/terminology.py:981-993
# 有逗号时视为关键词列表，逐个匹配（精度更高，避免整体 ILIKE 误匹配）
if ',' in question:
    parts = [kw.strip() for kw in question.split(',') if kw.strip()]
else:
    parts = [question]
for part in parts:
    for item in select_terminology_by_word(session, part, oid, datasource, advanced_application_id):
```

**askdata 现状**

```java
// chat/service/ChatKnowledgeService.java:218-235
List<Map<String, Object>> groups = recallTerms(question, oid, dsId, advancedAppId);
// 2.1：LLM 业务实体抽取（带历史上下文）作为术语命中的补充种子；模型不可用/失败则退化为词法命中
List<String> matched = matchedWords(groups, question);
boolean keywordLlmUsed = false;
List<String> llmEntities = extractEntitiesWithLlm(question, historyText);
...
String expanded = String.join(",", expandKeywords(extractedList, groups));
String terminologyText = terminologyTemplate(groups);
```

`recallTerms`（`:243-253`）把**原始问题**交给 `TerminologyRecallService.recallGroups`，LLM 抽取在之后才发生，且其结果只用于 `extracted`/`expanded` 关键词，未参与 `groups` 的重算。

**差距**

- 术语召回的实际查询文本与 SQLBot 不同：SQLBot 是"抽取出的名词性实体"，askdata 是"整句自然语言"。
- SQLBot 的逗号切分逐词匹配专门规避"整体 ILIKE 误匹配"；askdata 走子串匹配，短词（如"额"）容易命中噪声术语。
- askdata 新增的 LLM 实体抽取（工作区未提交改动）**只加在关键词侧，未接入术语召回**，等于把 SQLBot 的高精度链路降级成"召回 + 事后打标"。

**建议**

1. `prepare` 改为两段式：先做 LLM 实体抽取得到 `extracted`，再以 `extracted`（或 `extracted` 为空时回退 `question`）作为术语召回的查询文本，等价于 SQLBot `match_text = extracted_keywords or question`。
2. 若抽取结果含逗号分隔的多词，按 SQLBot 逻辑拆词逐个召回后合并（`ChatKnowledgeService.recallTerms` 增加按词召回分支）。
3. 召回结果保留"命中的词"用于 `matchedWords`（当前 `matchedWords` 只保留"在问题里真实出现"的词，`ChatKnowledgeService.java:363-379`），改成"抽取词命中组内任一词"。

---

### 4. SQL 示例导入不写向量（缺失·高）

**SQLBot 行为**

```python
# apps/data_training/curd/data_training.py:407-409（batch_create_training 末尾）
if inserted_ids:
    run_save_data_training_embeddings(inserted_ids)
```

导入入口 `apps/data_training/api/data_training.py:230` 调用 `batch_create_training`，因此**导入即带向量**。

**askdata 现状**

```java
// training/TrainingService.java:327-329（uploadExcel 内）
trainingRepository.insert(oid, datasource, question, description, true, advancedApplication,
        System.currentTimeMillis());
successCount++;
```

对比新建/编辑路径是有的：

```java
// training/TrainingService.java:176
trainingRecallService.saveEmbeddings(java.util.Collections.singletonList(newId));
```

**差距**

- 导入的示例 `embedding` 为 NULL，`TrainingRecallService.recallByDatasource`（`training/TrainingRecallService.java:115,161`）只对 `embedding IS NOT NULL` 的行做向量召回 → 导入的示例**召回不到**。
- 兜底路径 `SchemaContextBuilder.trainingSamples` 会读到这些行（`WHERE embedding IS NULL` 不过滤），但走的是 `topK` 词法/重算路径（`:231,708-710`），与 SQLBot 的"导入即向量"不等价。
- 只有等到下次应用启动、且 `EmbeddingBackfillRunner.backfillTraining`（`:134-148`）跑过之后才恢复。

**建议**

1. `uploadExcel` 收集 `insert` 返回的 id，批量调用 `trainingRecallService.saveEmbeddings(ids)`（注意事务边界：向量写入要放到事务提交之后，或用 `TransactionSynchronizationManager` 注册 afterCommit，避免长事务持有 embedding 写锁）。
2. 或者在 `TrainingRepository.insert` 内做"有 embedding 模型时同步写向量"（与 `TerminologyService.create` 的做法一致，`:205`），保持两条写入路径同构。
3. 补测试：导入 2 行 → 断言 `data_training.embedding IS NOT NULL`。

---

### 5. AI 模型层不按协议分发、`additional_params` 未生效（缺失·高）

**SQLBot 行为**

```python
# apps/ai_model/model_factory.py:115-120
_llm_types: Dict[str, Type[BaseLLM]] = {
    "openai": OpenAILLM,
    "tongyi": OpenAILLM,
    "vllm": OpenAIvLLM,
    "azure": OpenAIAzureLLM,
}
```

```python
# apps/ai_model/model_factory.py:160-166,174-181
additional_params = {}
if db_model.config:
    config_raw = json.loads(db_model.config)
    additional_params = {item["key"]: prepare_model_arg(item.get('val')) for item in config_raw if ...}
...
return LLMConfig(
    model_id=db_model.id,
    model_type="openai" if db_model.protocol == 1 else "vllm",
    ...
    additional_params=additional_params,
)
```

```python
# apps/chat/task/llm.py:196-201（按需关闭思考模式）
if no_reasoning:
    if self.config.additional_params:
        if self.config.additional_params.get('extra_body'):
            if self.config.additional_params.get('extra_body').get('enable_thinking'):
                del self.config.additional_params['extra_body']['enable_thinking']
```

**askdata 现状**

```java
// chat/service/ChatModelRouter.java:44-66
public static class ModelRef {
    public final long id;
    public final String name;
    public final String baseModel;
    public final String apiDomain;
    public final String apiKey;
    public final Double temperature;
    ...
}
```

```java
// chat/service/ChatModelClient.java:109-120
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("model", baseModel);
...
payload.put("messages", msgs);
payload.put("stream", true);
connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));
```

```java
// chat/service/ChatModelClient.java:350-365
static String chatCompletionsUrl(String apiDomain) {
    String base = trimBase(apiDomain);
    if (base.endsWith("/chat/completions")) { ... }
    return base + "/v1/chat/completions";
}
```

`protocol`/`supplier` 只在 `ai_model` 表存取（`system/repository/AiModelRepository.java:31,64-78`、`system/service/AiModelService.java:97-100,428-431`），`ModelRef` 不含二者。`temperature` 被解析进 `ModelRef`（`ChatModelRouter.java:96,99-110`），但主链路请求体里没有它——只在连通性检查里出现（`system/service/AiModelService.java:272-273`）。

**差距**

- 供应商差异（Azure 的 `api_version`/`deployment_name`、tongyi 的特殊字段）无法配置。
- `ai_model.config` 里除 temperature 外的参数（`max_tokens`、`top_p`、`extra_body.enable_thinking` 等）全部被丢弃；`enable_thinking` 更是无任何处理入口。
- 主链路实际不带 temperature，用户在参数页设的采样温度无效。

**建议**

1. `ModelRef` 增加 `protocol`、`supplier`、`additionalParams`（Map<String,Object>），`ChatModelRouter.toRef`（`:89-97`）一次性解析 `ai_model.config`。
2. `ChatModelClient.streamCompletion` 把 `temperature` + `additionalParams` 写进 payload；对 `extra_body` 做平铺（OpenAI 兼容协议中 `extra_body` 通常需要展开到顶层）。
3. 增加 `enable_thinking` 开关：当 `additionalParams` 里 `extra_body.enable_thinking=true` 且请求处于"无思考"模式时删除该键（对齐 `llm.py:196-201`）。
4. `protocol` 至少区分 "OpenAI 兼容" 与 "Azure" 两条：`api_version`/`deployment_name` 走 `?api-version=` 查询参数 + `model` 用 deployment 名。

---

### 6. 高级应用 oid 硬编码为 1（打折·高）

**SQLBot 行为**

```python
# apps/chat/task/llm.py:379-381
if self.current_assistant:
    calculate_oid = self.current_assistant.oid if self.current_assistant.type != 4 else self.oid
    if self.current_assistant.type == 1:
        calculate_ds_id = None
```

助手 oid 来自 `sys_assistant` 自身（`apps/system/schemas/system_schema.py:113-122`，`oid: Optional[int] = Field(default=1)`）。

**askdata 现状**

```java
// embedded/AssistantPublicController.java:67-70
long assistantOid = 1L;
if (Rows.intg(assistant, "type", 0) == 0) {
    assistantOid = oidOfConfiguration(Rows.str(assistant, "configuration"), 1L);
}
```

知识查询又强制 oid 匹配：

```java
// terminology/TerminologyRecallService.java:156-160
"SELECT t.id, t.embedding FROM terminology t WHERE t.oid = ? AND t.enabled = TRUE "
        + "AND t.embedding IS NOT NULL"
```

```java
// chat/service/ChatKnowledgeService.java:277
"SELECT prompt FROM custom_prompt WHERE oid = ? AND type = 'GENERATE_SQL' "
```

**差距**

- 高级应用（type=1）走嵌入式访问时，askdata 固定用 oid=1；SQLBot 用助手自身 oid。若应用归属工作空间 oid≠1（askdata 的 `sys_assistant.oid` 是可变的，见 `embedded/AssistantRepository.java:94-100`），该应用的专属术语 / SQL 示例 / 自定义提示词在问数时**全部召不回**。
- `AdvancedAppKnowledgeScopeTest`（`:46-96`）全部用同一 oid（1L），因此覆盖不到这个缺口。
- SQLBot 对 type==4（页面嵌入）走 `self.oid`（继承当前用户工作空间），askdata 无对应类型，该语义整体缺失。

**建议**

1. `AssistantPublicController.info` 改为对所有类型统一取 `assistant.oid`（`Rows.lngOrNull(assistant, "oid")`，为空再回落 1），并写入令牌 `oid`。
2. `AssistantRepository.list/listAdvancedApplications` 已按 oid 过滤，检索时保持同一口径。
3. 扩展 `AdvancedAppKnowledgeScopeTest`：用两个不同 oid 的助手 + 术语，断言"应用 A 的会话召不回应用 B 的知识"，并覆盖"助手 oid≠1"的场景。

---

### 7. 第二套术语渲染与死代码（缺失·中）

**SQLBot 行为**：只有一套 XML 渲染（`terminology.py:952-972` + `templates/template.yaml:2-4`）。

**askdata 现状**

```java
// terminology/TerminologyService.java:465（定义）
public String buildTerminologyContext(String question, long oid, long dsId, Long advancedApp) {
```

全仓检索 `buildTerminologyContext` 仅命中该定义行，无调用方。

**差距**

- 同一份术语数据存在两种提示词形态（XML vs `- 术语「x」：…` bullet），后续维护者改错分支的概率很高。
- bullet 形态本身也不是 SQLBot 语义（SQLBot 是结构化 XML，含 `words` 列表 + `description`）。

**建议**

- 删除 `TerminologyService.buildTerminologyContext`（及其私有辅助），或改造成 `ChatKnowledgeService.terminologyTemplate` 的薄封装并入同一处；保留唯一 XML 渲染实现。

---

### 8. 导出/模板 i18n 缺失（缺失·中）

**SQLBot 行为**

```python
# apps/terminology/api/terminology.py:94-100
fields = []
fields.append(AxisObj(name=trans('i18n_terminology.term_name'), value='word'))
fields.append(AxisObj(name=trans('i18n_terminology.synonyms'), value='other_words'))
```

**askdata 现状**

```java
// terminology/TerminologyService.java:267-273
List<ExcelUtils.Column> columns = new ArrayList<>();
columns.add(new ExcelUtils.Column("术语名称", "word"));
columns.add(new ExcelUtils.Column("同义词", "other_words"));
columns.add(new ExcelUtils.Column("术语描述", "description"));
```

```java
// training/TrainingService.java:230-233
```

**差距**

- 导出/模板文件的表头恒为中文，而 askdata 前端已支持 5 种语言（`web/src/i18n/*.json`），用户在非中文环境拿到的是中文表头，回导时列名不匹配（SQLBot 靠 i18n 保持一致）。

**建议**

- 后端按 `Accept-Language` 或请求参数返回表头；最小实现可先提供"按语言码查表"的静态 Map（`zh-CN`/`en`/`zh-TW`/`ko-KR`/`th-TH`），并在导出接口上支持 `?lang=`。

---

### 9. SQL 示例条数与格式不稳定（打折·中）

**SQLBot 行为**

- 条数：`config.py:110` `EMBEDDING_DATA_TRAINING_TOP_COUNT=5`，直接进 SQL `LIMIT`（`data_training.py:506,517`）。
- 格式：XML（`data_training.py:587` `{'question':…, 'suggestion-answer':…}` → `:596-616`）。

**askdata 现状**

```java
// chat/service/SchemaContextBuilder.java:59
private static final int TRAINING_LIMIT = 3;
```

```java
// chat/service/SchemaContextBuilder.java:215（向量路径文本）
texts.add(String.valueOf(q == null ? "" : q) + "\n" + (sql == null ? "" : sql));
```

```java
// chat/service/SchemaContextBuilder.java:733（兜底路径文本，格式不同）
result.add("问：" + Rows.str(row, "question") + "\nSQL：" + Rows.str(row, "description"));
```

```java
// chat/service/SchemaContextBuilder.java:710（兜底路径重算全部样例向量）
List<double[]> vectors = embedAll(question, texts);
```

**差距**

- 同一次问数，命中向量时给模型 5 条样例、未命中时给 3 条，且文本形态不同（`q\nsql` vs `问：q\nSQL：sql`）。
- 兜底路径把全部样例重新向量化，随样例量线性增长，与"不再每次提问重算 embedding"的目标相悖。
- 未使用 SQLBot 的 `<sql-examples><sql-example>` XML 形态，模型对"样例"的结构感知弱化。

**建议**

1. 统一条数为 5（对齐 `EMBEDDING_DATA_TRAINING_TOP_COUNT`），删掉 `TRAINING_LIMIT=3` 的分叉。
2. 统一文本渲染函数（例如 `formatSample(q, sql)`），两条路径共用。
3. 兜底路径优先用已落库向量（`data_training.embedding IS NOT NULL` 的行）做 cosine 排序，只对"无向量的行"做一次性词法打分；避免每问全量重算。
4. 迁移到 XML 形态（`<sql-examples><sql-example><question>…</question><suggestion-answer>…</suggestion-answer></sql-example></sql-examples>`），与术语段风格统一。

---

## 附：证据索引（本次审计实际读取并核对的文件）

**SQLBot（backend/）**：`apps/chat/task/llm.py`、`apps/chat/models/chat_model.py`、`apps/chat/curd/chat.py`、`apps/chat/api/chat.py`、`apps/terminology/curd/terminology.py`、`apps/terminology/api/terminology.py`、`apps/data_training/curd/data_training.py`、`apps/data_training/api/data_training.py`、`apps/ai_model/model_factory.py`、`apps/ai_model/embedding.py`、`apps/system/schemas/system_schema.py`、`apps/system/crud/aimodel_manage.py`、`apps/system/models/system_model.py`、`common/core/config.py`、`common/core/deps.py`、`common/utils/embedding_threads.py`、`common/utils/distributed_lock.py`、`common/utils/dict_to_xml.py`、`templates/template.yaml`、`main.py`。

**askdata（askdata-server/）**：`chat/service/ChatKnowledgeService.java`、`chat/service/ChatQuestionService.java`、`chat/service/ChatModelRouter.java`、`chat/service/ChatModelClient.java`、`chat/service/SchemaContextBuilder.java`、`chat/repository/ChatRepository.java`、`terminology/TerminologyService.java`、`terminology/TerminologyRecallService.java`、`terminology/TerminologyRepository.java`、`terminology/TerminologyController.java`、`training/TrainingService.java`、`training/TrainingRecallService.java`、`training/TrainingRepository.java`、`prompt/CustomPromptService.java`、`prompt/CustomPromptRepository.java`、`ai/EmbeddingService.java`、`ai/LocalEmbeddingModel.java`、`ai/AiModelConfig.java`、`ai/AskdataAssistantConfig.java`、`ai/AskdataNl2Sql.java`、`common/startup/EmbeddingBackfillRunner.java`、`common/lock/SingleWorkerGuard.java`、`system/service/AiModelService.java`、`system/repository/AiModelRepository.java`、`system/repository/SysArgRepository.java`、`embedded/AssistantPublicController.java`、`embedded/AssistantRepository.java`、`resources/schema.sql`、`web/src/stores/chatConfig.ts`、`web/src/views/system/parameter/index.vue`、`src/test/java/com/xiongx9527/askdata/chat/service/AdvancedAppKnowledgeScopeTest.java`。

**无法核验的部分**：`sqlbot_xpack/` 在 SQLBot backend 中不存在，自定义提示词的**数据来源与筛选实现**（`find_custom_prompts`）不可见；本报告对 C 组仅按调用点（`llm.py:400/587/638`）与参数语义判定，未对 xpack 内部实现做等价性断言。
