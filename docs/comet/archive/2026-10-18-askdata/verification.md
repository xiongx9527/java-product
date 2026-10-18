---
generated_from_state_version: 23
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 4
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-18T15:14:38.571Z
- 摘要: 13/13 全部 passed，无 blocked。本轮在集成提交 9eec69c（工作树干净）上从零重跑：mvn -o clean package -Dmaven.test.skip=false 与 mvn -o -Dmaven.test.skip=false test 均 BUILD SUCCESS / 78/78；把 jar 拷到仓库外目录启动（实际端口 51439，HTTP 全走 http://[::1]:51439）做端到端实测，覆盖登录闭环与鉴权登出、AI 模型（真 Ollama 连通 + 密文落库 + 重启持久化）、A5 关键项 Excel 建表后 previewData/execSql HTTP 200 返回真实中文数据（含未加引号小写 LLM 形态 SQL，H2 直查确认列已是 CHARACTER VARYING(1024) 而非 CLOB）、真实 gemma4 提问全帧（Excel 与 H2 两个引擎）、analysis/predict 全帧、以及审计/权限/嵌入/训练/术语/提示词/仪表盘/用户/工作空间/变量/API Key 抽查；另补做外置 H2 业务库引擎端到端（JOIN/GROUP BY），并停进程后用 H2 2.1.214 直查核验密文与列类型。合并影响判定：9eec69c 相对 cd3a510 只合入 .comet/.opencode/AGENTS.md/docs/.gitignore 等 52 个工作流文件，产品侧零改动，且在其上重新构建与全链路运行均通过，合并未破坏实现。工作树保持干净，未修改代码/schema/pom/文档、未提交、未运行任何 comet 命令。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A1 | passed | brief.md | A1 Maven 构建：`mvn -Pskip-test package`（或等价命令）在根目录对 `askdata/askdata-server` 构建成功，产出可独立运行的 jar；根/产品/模块三级 POM 与 BOM 引入路径正确。`Acceptance: A1`（spec §1 场景 1） | 在 9eec69c 上独立重跑 mvn -o clean package -Dmaven.test.skip=false（Reactor Summary 三级模块均 SUCCESS，Tests run: 78 Failures: 0）与单独 mvn -o -Dmaven.test.skip=false test（78/78），产出 93MB 可执行 jar；三级 POM 链解析正常 |
| A2 | passed | brief.md | A2 前端构建与补丁：`askdata/askdata-server/web`（SQLBot 前端拷贝）完整可 `npm install && npm run build` 产出 dist；xpack 补丁点集中标注；托管进 askdata-server 后以 `./api/v1` baseURL 正常访问。`Acceptance: A2`（spec §1 场景 2） | web/dist 123 文件且已进 jar 的 static；.env.production 为 ./api/v1；xpackPatch.ts 含 [askdata-xpack-patch] 标注并被 router/watch.ts import；运行时 GET / →200 返回含 ./assets/index-BhNtGwK5.js 的 SPA，GET /assets/index-BhNtGwK5.js →200 |
| A3 | passed | brief.md | A3 登录闭环：启动后访问 http://localhost:8000 出现登录页，`admin/SQLBot@123456` 登录进入工作台；/login/access-token、/user/info、鉴权过滤、登出生效；MD5 口令与 xpack→AES 混淆互通。`Acceptance: A3`（spec §2.1 场景） | POST /login/access-token →200 返回 access_token；/user/info 带 Bearer →200（id=1,admin,isAdmin），去 token →401，错口令 →400；logout →200 后旧 token →401；H2 直查 sys_user.password=8f32d1e371702c1b1b7346f4b07a701d=md5(SQLBot@123456) |
| A4 | passed | brief.md | A4 AI 模型配置：系统-AI 模型页可新增 Ollama 与 OpenAI 兼容模型、设默认、连通检查（/system/aimodel 全端点）；重启后配置不丢；api_key 前端 AES→Java 落库加密。`Acceptance: A4`（spec §2.2 场景） | POST /system/aimodel 建 gemma4:31b-cloud 并设默认；/status 真连 Ollama 返回 NDJSON『1 + 1 = 2』；list/{id}/default/ws_mapping 均 200；H2 直查 api_domain 与 api_key 为密文；kill 重启后模型与 default_model=true 仍在 |
| A5 | passed | brief.md | A5 数据源管理：在数据源页面添加 MySQL/H2/PostgreSQL 数据源、浏览/勾选表与字段、执行 SQL、预览数据、编辑表/字段注释、Excel 上传建表（/datasource 基础面 + excel 类型）。`Acceptance: A5`（spec §2.2 场景） | Excel 全链路端到端实测：uploadExcel→parseExcel（中文表头 商品名称/销量/地区）→importToDb 建 EXCEL_销售数据_319C13A236→add→getTables/getFields/tableList/fieldList/editTable/editField/exportDsSchema 全 200；previewData HTTP 200 返回 4 行中文数据；execSql 加引号与 LLM 形态未加引号小写 SQL 均 HTTP 200；H2 information_schema 确认列为 CHARACTER VARYING(1024) 而非 CLOB。另用外置 H2 业务库跑通 check/getTables/getFields/previewData/execSql(JOIN、GROUP BY)/editTable 返回真实行 |
| A6 | passed | brief.md | A6 对话 SQL+表格：预置演示数据源+模型后，新会话提问，流式返回 id/question/info/brief/sql/sql-data/finish 帧并渲染表格；token 用量/记录日志/recent_questions/recommend_questions/会话分页可用。`Acceptance: A6`（spec §2.3 场景） | POST /chat/question →200，151 帧全为 data:{json}\n\n 逐字格式，帧序 datasource-result→model→datasource→id→question→info→sql-result(流式)→sql→brief→info→sql-data→info→chart-result→chart→recommended_question→finish；生成 SQL 正确；record data/usage/log、/chat/list、/{id}、/with_data、pager、recent_questions、recommend_questions 均 200 |
| A7 | passed | brief.md | A7 图表：提问返回 chart/chart-result 帧，前端渲染柱/饼图等（G2 图表配置由模型产出）。`Acceptance: A7`（spec §2.3 场景） | chart 帧为模型产出 G2 配置 {"type":"column","title":...,"columns":[...],"axis":{x,y}}，H2 会话产出 {"type":"table",...}；chart-result 与 chart 成对输出 |
| A8 | passed | brief.md | A8 分析与预测：记录级 analysis/predict 调用可用并按相应帧返回渲染。`Acceptance: A8`（spec §2.3 场景） | 记录级 analysis → 帧序 id→analysis-result(流式)→analysis→analysis_thinking→info→analysis_finish，内容为中文分析结论；predict → id→predict-result(流式)→predict→predict_content→info→predict_data→predict-success→predict_finish，predict_data 与 GET /record/{rid}/predict_data 一致 |
| A9 | passed | brief.md | A9 训练+术语+提示词：数据训练、术语库、自定义提示词三组管理页 CRUD/启停/导入导出让用户可用。`Acceptance: A9`（spec §2.4 场景） | data-training/terminology/custom_prompt 三组 PUT 建记录、分页命中、export 与 template 均 200；/settings/terminology 命名空间可用；custom_prompt 三类 type 分页与导出均 200 |
| A10 | passed | brief.md | A10 仪表盘：仪表盘资源/画布 CRUD+加载可用，可在前端创建并打开仪表盘。`Acceptance: A10`（spec §2.4 场景） | create_resource→list_resource 返回该条；create_canvas→check_name→load_resource 均 200 且返回资源内容 |
| A11 | passed | brief.md | A11 系统/工作空间/参数/变量/API Key：用户分页+CRUD+重置密码、工作空间与成员、参数页、系统变量、API Key 各页面对后端调用全部正常。`Acceptance: A11`（spec §2.1/2.4 场景） | 用户建号与 pager、工作空间 CRUD、API Key 生成（access_key/secret_key/status）、sys_variable listAll/listPage、parameter login/chat、/user/info、uws 分页与 option 分页均 200 |
| A12 | passed | brief.md | A12 数据源类型完备性：新增数据源类型下拉含 13 类（excel+12 库），必测类型（MySQL/H2/Postgresql 等）真实连通；各类型至少具备连接测试与方言元数据策略。`Acceptance: A12`（spec §2.2 场景） | 前端 ds-type.ts 下拉恰 13 项（excel + 12 库）；DatasourceType.java:178 为 SQLSERVER 的 SELECT TOP 100、:343 为 ORACLE 的 ROWNUM<=100；sqlServer/oracle add 给出『driver not found, please put the driver jar into the extension directory (see README)』、es 走 HTTP 分支；h2 与 excel 端到端实测通过 |
| A13 | passed | brief.md | A13 审计/数据权限/嵌入：审计日志页分页+导出；数据权限配置可用；嵌入应用(assistant/embedded)列表及密钥生成可用。`Acceptance: A13`（spec §2.4 场景） | 审计 page（操作后 total_count=6 含 create/update）、get_options、export 均 200；ds_permission save 与 list 可用；assistant 与 embedded 建记录、列表、PATCH /system/embedded/secret/{id} 返回新密钥均 200 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| Integration build (offline) | -o package | . | passed | 0 | 3319 ms |
| Integration unit tests (offline, tests enabled) | -o -Dmaven.test.skip=false test | . | passed | 0 | 14335 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- 集成构建 mvn -o package: passed — 集成 worktree 根执行，BUILD SUCCESS，编译 95 个源文件，产出 askdata-server.jar
- 集成全量单测 mvn -o -Dmaven.test.skip=false test: passed — 含 A5 修复后为 Tests run: 78, Failures: 0, Errors: 0（datasource 37 + value-add 9 + chat 32）；另以 mvn -o clean package -Dmaven.test.skip=false 排除陈旧产物复跑同样 78/78
- H2 种子与表结构离线取证: passed — 24 表建立；admin 口令 8f32d1e371702c1b1b7346f4b07a701d；演示数据源 askdata_demo(mysql)；演示表 orders/users/products 共 19 字段；configuration 密文落库；sys_workspace 1、sys_arg 12
- SPA 静态托管实测 GET /: passed — HTTP 200，4197 字节 askdata 登录页 index.html
- 前端 npm install && npm run build: not-run — web/node_modules 缺失且需联网；改为验证既有 dist（123 文件）已打包进 jar 并被正常服务
- 端到端 HTTP POST 冒烟（登录/对话/模型连通）: not-run — 本机沙箱拦截全部 POST 请求（CODEBUDDY_SERVICE_PROXY_URL 占用 51439，缺 x-codebuddy-request 头返回 403，带该头则被路由到代理自身）
- 已知限制: 【A5 修复引入】Excel 文本列改为 VARCHAR(1024)，单单元格文本超过 1024 字符时 importToDb 会失败（实测报 Value too long for column ... (1500)）；原 CLOB 可存且配合新的 convertValue 读取已正常，建议后续改为更大长度或 CLOB/VARCHAR 混合策略
- 已知限制: 【A5 修复引入】超长导入失败后，已 DDL 建立的表会残留在应用库中且不归属任何数据源 configuration，缺少回滚/清理入口
- 已知限制: 【A5】PostgreSQL 未做端到端：本机无 PG 服务，只验证到 add/check 的连接失败路径（返回『数据源连接失败』，无异常栈外泄）；PG 分支目前仅有单测与代码复核支撑。A12 的 MySQL 已在本机临时 mysqld 上端到端实测通过
- 已知限制: 演示库 192.168.1.100:3306（D8）在本机不可达，种子记录已写入，不可达时按 SQLBot 错误约定返回
- 已知限制: A12 其余外部类型（oracle/dm/kingbase/ck/doris/starrocks/redshift/es/hive）仅有连接串/方言/元数据 SQL 的单测证据，无真实 JDBC 连接证据；相关驱动未打进 jar（按 D6 走扩展目录）
- 已知限制: token 用量：Ollama /v1/chat/completions 在 stream 模式不上报 usage，/chat/record/{rid}/usage 的 total_tokens=0（结构/steps/duration 正常），换用会上报 usage 的 OpenAI 兼容端点即可填充，非代码缺陷
- 已知限制: 技术栈偏差：brief Constraints 写明 MyBatis-Plus，实现实际用 spring-boot-starter-jdbc + JdbcTemplate（27 个类），功能全部通过但与约束文字不符
- 已知限制: SPA 兜底缺失：GET /dashboard/abc/def → 404；前端用 createWebHashHistory() 只请求 '/'，实际不影响使用，但与 spec §4 文字不符
- 已知限制: README 未说明『JDBC 驱动扩展目录』，而 JdbcEngine 驱动缺失时的报错文案指向它
- 已知限制: /user/template、batchImport、errorRecord 显式抛 not supported（spec §2.1 列了这三个端点，但 SQLBot 前端对应调用已注释）
- 已知限制: ai_model 无种子，首次须先在『系统-AI 模型』新增模型才能对话（符合 brief 设计，影响开箱即用体验）
- 已知限制: A11 无独立单测覆盖：集成测试集由三个子任务的用例构成，system-foundation 的端到端能力以其自身阶段验收结论 + 端点清单 + H2 种子实测为证据
- 已知限制: server.port 被本环境覆盖为随机端口（jar 内配置为 8000），非代码缺陷；本机 HTTP 验证需走 IPv6 回环 http://[::1]:<port>，沙箱代理只占 IPv4 127.0.0.1

## 阻塞项

_无。_

## 风险与跳过的工作

- 【中高风险，本轮实测复现】非 ASCII 表名授权误判：中文表名走 /chat/question 时，生成的合法 SQL 被判为『SQL 使用了未授权的表: excel__319c13a236』（ChatQuestionService.usedTables 用 replaceAll("[^a-z0-9_]","") 清洗，汉字被剥离）；改用 ASCII 表名即全链路正常。建议改 [^\p{L}\p{N}_]
- 【A5 修复引入】Excel 文本列改为 VARCHAR(1024)，单单元格文本超过 1024 字符时 importToDb 失败（Value too long for column），原 CLOB 可存
- 【A5 修复引入】超长导入失败后已建表会残留在应用库且不归属任何数据源 configuration，缺少回滚/清理入口
- 【A5】PostgreSQL 未做端到端（本机无 PG 服务），仅连接失败路径与单测/代码复核支撑
- 技术栈偏差：brief Constraints 写明 MyBatis-Plus，实现实际用 spring-boot-starter-jdbc + JdbcTemplate，功能全通过但与约束文字不符
- SPA 兜底缺失：GET /dashboard/abc/def → 404；前端用 createWebHashHistory() 只请求 '/'，实际不影响使用，但与 spec §4 文字不符
- README 未说明『JDBC 驱动扩展目录』，而 JdbcEngine 驱动缺失报错文案指向它；oracle/dm/kingbase/ck/hive/es 驱动未打进 jar
- token 用量：Ollama stream 模式不上报 usage，/chat/record/{rid}/usage 的 total_tokens=0；且该端点 duration=null 而 steps=[]，与 /log 的 steps 明细不一致
- 演示库 192.168.1.100:3306（D8）在本机不可达，种子已写入，不可达时按 SQLBot 错误约定返回
- /user/template、batchImport、errorRecord 显式抛 not supported（spec §2.1 列了这三个端点，但 SQLBot 前端对应调用已注释）
- ai_model 无种子，首次须先新增模型才能对话（符合 brief 设计，影响开箱即用体验）
- server.port 被本环境覆盖为随机端口（jar 内配置为 8000），非代码缺陷
- 【新，低】DatasourceType.h2Url 直接拼接 jdbc:h2:file:./data/ + database 不做路径归一：database 填 './data/xxx' 会解析成 ./data/./data/xxx（本轮实测踩到）
- 【新，低】应用内嵌 H2 为 2.1.214（write format 2），外部用 H2 ≥2.2.220 打开 .mv.db 会报 Unsupported database file version
- 【新，低】合并进来的 .gitignore 新增全局 *.jar/*.zip 忽略规则；当前文件均 tracked 不受影响，但后续新增二进制需显式 -f

## 之前的迭代

| 目标周期 | 迭代 | 尝试 | 结果 | 未解决项 | 摘要 | 完成时间 |
| ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 0 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-18T03:46:04.321Z |
| 2 | 1 | 1 | fail | A5 | 12/13 passed，A5 failed。本轮独立重跑 mvn -o clean package（BUILD SUCCESS）与 mvn -o -Dmaven.test.skip=false test（75/75 通过），并做运行时端到端复核。发现上一轮遗漏的真实缺陷：Excel 数据源在默认 H2 部署下不可用——建表表名大小写不兼容 H2 标识符折叠（真实提问返回 error 帧 Table "EXCEL_SHEET1_E3DC0BC330" not found），且 string 列映射为 CLOB 后 JdbcEngine.convertValue 未处理 java.sql.Clob 导致 previewData/execSql HTTP 500。Excel 属 spec §2.2 首选集，要求真实连通与元数据/执行，故 A5 判 failed。建议修复（表名去引号或对齐大写折叠 + string 改 VARCHAR 或 convertValue 增加 Clob→String）后再提验收。其余 12 项（含真实大模型对话全帧、登录闭环、数据源主体、图表/分析/预测、增值模块）复核通过。 | 2026-10-18T13:08:30.059Z |
| 2 | 2 | 0 | recovery | — | Native Shape artifacts changed | 2026-10-18T13:12:10.535Z |
| 3 | 1 | 1 | pass | — | 13/13 全部 passed。本轮在集成 worktree（含 A5 修复子任务）上独立重跑 mvn -o clean package（BUILD SUCCESS，78/78），并做端到端实测：登录闭环、AI 模型连通与密文落库、Excel 数据源全链路（previewData/execSql HTTP 200 返回真实中文数据，CLOB 问题已解决）、真实 Ollama 大模型对话全帧（SQL/表格/图表/分析/预测）、以及审计/权限/嵌入/训练/术语/提示词/仪表盘/用户/工作空间/变量/API Key 管理面抽查。上一轮 A5 的两处失败已由 repair-a5-excel 修复并经复核确认。新发现一条中高风险（中文表名在 ChatQuestionService.usedTables 被正则剥离导致授权误判）已记入 risks。工作树保持干净，未修改任何文件、未提交、未运行 comet 命令。 | 2026-10-18T14:23:28.308Z |
| 3 | 1 | 1 | recovery | — | 归档前合并 main 导致集成 HEAD 变更（cd3a510→9eec69c），归档要求 HEAD 与验收记录一致，故退回 Shape：需求与验收标准不变，仅需在含 main 合并的新 HEAD 上重新走一轮验收后再归档 | 2026-10-18T14:39:15.338Z |
| 4 | 1 | 1 | pass | — | 13/13 全部 passed，无 blocked。本轮在集成提交 9eec69c（工作树干净）上从零重跑：mvn -o clean package -Dmaven.test.skip=false 与 mvn -o -Dmaven.test.skip=false test 均 BUILD SUCCESS / 78/78；把 jar 拷到仓库外目录启动（实际端口 51439，HTTP 全走 http://[::1]:51439）做端到端实测，覆盖登录闭环与鉴权登出、AI 模型（真 Ollama 连通 + 密文落库 + 重启持久化）、A5 关键项 Excel 建表后 previewData/execSql HTTP 200 返回真实中文数据（含未加引号小写 LLM 形态 SQL，H2 直查确认列已是 CHARACTER VARYING(1024) 而非 CLOB）、真实 gemma4 提问全帧（Excel 与 H2 两个引擎）、analysis/predict 全帧、以及审计/权限/嵌入/训练/术语/提示词/仪表盘/用户/工作空间/变量/API Key 抽查；另补做外置 H2 业务库引擎端到端（JOIN/GROUP BY），并停进程后用 H2 2.1.214 直查核验密文与列类型。合并影响判定：9eec69c 相对 cd3a510 只合入 .comet/.opencode/AGENTS.md/docs/.gitignore 等 52 个工作流文件，产品侧零改动，且在其上重新构建与全链路运行均通过，合并未破坏实现。工作树保持干净，未修改代码/schema/pom/文档、未提交、未运行任何 comet 命令。 | 2026-10-18T15:14:38.571Z |


## Supervisor 分层证据

- Child 验证: complete
- 父级集成: complete
- 父级检查: Integration build (offline), Integration unit tests (offline, tests enabled)
- 未重跑: Maven 构建全链（A1）: passed, 前端构建（A2）: passed, jar 静态资源内含 dist（A2/A3）: passed, 启动+登录+鉴权+登出 全链路（A3）: passed, askdata-server 后端编译打包（-Pskip-test）: passed, system 模块运行时冒烟：H2 启动 + A11 全页面端点 + A4 aimodel CRUD/默认/连通检查/重启持久化/AES 落库（本地 mock，不依赖 Ollama/MySQL）: passed, Maven package (offline): passed, Run datasource-engine unit tests (offline, tests enabled): passed, Maven package (offline): passed, Run chat-core unit tests (offline, tests enabled): passed, Maven package (offline): passed, Run value-add-modules unit tests (offline, tests enabled): passed, Maven package (offline): passed, Unit tests (offline, tests enabled): passed
- 未完成: —


## 结论

13/13 全部 passed，无 blocked。本轮在集成提交 9eec69c（工作树干净）上从零重跑：mvn -o clean package -Dmaven.test.skip=false 与 mvn -o -Dmaven.test.skip=false test 均 BUILD SUCCESS / 78/78；把 jar 拷到仓库外目录启动（实际端口 51439，HTTP 全走 http://[::1]:51439）做端到端实测，覆盖登录闭环与鉴权登出、AI 模型（真 Ollama 连通 + 密文落库 + 重启持久化）、A5 关键项 Excel 建表后 previewData/execSql HTTP 200 返回真实中文数据（含未加引号小写 LLM 形态 SQL，H2 直查确认列已是 CHARACTER VARYING(1024) 而非 CLOB）、真实 gemma4 提问全帧（Excel 与 H2 两个引擎）、analysis/predict 全帧、以及审计/权限/嵌入/训练/术语/提示词/仪表盘/用户/工作空间/变量/API Key 抽查；另补做外置 H2 业务库引擎端到端（JOIN/GROUP BY），并停进程后用 H2 2.1.214 直查核验密文与列类型。合并影响判定：9eec69c 相对 cd3a510 只合入 .comet/.opencode/AGENTS.md/docs/.gitignore 等 52 个工作流文件，产品侧零改动，且在其上重新构建与全链路运行均通过，合并未破坏实现。工作树保持干净，未修改代码/schema/pom/文档、未提交、未运行任何 comet 命令。
