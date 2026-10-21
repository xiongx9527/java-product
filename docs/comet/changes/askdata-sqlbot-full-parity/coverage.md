# SQLBot 端点覆盖表（A1）

> 本文件是 askdata-sqlbot-full-parity change 的 A1 验收产物：逐条比对 SQLBot 开源社区版后端
> `backend/apps/**` 全部 router 端点与 askdata（Java/Spring Boot）已实现路由，给出状态与证据。

## 汇总

| 指标 | 数值 |
| --- | --- |
| SQLBot 端点总数（活跃路由，已剔除注释/文档字符串内的死代码） | **144** |
| covered（askdata 有等价路由，信封一致） | **140** |
| partial（部分等价） | **0** |
| missing（能力缺失，见"已知缺口"） | **4** |
| n-a（按 brief"明确不建"有意不建） | **0**（说明见下） |

补充：askdata 另有 **48 条** SQLBot 开源版不存在的路由（xpack 等价能力与 askdata 扩展，见文末附录 B），
本表不计入分子分母。

## 方法论说明

- **事实源**：SQLBot 开源社区版基线 commit `fc378c37`（路径 `/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot`，
  与 brief 指定一致；另一份 `/Users/xiongxiao/Workspaces/Soft/docker/sqlbot` 为不同版本，仅作交叉参考，未采用）。
- **枚举方式**：解析 `backend/apps/api.py` 的 17 个 include_router 模块，提取每个模块的
  `APIRouter(prefix=...)` 与全部 `@router.{get,post,put,delete,patch}(...)` 装饰器（单/双引号均覆盖），
  全局前缀为 `settings.API_V1_STR = CONTEXT_PATH + "/api/v1"`（CONTEXT_PATH 缺省为空，故为 `/api/v1`）。
  文档字符串（`""" ... """`）与 `#` 注释内的死代码路由不计入（如 chat.py 的旧版
  `/record/{id}/data`、datasource.py 的 `/execSql/{id}` 与 `/editLocalComment`、
  assistant.py 的 `/validator`）。`apps/` 下无其他活跃 router（audit router 在 api.py 中被注释且
  `apps/audit` 不存在；swagger/template/db/ai_model 无 router）。
- **askdata 侧枚举**：`askdata/askdata-server/src/main/java/**/*Controller.java` 全部 27 个
  `@RestController` 的类级 `@RequestMapping("api/v1")` + 方法级映射注解。
- **状态判定**：
  - covered：SQLBot 端点与 askdata 路由一一对应（路径形状一致，路径参数名可不同），且响应信封语义一致；
  - partial：能力部分等价（本表无）；
  - missing：SQLBot 能力在 askdata 无等价实现；
  - n-a：brief"明确不建"条目。本表中唯一涉及"明确不建"的是 SQLBot `/mcp/*` 7 个 REST 端点的
    **协议形态**（沿用 askdata-sqlbot-parity D2 决策：能力等价，askdata 用框架 MCP http-sse 承载，
    不照抄 REST 形态）——但其**能力**按 covered/missing 逐条判定，不因形态差异记为 n-a。
- **信封核验**：见"响应信封一致性"一节。
- **程序化复核**：除人工核对外，另以脚本对两侧做 (METHOD, 归一化路径) 集合比对（路径参数统一归一为
  `{}`）：SQLBot 144 条中 137 条与 askdata 路由逐一精确匹配（全部非 MCP 模块），7 条 MCP 端点
  按能力口径判定（3 条能力等价、4 条缺失）；askdata 185 条路由中 48 条为 SQLBot 开源版不存在的
  扩展（附录 B）。本表 covered/partial/missing 计数即源于该比对。
- **日期**：2026-10-21。

## 逐模块覆盖表

### 1. chat（`apps/chat/api/chat.py`，prefix `/chat`，17 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /chat/list | GET /api/v1/chat/list | covered | ChatController.list() |
| GET /chat/{chart_id} | GET /api/v1/chat/{id} | covered | ChatController.get() |
| GET /chat/{chart_id}/with_data | GET /api/v1/chat/{id}/with_data | covered | ChatController.getWithData() |
| GET /chat/record/{chat_record_id}/data | GET /api/v1/chat/record/{rid}/data | covered | ChatController.recordData() |
| GET /chat/record/{chat_record_id}/data_live | GET /api/v1/chat/record/{rid}/data_live | covered | ChatController.recordDataLive() |
| GET /chat/record/{chat_record_id}/predict_data | GET /api/v1/chat/record/{rid}/predict_data | covered | ChatController.recordPredictData() |
| GET /chat/record/{chat_record_id}/log | GET /api/v1/chat/record/{rid}/log | covered | ChatController.recordLog() |
| GET /chat/record/{chat_record_id}/usage | GET /api/v1/chat/record/{rid}/usage | covered | ChatController.recordUsage() |
| POST /chat/rename | POST /api/v1/chat/rename | covered | ChatController.rename() |
| DELETE /chat/{chart_id} | DELETE /api/v1/chat/{id} | covered | ChatController.delete() |
| POST /chat/start | POST /api/v1/chat/start | covered | ChatController.start() |
| POST /chat/assistant/start | POST /api/v1/chat/assistant/start | covered | ChatController.assistantStart() |
| POST /chat/recommend_questions/{chat_record_id} | POST /api/v1/chat/recommend_questions/{rid} | covered | ChatController.recommendQuestions() |
| GET /chat/recent_questions/{datasource_id} | GET /api/v1/chat/recent_questions/{ds} | covered | ChatController.recentQuestions() |
| POST /chat/question | POST /api/v1/chat/question | covered | ChatController.question()（SSE 流式，直写 text/event-stream） |
| POST /chat/record/{chat_record_id}/{action_type} | POST /api/v1/chat/record/{rid}/{action_type} | covered | ChatController.analysisOrPredict()（analysis/predict） |
| GET /chat/record/{chat_record_id}/excel/export/{chat_id} | GET /api/v1/chat/record/{rid}/excel/export/{chat_id} | covered | ChatController.exportExcel()（附件下载，不进信封） |

小计：17/17 covered。

### 2. dashboard（`apps/dashboard/api/dashboard_api.py`，prefix `/dashboard`，8 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /dashboard/list_resource | POST /api/v1/dashboard/list_resource | covered | DashboardController.listResource() |
| POST /dashboard/load_resource | POST /api/v1/dashboard/load_resource | covered | DashboardController.loadResource() |
| POST /dashboard/create_resource | POST /api/v1/dashboard/create_resource | covered | DashboardController.createResource() |
| POST /dashboard/update_resource | POST /api/v1/dashboard/update_resource | covered | DashboardController.updateResource() |
| DELETE /dashboard/delete_resource/{resource_id}/{name} | DELETE /api/v1/dashboard/delete_resource/{resource_id}/{name} | covered | DashboardController.deleteResource() |
| POST /dashboard/create_canvas | POST /api/v1/dashboard/create_canvas | covered | DashboardController.createCanvas() |
| POST /dashboard/update_canvas | POST /api/v1/dashboard/update_canvas | covered | DashboardController.updateCanvas() |
| POST /dashboard/check_name | POST /api/v1/dashboard/check_name | covered | DashboardController.checkName() |

小计：8/8 covered。

### 3. data_training（`apps/data_training/api/data_training.py`，prefix `/system/data-training`，7 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/data-training/page/{current_page}/{page_size} | GET /api/v1/system/data-training/page/{current_page}/{page_size} | covered | TrainingController.pager() |
| PUT /system/data-training | PUT /api/v1/system/data-training | covered | TrainingController.createOrUpdate() |
| DELETE /system/data-training | DELETE /api/v1/system/data-training | covered | TrainingController.delete() |
| GET /system/data-training/{id}/enable/{enabled} | GET /api/v1/system/data-training/{id}/enable/{enabled} | covered | TrainingController.enable() |
| GET /system/data-training/export | GET /api/v1/system/data-training/export | covered | TrainingController.export()（附件下载） |
| GET /system/data-training/template | GET /api/v1/system/data-training/template | covered | TrainingController.template()（附件下载） |
| POST /system/data-training/uploadExcel | POST /api/v1/system/data-training/uploadExcel | covered | TrainingController.uploadExcel() |

小计：7/7 covered。

### 4. datasource（`apps/datasource/api/datasource.py`，prefix `/datasource`，25 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /datasource/ws/{oid} | GET /api/v1/datasource/ws/{oid} | covered | DatasourceController.ws()（按工作空间列数据源，非 WebSocket，与 SQLBot 同为普通 GET） |
| GET /datasource/list | GET /api/v1/datasource/list | covered | DatasourceController.list() |
| POST /datasource/get/{id} | POST /api/v1/datasource/get/{id} | covered | DatasourceController.get() |
| POST /datasource/check | POST /api/v1/datasource/check | covered | DatasourceController.check() |
| GET /datasource/check/{ds_id} | GET /api/v1/datasource/check/{ds_id} | covered | DatasourceController.checkById() |
| POST /datasource/add | POST /api/v1/datasource/add | covered | DatasourceController.add() |
| POST /datasource/chooseTables/{id} | POST /api/v1/datasource/chooseTables/{id} | covered | DatasourceController.chooseTables() |
| POST /datasource/update | POST /api/v1/datasource/update | covered | DatasourceController.update() |
| POST /datasource/delete/{id}/{name} | POST /api/v1/datasource/delete/{id}/{name} | covered | DatasourceController.delete() |
| POST /datasource/getTables/{id} | POST /api/v1/datasource/getTables/{id} | covered | DatasourceController.getTables() |
| POST /datasource/getTablesByConf | POST /api/v1/datasource/getTablesByConf | covered | DatasourceController.getTablesByConf() |
| POST /datasource/getSchemaByConf | POST /api/v1/datasource/getSchemaByConf | covered | DatasourceController.getSchemaByConf() |
| POST /datasource/getFields/{id}/{table_name} | POST /api/v1/datasource/getFields/{id}/{table_name} | covered | DatasourceController.getFields() |
| POST /datasource/syncFields/{ds_id}/{id} | POST /api/v1/datasource/syncFields/{ds_id}/{id} | covered | DatasourceController.syncFields() |
| POST /datasource/tableList/{id} | POST /api/v1/datasource/tableList/{id} | covered | DatasourceController.tableList() |
| POST /datasource/fieldList/{ds_id}/{id} | POST /api/v1/datasource/fieldList/{ds_id}/{id} | covered | DatasourceController.fieldList() |
| POST /datasource/editTable | POST /api/v1/datasource/editTable | covered | DatasourceController.editTable() |
| POST /datasource/editField | POST /api/v1/datasource/editField | covered | DatasourceController.editField() |
| POST /datasource/previewData/{id} | POST /api/v1/datasource/previewData/{id} | covered | DatasourceController.previewData() |
| POST /datasource/fieldEnum/{ds_id}/{id} | POST /api/v1/datasource/fieldEnum/{ds_id}/{id} | covered | DatasourceController.fieldEnum() |
| POST /datasource/uploadExcel | POST /api/v1/datasource/uploadExcel | covered | DatasourceController.uploadExcel() |
| GET /datasource/exportDsSchema/{id} | GET /api/v1/datasource/exportDsSchema/{id} | covered | DatasourceController.exportDsSchema()（附件下载） |
| POST /datasource/uploadDsSchema/{id} | POST /api/v1/datasource/uploadDsSchema/{id} | covered | DatasourceController.uploadDsSchema() |
| POST /datasource/parseExcel | POST /api/v1/datasource/parseExcel | covered | DatasourceController.parseExcel() |
| POST /datasource/importToDb | POST /api/v1/datasource/importToDb | covered | DatasourceController.importToDb() |

小计：25/25 covered。

### 5. recommended_problem（`apps/datasource/api/recommended_problem.py`，prefix `/recommended_problem`，3 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /recommended_problem/get_datasource_recommended/{ds_id} | GET /api/v1/recommended_problem/get_datasource_recommended/{ds_id} | covered | RecommendedProblemController.list() |
| GET /recommended_problem/get_datasource_recommended_base/{ds_id} | GET /api/v1/recommended_problem/get_datasource_recommended_base/{ds_id} | covered | RecommendedProblemController.base() |
| POST /recommended_problem/save_recommended_problem | POST /api/v1/recommended_problem/save_recommended_problem | covered | RecommendedProblemController.save() |

小计：3/3 covered。

### 6. table_relation（`apps/datasource/api/table_relation.py`，prefix `/table_relation`，2 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /table_relation/save/{ds_id} | POST /api/v1/table_relation/save/{ds_id} | covered | TableRelationController.save() |
| POST /table_relation/get/{ds_id} | POST /api/v1/table_relation/get/{ds_id} | covered | TableRelationController.get() |

小计：2/2 covered。

### 7. mcp（`apps/mcp/mcp.py`，prefix `/mcp`，7 条）

SQLBot 的 7 条是手写 REST 形态的 MCP 工具端点。按 brief"明确不建"（沿用 askdata-sqlbot-parity D2），
askdata 不照抄 REST 形态，而以框架 MCP（fw-plus-ai-mcp-server，http-sse 传输，`/mcp` 端点）承载
**等价工具能力**。下表按能力判定：

| SQLBot 端点 | askdata 能力 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /mcp/mcp_ds_list | MCP tool `datasource_list` | covered（能力等价） | AskdataMcpTools.datasource_list()；application.yaml `fw-plus.ai.mcp.transport=http-sse` |
| POST /mcp/mcp_question | MCP tool `question_answer` | covered（能力等价） | AskdataMcpTools.question_answer() |
| POST /mcp/mcp_assistant | MCP tool `assistant` | covered（能力等价） | AskdataMcpTools.assistant() |
| POST /mcp/access_token | 无等价 MCP 工具 | missing | 账号口令换 token 的 MCP 通道能力缺失；askdata MCP 鉴权走 sa-token-login 会话复用，无独立 token 交换工具 |
| POST /mcp/mcp_start | 无等价 MCP 工具 | missing | 建会话并返回 chat_id 的 MCP 工具缺失（assistant 工具有多轮记忆但不返回 chat_id） |
| POST /mcp/mcp_ws_list | 无等价 MCP 工具 | missing | 工作空间列表的 MCP 工具缺失（REST `/user/ws` 有等价能力但非 MCP 工具） |
| POST /mcp/mcp_model_list | 无等价 MCP 工具 | missing | 模型列表的 MCP 工具缺失（REST `/system/aimodel/list/by_ws` 有等价能力但非 MCP 工具） |

小计：3 covered（能力等价）/ 4 missing。缺口原因与归属见"已知缺口"。

### 8. settings（`apps/settings/api/base.py`，prefix `/system`，1 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /system/download-fail-info | POST /api/v1/system/download-fail-info | covered | SettingsFileController.downloadFailInfo() |

小计：1/1 covered。

### 9. aimodel（`apps/system/api/aimodel.py`，prefix `/system/aimodel`，13 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /system/aimodel/status | POST /api/v1/system/aimodel/status | covered | AiModelController.status() |
| GET /system/aimodel/default | GET /api/v1/system/aimodel/default | covered | AiModelController.checkDefault() |
| PUT /system/aimodel/default/{id} | PUT /api/v1/system/aimodel/default/{id} | covered | AiModelController.setDefault() |
| GET /system/aimodel | GET /api/v1/system/aimodel | covered | AiModelController.list() |
| GET /system/aimodel/{id} | GET /api/v1/system/aimodel/{id} | covered | AiModelController.get() |
| POST /system/aimodel | POST /api/v1/system/aimodel | covered | AiModelController.create() |
| PUT /system/aimodel | PUT /api/v1/system/aimodel | covered | AiModelController.update() |
| DELETE /system/aimodel/{id} | DELETE /api/v1/system/aimodel/{id} | covered | AiModelController.delete() |
| GET /system/aimodel/{id}/ws_mapping | GET /api/v1/system/aimodel/{id}/ws_mapping | covered | AiModelController.wsMapping() |
| PUT /system/aimodel/{id}/ws_mapping | PUT /api/v1/system/aimodel/{id}/ws_mapping | covered | AiModelController.replaceWsMapping() |
| POST /system/aimodel/{id}/ws_mapping | POST /api/v1/system/aimodel/{id}/ws_mapping | covered | AiModelController.addWsMapping() |
| DELETE /system/aimodel/{id}/ws_mapping | DELETE /api/v1/system/aimodel/{id}/ws_mapping | covered | AiModelController.deleteWsMapping() |
| GET /system/aimodel/list/by_ws | GET /api/v1/system/aimodel/list/by_ws | covered | AiModelController.listByWs() |

小计：13/13 covered。

### 10. apikey（`apps/system/api/apikey.py`，prefix `/system/apikey`，4 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/apikey | GET /api/v1/system/apikey | covered | ApiKeyController.list() |
| POST /system/apikey | POST /api/v1/system/apikey | covered | ApiKeyController.create() |
| PUT /system/apikey/status | PUT /api/v1/system/apikey/status | covered | ApiKeyController.status() |
| DELETE /system/apikey/{id} | DELETE /api/v1/system/apikey/{id} | covered | ApiKeyController.delete() |

小计：4/4 covered。

### 11. assistant（`apps/system/api/assistant.py`，prefix `/system/assistant`，11 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/assistant/info/{id} | GET /api/v1/system/assistant/info/{id} | covered | AssistantPublicController.info()（公共路径，嵌入侧免鉴权） |
| GET /system/assistant/app/{appId} | GET /api/v1/system/assistant/app/{appId} | covered | AssistantPublicController.app()（公共路径） |
| GET /system/assistant/picture/{file_id} | GET /api/v1/system/assistant/picture/{file_id} | covered | AssistantController.picture()（图片字节流，公共路径） |
| PATCH /system/assistant/ui | PATCH /api/v1/system/assistant/ui | covered | AssistantController.ui() |
| GET /system/assistant/validate/{id} | GET /api/v1/system/assistant/validate/{id} | covered | AssistantPublicController.validate()（公共路径） |
| GET /system/assistant/ds | GET /api/v1/system/assistant/ds | covered | AssistantController.datasources() |
| GET /system/assistant | GET /api/v1/system/assistant | covered | AssistantController.list() |
| GET /system/assistant/advanced_application | GET /api/v1/system/assistant/advanced_application | covered | AssistantController.advancedApplication() |
| POST /system/assistant | POST /api/v1/system/assistant | covered | AssistantController.create() |
| PUT /system/assistant | PUT /api/v1/system/assistant | covered | AssistantController.update() |
| DELETE /system/assistant/{id} | DELETE /api/v1/system/assistant/{id} | covered | AssistantController.delete() |

小计：11/11 covered。

### 12. login（`apps/system/api/login.py`，prefix `/login`，3 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /login/access-token | POST /api/v1/login/access-token | covered | LoginController.accessToken() |
| POST /login/change-pwd | POST /api/v1/login/change-pwd | covered | LoginController.changePwd() |
| POST /login/logout | POST /api/v1/login/logout | covered | LoginController.logout() |

小计：3/3 covered。

### 13. parameter（`apps/system/api/parameter.py`，prefix `/system/parameter`，4 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/parameter/login | GET /api/v1/system/parameter/login | covered | ParameterController.login() |
| GET /system/parameter | GET /api/v1/system/parameter | covered | ParameterController.list() |
| POST /system/parameter | POST /api/v1/system/parameter | covered | ParameterController.save() |
| GET /system/parameter/chat | GET /api/v1/system/parameter/chat | covered | ParameterController.chat() |

小计：4/4 covered。

### 14. user（`apps/system/api/user.py`，prefix `/user`，17 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /user/template | GET /api/v1/user/template | covered | UserController.template()（附件下载） |
| POST /user/batchImport | POST /api/v1/user/batchImport | covered | UserController.batchImport() |
| GET /user/errorRecord/{file_id} | GET /api/v1/user/errorRecord/{fileId} | covered | UserController.errorRecord()（附件下载） |
| GET /user/info | GET /api/v1/user/info | covered | UserController.info() |
| GET /user/defaultPwd | GET /api/v1/user/defaultPwd | covered | UserController.defaultPwd() |
| GET /user/pager/{pageNum}/{pageSize} | GET /api/v1/user/pager/{pageNum}/{pageSize} | covered | UserController.pager() |
| GET /user/ws | GET /api/v1/user/ws | covered | UserController.wsOptions() |
| PUT /user/ws/{oid} | PUT /api/v1/user/ws/{oid} | covered | UserController.switchWorkspace() |
| GET /user/{id} | GET /api/v1/user/{id} | covered | UserController.get() |
| POST /user | POST /api/v1/user | covered | UserController.create() |
| PUT /user | PUT /api/v1/user | covered | UserController.update() |
| DELETE /user/{id} | DELETE /api/v1/user/{id} | covered | UserController.delete() |
| DELETE /user | DELETE /api/v1/user | covered | UserController.deleteBatch() |
| PUT /user/language | PUT /api/v1/user/language | covered | UserController.changeLanguage() |
| PATCH /user/pwd/{id} | PATCH /api/v1/user/pwd/{id} | covered | UserController.resetPassword() |
| PUT /user/pwd | PUT /api/v1/user/pwd | covered | UserController.changeOwnPassword() |
| PATCH /user/status | PATCH /api/v1/user/status | covered | UserController.changeStatus() |

小计：17/17 covered。

### 15. sys_variable（`apps/system/api/variable_api.py`，prefix `/sys_variable`，4 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| POST /sys_variable/save | POST /api/v1/sys_variable/save | covered | VariableController.save() |
| POST /sys_variable/delete | POST /api/v1/sys_variable/delete | covered | VariableController.delete() |
| POST /sys_variable/listAll | POST /api/v1/sys_variable/listAll | covered | VariableController.listAll() |
| POST /sys_variable/listPage/{pageNum}/{pageSize} | POST /api/v1/sys_variable/listPage/{pageNum}/{pageSize} | covered | VariableController.listPage() |

小计：4/4 covered。

### 16. workspace（`apps/system/api/workspace.py`，prefix `/system/workspace`，11 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/workspace/uws/option/pager/{pageNum}/{pageSize} | GET /api/v1/system/workspace/uws/option/pager/{pageNum}/{pageSize} | covered | WorkspaceController.optionPager() |
| GET /system/workspace/uws/option | GET /api/v1/system/workspace/uws/option | covered | WorkspaceController.optionUser() |
| GET /system/workspace/uws/pager/{pageNum}/{pageSize} | GET /api/v1/system/workspace/uws/pager/{pageNum}/{pageSize} | covered | WorkspaceController.memberPager() |
| POST /system/workspace/uws | POST /api/v1/system/workspace/uws | covered | WorkspaceController.uwsCreate() |
| PUT /system/workspace/uws | PUT /api/v1/system/workspace/uws | covered | WorkspaceController.uwsUpdate() |
| DELETE /system/workspace/uws | DELETE /api/v1/system/workspace/uws | covered | WorkspaceController.uwsDelete() |
| GET /system/workspace | GET /api/v1/system/workspace | covered | WorkspaceController.list() |
| POST /system/workspace | POST /api/v1/system/workspace | covered | WorkspaceController.create() |
| PUT /system/workspace | PUT /api/v1/system/workspace | covered | WorkspaceController.update() |
| GET /system/workspace/{id} | GET /api/v1/system/workspace/{id} | covered | WorkspaceController.get() |
| DELETE /system/workspace/{id} | DELETE /api/v1/system/workspace/{id} | covered | WorkspaceController.delete() |

小计：11/11 covered。

### 17. terminology（`apps/terminology/api/terminology.py`，prefix `/system/terminology`，7 条）

| SQLBot 端点 | askdata 端点 | 状态 | 证据 |
| --- | --- | --- | --- |
| GET /system/terminology/page/{current_page}/{page_size} | GET /api/v1/system/terminology/page/{current_page}/{page_size} | covered | TerminologyController.pager() |
| PUT /system/terminology | PUT /api/v1/system/terminology | covered | TerminologyController.createOrUpdate() |
| DELETE /system/terminology | DELETE /api/v1/system/terminology | covered | TerminologyController.delete() |
| GET /system/terminology/{id}/enable/{enabled} | GET /api/v1/system/terminology/{id}/enable/{enabled} | covered | TerminologyController.enable() |
| GET /system/terminology/export | GET /api/v1/system/terminology/export | covered | TerminologyController.export()（附件下载） |
| GET /system/terminology/template | GET /api/v1/system/terminology/template | covered | TerminologyController.template()（附件下载） |
| POST /system/terminology/uploadExcel | POST /api/v1/system/terminology/uploadExcel | covered | TerminologyController.uploadExcel() |

小计：7/7 covered。

## 响应信封一致性

- **契约**：askdata 非流式 JSON 响应统一为 `{"code":0,"data":<payload>,"msg":null}`
  （`common/web/Result.java`，`Result.ok()`），与 SQLBot `common/core/response_middleware.py`
  的 ResponseMiddleware 包装契约逐字一致；SQLBot 前端 `request.ts` 按 `code===0` 取 `data`，
  兼容。
- **全局兜底**：`common/web/ResponseEnvelopeAdvice.java`（`ResponseBodyAdvice`）对所有 JSON
  响应补信封；已是 `{code,data,msg}` 结构不二次包装——与 SQLBot 中间件"已包装则透传"语义一致。
  27 个 Controller 的方法返回值扫描结果：JSON 端点全部返回 `Result`/`PageResult`（或由兜底切面
  包装），**未发现信封不一致的端点**。
- **有意不进信封的响应**（与 SQLBot 语义一致，不算不一致）：
  1. 流式端点：`POST /chat/question`、`POST /chat/record/{rid}/{action_type}`、
     `POST /chat/recommend_questions/{rid}` 直写 `text/event-stream`（对应 SQLBot
     `StreamingResponse(media_type="text/event-stream")`，ResponseMiddleware 直接透传）；
  2. 附件下载：template/export/errorRecord/picture 等设置 `Content-Disposition: attachment`
     的响应（对应 SQLBot FileResponse，非 application/json 不透传包装）。

## 已知缺口（missing，4 条）

均为 MCP 工具能力缺口（REST 形态按 brief"明确不建"有意不照抄，此处按能力判定）：

1. `/mcp/access_token`——账号口令换 token。askdata MCP 鉴权为 sa-token-login 会话复用，
   无等价工具。brief 计划由 `AskdataMcpTools` 补齐（本集成快照未合入）。
2. `/mcp/mcp_start`——建会话返回 chat_id。现有 `assistant` 工具有多轮记忆但不暴露 chat_id。
   brief 计划补齐（本集成快照未合入）。
3. `/mcp/mcp_ws_list`——工作空间列表。REST `/user/ws` 有等价能力，缺 MCP 工具形态。
   brief 计划补齐（本集成快照未合入）。
4. `/mcp/mcp_model_list`——模型列表。REST `/system/aimodel/list/by_ws` 有等价能力，缺 MCP
   工具形态。brief 未列入"补齐 3 个工具"计划，属完整缺口。

说明：本表基于 integration-regression 分支 HEAD（9006cfc）的实际代码状态如实标注；
上述缺口的补齐归属由 change 的其他 child 负责，合入后本表 covered 计数将上升。

## 附录 A：SQLBot 侧剔除的死代码路由（不计入 144）

- `GET /chat/record/{chat_record_id}/data`（chat.py:57，文档字符串内的旧版，被 :75 活跃版取代）
- `GET /chat/record/{chat_record_id}/predict_data`（chat.py:66，同上，被 :93 取代）
- `POST /datasource/execSql/{id}`（datasource.py:182，文档字符串注释）
- `POST /datasource/editLocalComment`（datasource.py:212，# 注释）
- `POST /datasource/uploadExcel`（datasource.py:258，# 注释；:323 为活跃版）
- `GET /system/assistant/validator`（assistant.py:144，文档字符串注释）
- `GET /system/assistant/{id}`（assistant.py:363，文档字符串注释）
- `audit.api.audit_api`（api.py 注释掉的 include，且 `apps/audit` 在开源版不存在）

注：askdata 侧反而实现了 SQLBot 已注释的 `POST /api/v1/datasource/execSql/{id}`
（DatasourceController.execSql()），属 askdata 扩展。

## 附录 B：askdata 侧扩展路由（SQLBot 开源版不存在，共 48 条，不计入覆盖率）

这些路由对应 SQLBot **xpack 闭源商业能力**（brief"明确不建"其本体，但 askdata 已有等价数据
结构与实现）或 askdata 自身扩展：

| 模块 | 路由数 | 说明 |
| --- | --- | --- |
| embedded（EmbeddedController） | 6 | xpack 嵌入助手管理（分页/编辑器/CRUD/重置 secret） |
| authentication（AuthenticationController） | 10 | xpack SSO 认证源（CAS/OAuth2/LDAP 配置、状态、登录） |
| custom_prompt（CustomPromptController） | 7 | xpack 自定义提示词 CRUD/导入导出 |
| audit（AuditController） | 3 | xpack 审计日志查询/导出 |
| platform（PlatformController） | 3 | xpack 企业平台用户同步 |
| appearance（AppearanceController） | 2 | xpack 外观定制（ui 键值/图片） |
| ds_permission（DsPermissionController） | 3 | 行/列权限配置（xpack UI 等价，本 change 补运行时强制） |
| settings/terminology（SettingsTerminologyController） | 5 | 设置页术语管理（`/system/terminology` 之外的既有路由） |
| license（SkeletonStubController） | 2 | License 占位端点 |
| system/config/key（ConfigKeyController） | 1 | SQLBot 白名单内公共端点，askdata 原缺失，本 change 补齐 |
| chat/question/pager（ChatController） | 1 | 会话分页（askdata 既有路由） |
| dashboard/move_resource（DashboardController） | 1 | askdata 既有扩展 |
| datasource/execSql（DatasourceController） | 1 | SQLBot 已注释，askdata 保留 |
| login/health（LoginController） | 1 | 健康检查扩展 |
| terminology/{id}、data-training/{id}（GET 编辑器） | 2 | askdata 既有扩展 |

合计 48 条。这些路由同样遵循 `{code,data,msg}` 信封。
