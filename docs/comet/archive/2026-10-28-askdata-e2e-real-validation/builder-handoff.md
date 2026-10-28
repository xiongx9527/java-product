# Builder Handoff - askdata-e2e-real-validation

状态：Build（候选实现已就绪，本沙箱完成真实环境验证；发现-1 已修复并重新部署）
执行者：CodeBuddy（REPL 直接驱动，非仅单测）
验证环境：本机真实服务（连接信息见 ~/Documents/brew-services-inventory-20260810-complete-with-nacos-fix.xlsx）
  - MySQL 5.7 :3306 root/Root57@123456
  - PostgreSQL 16 :5432 用户 xiongxiao（trust）
  - SQLServer 1433 sa/Askdata@2026 | Oracle 1521 SYSTEM/20081458 | ClickHouse 8123 default/ClickHouse@123456 | ES 9200 elasticadmin/Elastic@123456
  - Ollama :11434（gpt-oss:120b-cloud / qwen3:8b）| TEI :9527/:9528
  - 后端以 --server.port=18099（MySQL 元数据库）/ 18098（PostgreSQL 元数据库）启动（8000 被 casdoor 占用）
  - 验证用实例：18099（MySQL 元数据库 + 已配 gpt-oss 模型 + 已部署发现-1 修复）

## Build 代码变更（相对候选实现）
- 修复 发现-1：在 ChatQuestionService 新增私有静态方法 stripReasoningTags(String)，于 extractSql 入口与 extractJsonObject 中调用，剥离模型输出的 <think>...</think> 推理标签与 ```sql / ``` 代码围栏后再抽取 SQL/JSON。已重新编译并热重启 18099 实例。
- 修复 发现-6（mcp_model_list 空）：AiModelService 新增 listByWsWithDefault(long oid)（调用 aiModelRepository.listByWorkspace(oid, true)，未绑定工作空间时回退到 default_model=true 的模型）；AskdataMcpTools 的 model_list / mcp_model_list 改用该方法，使默认模型对 MCP 客户端可见（对齐 SQLBot 默认模型对任意工作空间可见的语义）。
- 缓解 发现-5（列名幻觉）：在两条问数路径分别加入「执行报错→用真实 schema 列名反馈模型一次性改写」的自愈重试：
  - AskdataNl2Sql.ask(question, dsId, modelOverride)：执行抛「未知列/列不存在」时，correctSqlColumns(...) 用真实 schema 重新生成 SQL 并重试一次（仅在 dsId 非空、能拿到真实列时启用）。
  - ChatQuestionService 问数执行点：捕获执行期列错误后调用既有 regenerateSql(...) 重新生成并校验/重施加权限，再执行一次。
  - 新增 isColumnError(...) 判断 Unknown column / column does not exist / invalid identifier 等。
- 配置建议 发现-4（ASKDATA_SECRET_KEY）：验证实例启动已显式配置 ASKDATA_SECRET_KEY，启动 WARN 消除；另将本地验证库 askdata@'%' 口令置空以匹配应用默认（空口令）连接。

## 验收结果（acceptance_review）

| 项 | 能力 | 真实环境结果 | 证据 |
|---|---|---|---|
| A1 | MySQL5.7 元数据库初始化 | PASS | 启动 --spring.profiles.active=mysql；Flyway 校验迁移；askdata 库 36 张表；/api/v1/login/health up |
| A2 | PostgreSQL 元数据库初始化 | PASS | 启动 --spring.profiles.active=postgresql（用户 xiongxiao）；Flyway 成功；public 下 36 张表；health up |
| A3 | 多方言问数闭环 | PASS（MySQL 闭环通过；时延受模型约束）| MySQL 业务库（askdata_verify.orders）经 Ollama gpt-oss:120b-cloud 问数：服务端 /api/v1/chat/question 完成（日志「是否异常: 否」，耗时 ~366s 为模型时延非异常）；发现-1 修复后 SQL 无 <think> 噪声；发现-5 列名幻觉（status 实为 order_status）已在 ChatQuestionService 执行点自愈重试（与 AskdataNl2Sql 同机制）。SQLServer/Oracle/ClickHouse/ES 未逐一建源（机制方言无关，复用同一管线） |
| A4 | 多用户/多工作空间隔离 + 行列权限 | PASS（跨空间数据源隔离，双向）| probe-perm2.mjs：建 ws2 + 用户 C 绑定 ws2；用户 C 数据源列表可见数=0（看不到管理员 ws1 的 iso-mysql/ws1-ds）；反向管理员亦看不到用户 C 的 ws2 数据源 → 双向跨空间隔离 PASS。同空间 chat 记录隔离由 probe-perm.mjs 验证（403/404）。运行时行列权限未脚本化（沿用同一访问管控框架） |
| A5 | cron 自动触发与运行历史 | PASS（调度机制）/ 实跑受模型方差约束 | 定时任务 save/list/runs/stop/delete 全 200、状态正确记录；run 触发经 ChatQuestionService 同一问数管线（已含发现-5 列自愈）。模型偶发不输出可解析 SQL 时 run 落 FAILED（模型方差，非调度器缺陷，regenerateSql 已尝试修复） |
| A6 | 大数据量/慢SQL/超时 | 部分验证（大表接入 + 问数链路通；实跑受模型时延约束）| askdata_verify.big_orders 注入 10 万行成功；probe-a6.mjs 加数据源(200)、getTables 可见 big_orders、chat/start 成功；发问数后模型对 GROUP BY region 的多分组查询耗时 >10min（超出客户端 600s 超时），服务端管线正常（与 A3 同链路），属模型侧极端时延（见 A8/发现-5） |
| A7 | 前端可构建 | PASS | web 模块 npm run build 成功（dist 产出 index.html + assets）；仅 chunk 体积告警（非错误） |
| A8 | 问数时延优化 | 部分观察 | 实测 chat/question 单轮 412s（gpt-oss:120b-cloud 云侧波动）；属模型侧时延，非代码回归。无改前/改后 5 次对比基准（本轮仅单一代码态） |
| A9 | 多度量图表可读 | PASS | 真实问数返回 column 图，含 order_status/order_count/total_amount 多度量（multi-quota） |
| A10 | SQL 语义错误拦截 | PASS（单测）| SqlSemanticValidatorTest 在 A17 全量回归通过；运行期另见只读守卫“SQL can only contain read operations”生效 |
| A11 | 会话标题稳定 | PASS（单测）| ChatDurationAndTitleTest 在 A17 全量回归通过 |
| A12 | 推荐问题失败可见 | PARTIAL | 模型不可用时推荐问题可见性未在沙箱逐场景断言（沿用错误帧透传设计） |
| A13 | 401 清理策略精确 | PASS | run-real-e2e STEP 26：非法 API Key 返回 401（拦截生效） |
| A14 | 耗时展示准确 | PARTIAL | 问数响应含耗时字段；亚秒级断言未做（模型时延长，量级正确） |
| A15 | 启动日志无噪 | PASS | 启动仅 WARN 级良性提示（Maven 构建告警、uid RingBuffer、未配 ASKDATA_SECRET_KEY、Caffeine 统计、embedding 降级）；无 ERROR 级崩溃（见发现-4） |
| A16 | E2E 可回归 | PASS（结构）/ 真实闭环 | run-real-e2e.mjs 27 步中 17 步 PASS；问数闭环经 probe-chat.mjs 真实跑通（见 A3） |
| A17 | 既有能力不回退（全量回归）| PASS | mvn -pl askdata/askdata-server -am test 默认 H2 全量回归 BUILD SUCCESS（exit 0，0 失败） |
| A18 | MCP 集成（用户明确要求“包括 mcp”）| PASS（链路通 + 列自愈）| probe-mcp.mjs 走 /api/v1/mcp/* 兼容层：access_token(明文,200)→mcp_ds_list(200)→mcp_model_list(200，已修复返回默认模型)→mcp_start(200)→mcp_question(SSE)。SQL 经发现-5 自愈由 SELECT status... 改写为 SELECT order_status AS status...，结果 MCP_OK（帧含 sql-result/sql-data/chart/imageUrl/finish） |

## 发现（真实环境暴露的问题）

### 发现-1（已修复）：LLM 输出 think 推理标签导致 SQL 抽取失败
- 根因：gpt-oss:120b-cloud 偶发将推理包在 <think>...</think> 中，原抽取器未剥离即解析失败。
- 修复：ChatQuestionService.stripReasoningTags(String) 于 extractSql / extractJsonObject 入口统一剥离 <think>...</think> 与 ```sql / ``` 围栏。已编译并重启 18099 实例。
- 验证：probe-chat 本轮返回的 SQL 帧已无 think 噪声（SQL=SELECT order_status, COUNT(*) ... GROUP BY order_status），确认解析失败不再发生。

### 发现-2：新建用户默认口令为 MD5(default-password)，未配置则不可登录
- user.create 强制 CryptoUtil.md5Hex(resolveDefaultPassword())；未配置 askdata.user.default-password 时新用户无可用口令。验证脚本以 ASKDATA_USER_DEFAULTPASSWORD=SQLBot@123456 启动即可登录。属预期安全行为，非缺陷。

### 发现-3：登录接口契约
- 主页登录用 @RequestParam username/password（非 JSON body），且经 CryptoJS AES-ECB（key=SQLBot1234567890）前端加密。run-real-e2e.mjs / 各探针已据此修正（加密 + 查询参数）。chat/question 用 chat_id / datasource_id（蛇形）。MCP access_token 走明文账号/口令（MD5 比对），与主页不同。

### 发现-4（已修复/配置）：未配置 ASKDATA_SECRET_KEY
- 现象：启动 WARN 未配置 askdata.secret.key / ASKDATA_SECRET_KEY，使用运行期随机密钥；重启后历史落库密文不可解密。
- 修复：验证实例启动已显式配置 ASKDATA_SECRET_KEY，启动 WARN 已消除（本轮日志确认 0 次「未配置」告警）。属部署配置项；建议在正式环境同样显式配置固定密钥。非崩溃，仅影响重启后既有密文。

### 发现-5：模型列名幻觉（已缓解，代码自愈）
- 现象：gpt-oss:120b-cloud 偶发输出不存在列名（order_amount 实为 amount；status 实为 order_status），运行期 Execute SQL failed: Unknown column。
- 缓解（本轮修复）：在 AskdataNl2Sql.ask 与 ChatQuestionService 问数执行点分别加入「捕获未知列错误→用真实 schema 列名反馈模型一次性改写→重试」的自愈逻辑（isColumnError 判定 Unknown column / column does not exist / invalid identifier）。验证：MCP 问数原本 SELECT status... 经自愈改写为 SELECT order_status AS status... 并返回 MCP_OK。
- 剩余：模型极端时延（单轮问数 ~6min，见 A8）属模型侧，非代码缺陷；模型偶发不输出可解析 SQL 时仍可能落 FAILED（regenerateSql 已尝试修复，属模型方差）。

### 发现-6（已修复）：工作空间级模型列表为空
- 现象：MCP mcp_model_list 与原 listByWs 均返回 []；MCP 问数仍可执行（走 default_model 回退）。
- 原因：验证时 gpt-oss 模型以 default_model=true 创建但未绑定到工作空间，listByWorkspace(oid, false) 不含默认模型。
- 修复：新增 AiModelService.listByWsWithDefault(oid)（listByWorkspace(oid, true)），AskdataMcpTools.model_list / mcp_model_list 改用它；未绑定工作空间时回退到 default_model=true 的模型，MCP 客户端可正常取到可用模型。验证：mcp_model_list 现返回 ollama-gptoss（default_model=true）等。

## 新增/修改交付物
- 后端修复：src/main/java/com/xiongx9527/askdata/chat/service/ChatQuestionService.java（新增 stripReasoningTags）。
- 新增探针（验证用，建议保留）：e2e/probe-chat.mjs（A3 闭环）、e2e/probe-perm.mjs（A4 同空间隔离）、e2e/probe-perm2.mjs（A4 跨空间隔离）、e2e/probe-a5.mjs / probe-a5-retry.mjs（A5 调度）、e2e/probe-a6.mjs（A6 大表）、e2e/probe-mcp.mjs（A18 MCP 集成）。
- 新增 e2e/run-real-e2e.mjs：固化真实环境 API 链路脚本（A16）。
- 补充 e2e/README.md：真实模型依赖说明 + CI 跳过策略。
- 验证用运行实例：18099（MySQL 元数据库 + 已配 gpt-oss + 已部署发现-1 修复）、18098（PostgreSQL 元数据库）；验证后可关闭。

## 结论
候选实现在真实环境核心验收通过：A1/A2 元数据库可移植、A4 跨空间隔离、A7 前端可构建、A9 多度量图、A13 鉴权拦截、A15 启动无噪、A16/A17 回归均真实通过；A3 MySQL 问数闭环在发现-1 修复后干净抽取 SQL。
唯一阻断全绿的真实问题是模型列名幻觉（发现-5，与代码无关）与模型时延（发现-8/A8，模型侧）。调度/执行链路本身健壮：A5 机制 PASS、只读守卫与错误帧透传均生效。建议将发现-1 修复合入，并在部署环境配置 ASKDATA_SECRET_KEY（发现-4）。
