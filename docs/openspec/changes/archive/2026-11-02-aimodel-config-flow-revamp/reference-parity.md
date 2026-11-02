# 参考实现功能对等基线（SQLBot）

参考代码：`/Users/xiongxiao/Workspace/Idea/codes/SQLBot`

本文件是硬性验收基线：改造后，下列 SQLBot 行为必须全部保留或按「有意偏离」明确变更理由，不允许以「本期不做」「实现成本高」为由删减。

## 1. 后端接口面

| # | SQLBot 行为 | 出处 | 本次处置 |
| --- | --- | --- | --- |
| B1 | `POST /system/aimodel/status` NDJSON 流式校验，逐块输出 `{"content": ...}`，异常输出 `{"error": ...}` | `aimodel.py:24-51` | 保持；新增按类型走 embeddings |
| B2 | `GET /system/aimodel/default` 无默认模型时报错 | `aimodel.py:54-60` | 保持；语义改为「无可用 LLM」 |
| B3 | `PUT /system/aimodel/default/{id}` 已默认则直接返回；否则全表清空后置位 | `aimodel.py:63-83` | 增强：按类型清空，且校验可用+启用 |
| B4 | `GET /system/aimodel` 列表含 `ws_mapping_count` 子查询、`keyword` 模糊匹配（≤255）、排序 `default_model desc, name, create_time` | `aimodel.py:86-119` | 保持；新增 `enable`/`valid` 字段返回 |
| B5 | `GET /system/aimodel/{id}` 解密 `api_key`/`api_domain`，`config` 转 `config_list` 返回 | `aimodel.py:122-150` | 保持 |
| B6 | `POST /system/aimodel` 首次创建（`count == 0`）自动设默认 | `aimodel.py:153-171` | **有意偏离**：改为不自动设默认，由用户显式指定 |
| B7 | `PUT /system/aimodel` 部分更新（`exclude_unset` 语义），未传字段保留原值 | `aimodel.py:174-191` | 保持；`valid` 沿用库值 |
| B8 | `DELETE /system/aimodel/{id}` 删除默认模型报错 `i18n_llm.delete_default_error` | `aimodel.py:194-207` | 保持 |
| B9 | `ws_mapping` 四个接口：查询、全量替换、追加去重、按 id 删除并返回剩余 | `aimodel.py:210-352` | 保持 |
| B10 | `GET /system/aimodel/list/by_ws` 需 `ws_admin` 权限，按 `default_model desc` 排序 | `aimodel.py:355-362` | 保持；过滤为 LLM + 启用 + 可用 |
| B11 | 工作空间模型列表 `with_default` 语义：绑定模型 ∪ 默认模型 | `aimodel_manage.py:34-67` | 保持；默认模型按类型判定 |
| B12 | `api_key`/`api_domain` 落库加密，读取解密 | `aimodel.py:140-146`、`crypto.py` | 保持；新列不加密 |
| B13 | 存量迁移：`supplier == 12` 归一为 `15`，`http` 开头的地址加密 | `aimodel_manage.py:9-31` | 保持；本次迁移脚本一并处理 |
| B14 | 模型增删改全量写审计日志（模块 `AI_MODEL`） | `aimodel.py` `@system_log` | 保持；新接口（模型列表、重建）同样纳入审计 |

## 2. 模型运行时

| # | SQLBot 行为 | 出处 | 本次处置 |
| --- | --- | --- | --- |
| R1 | `protocol=1` openai 兼容、`protocol=2` vllm，二者同构 | `model_factory.py:96-104`、`119` | 保持 |
| R2 | Azure 端点：`azure_endpoint` + `api_version` + `deployment_name` | `model_factory.py:79-94` | 保持；由解析器统一判定 |
| R3 | `config_list` 参数平铺传入模型客户端 | `model_factory.py:27`、`aimodel.py:29-36` | 保持；`paramsOf()` 不改语义 |
| R4 | 校验请求固定提问 `1+1=?` | `aimodel.py:39` | 保持 |
| R5 | 流式返回逐块转文本，usage 汇总 | `model_factory.py`、`openai/llm.py` | 保持；新增格式分支不改变 Chat Completions 行为 |
| R6 | embedding 远程优先、本地回落（`embedding.py`） | `apps/ai_model/embedding.py` | **有意偏离 D7**：移除本地回落，强制在线向量模型 |

## 3. 前端表单

| # | SQLBot 行为 | 出处 | 本次处置 |
| --- | --- | --- | --- |
| F1 | 模型名称必填、≤100、带说明 tooltip | `ModelForm.vue:95-126`、`291-307` | 保持；新增重名校验 |
| F2 | 基础模型 `filterable + allow-create + default-first-option + reserve-keyword=false`，带「回车添加」提示 | `ModelForm.vue:318-339` | 保持 |
| F3 | 切换提供商：按 `model_config[model_type].api_domain` 填充地址、清空基础模型、`protocol` 置 2（vllm）或 1、清空参数 | `ModelForm.vue:176-183` | 保持 |
| F4 | 选中基础模型：自动填充 `api_domain`（模型自带地址优先，其次供应商默认地址） | `ModelForm.vue:213-220` | 保持（注意：仅地址联动，名称不联动） |
| F5 | 参数按 `supplier-base_model` 组合缓存，切回时恢复 | `ModelForm.vue:49`、`221-231` | 保持 |
| F6 | 默认参数合并 `common_args` + 模型 `args`，按 key 去重 | `ModelForm.vue:236-253` | 保持 |
| F7 | 高级设置折叠区；参数表列 key/显示名/值 + 编辑/删除；每页 5 条分页 | `ModelForm.vue:359-420` | 增强：折叠区内并入上游格式与完整 URL，参数区保留分页 |
| F8 | 参数新增/编辑校验：显示名重复、key 重复分别提示 | `ModelForm.vue:138-166` | 保持 |
| F9 | API Key 为 password 且可显示明文 | `ModelForm.vue:349-357` | 保持；编辑态默认明文回显 |
| F10 | 提交时 `config_list` 映射为 `{key, name, val}` | `ModelForm.vue:256-269` | 保持 |
| F11 | 模型类型下拉不可编辑 | `ModelForm.vue:308-317` | **有意偏离**：新建可选类型，编辑态只读 |
| F12 | 选中模型后才显示地址、Key、高级设置 | `ModelForm.vue:340-370` | 保持 |
| F13 | 供应商选择页展示图标 + 名称 | `ModelList.vue` | 保持；`id=15` 换独立图标 |

## 4. 有意偏离清单（必须写明理由，不算打折）

| 偏离项 | SQLBot 行为 | 本次行为 | 理由 |
| --- | --- | --- | --- |
| D1 | 首次创建自动设默认 | 不自动设默认 | **已确认保留**：默认模型必须可用+启用，新建模型尚未校验，自动设默认会立即产生不可用默认 |
| D2 | `default_model` 全局唯一 | 按模型类型唯一 | 引入向量模型后，全局唯一会让两类模型互相顶掉 |
| D3 | 无启用/可用状态 | 新增 `enable` + `valid` | 需要区分「配置不可用」与「人工停用」，并对齐平台对接模块 |
| D4 | 模型类型恒只读 | 新建可选、编辑只读 | 需要支持向量模型配置 |
| D5 | 默认模型可删除（受保护） | 默认模型不可删除且不可停用 | 默认模型停用会造成链路无模型可用 |
| D6 | 仅有 `protocol` 区分 openai/vllm | 新增 `upstream_format`（含 Anthropic/Responses） | `protocol` 语义已被 vLLM 占用，不能复用 |
| D7 | embedding 远程优先、失败/未配置回落本地 ONNX | 只用在线向量模型，无可用模型时阻断问答并删除本地模型代码与依赖 | 已确认：不允许本地模型参与向量化；本地回退会让「配置未生效」被掩盖 |

## 5. 本项目新增能力（SQLBot 无对应实现）

对等是指「SQLBot 有的不能少」，不是「SQLBot 没有的不能做」。下列能力为新增，需独立验收：

| 能力 | 说明 |
| --- | --- |
| `enable` / `valid` 双状态 | SQLBot 只有未使用的 `status` 列 |
| `upstream_format`（含 Anthropic Messages、Responses） | SQLBot 只有 `protocol` 区分 openai/vllm + Azure 域名特判 |
| 上游模型列表拉取（`/models` 代理） | SQLBot 只有静态 `supplier.ts` 预设 |
| 完整 URL 开关与路径剥离 | SQLBot 无 |
| 向量索引全空间重建 + 待重建状态 | SQLBot 无（仅启动期补空回填） |
| 自定义提供商独立图标与 `supplier.custom` | SQLBot 复用 `icon_common_openai` |
| 按类型连通校验（embeddings 分支） | SQLBot 校验固定走对话补全 |

## 6. 验收方式

- 逐条对照本表打勾，任一条未覆盖即视为未完成。
- 除第 4 节列出的 7 项偏离（D1–D7）外，发现其他行为差异一律按缺陷处理。
- 后端以单测 + stub 覆盖 B/R 类，前端以 Playwright 覆盖 F 类。

## 7. 覆盖矩阵（16.8 复核，2026-11-02）

> 每行标注覆盖该条目的测试类或代码位置；e2e 用例位于 `askdata/askdata-server/e2e/tests/aimodel.spec.ts`。
> 后端单测类位于 `askdata/askdata-server/src/test/java/com/xiongx9527/askdata/`。

### B 类（后端接口面）

| # | 覆盖证据 |
| --- | --- |
| B1 | `AiModelService#streamStatus`；`AiModelListAndConnectivityTest#chatModelChecksChatCompletionsEndpoint`；e2e「连通校验 401 / 200」 |
| B2 | `AiModelService#requireDefaultModel`；`AiModelListAndConnectivityTest` |
| B3 | `AiModelService#setDefault`；e2e「设为默认模型 + 默认模型不可删除保护」 |
| B4 | `AiModelService#list`；`AiModelListAndConnectivityTest#remoteModelsAreReturnedWithTypeGuess` |
| B5 | `AiModelService#editor`；`AiModelListAndConnectivityTest#apiKeyIsSentAsBearer` |
| B6 | D1；`AiModelService#create`（不自动设默认） |
| B7 | `AiModelService#update`（未传字段沿用库值） |
| B8 | `AiModelService#delete`；e2e「设为默认模型 + 默认模型不可删除保护」 |
| B9 | `AiModelController` ws_mapping 四接口；`ChatModelSelectionParityTest#customModelIsSelectableWhenBoundToWorkspace` |
| B10 | `AiModelController#listByWs`；`ChatModelSelectionParityTest` |
| B11 | `ChatModelSelectionParityTest#defaultModelIsVisibleWithoutExplicitBinding` |
| B12 | `CryptoUtil`；`AiModelListAndConnectivityTest#apiKeyIsSentAsBearer` |
| B13 | `db/migration/mysql/V5__ai_model_states.sql`、`postgresql/V5__ai_model_states.sql`、`common/V2__seed.sql` |
| B14 | `AiModelController` 的 `@AuditLog(module=AI_MODEL)`；`AiModelEmbeddingEndpointGuardTest` |

### R 类（模型运行时）

| # | 覆盖证据 |
| --- | --- |
| R1 | `ChatModelClient`；`ChatModelClientEndpointTest` |
| R2 | `UpstreamFormatResolverTest#detectsAzureByHost`；`AiModelStatusAzureTest#azureEndpointBuildsDeploymentUrlAndDropsUrlOnlyParams` |
| R3 | `ChatModelClientPayloadTest`；`AiModelStatusAzureTest#configListIsMergedIntoModelParams` |
| R4 | `AiModelService#streamStatus`（固定提问 `1+1=?`） |
| R5 | `ChatModelClientFormatStreamTest#anthropicSseConcatenatesTextAndReadsUsage`、`#responsesSseConcatenatesDeltaAndReadsCompletedUsage` |
| R6 | D7；`EmbeddingServiceVectorStoreRoutingTest` |

### F 类（前端表单）

| # | 覆盖证据 |
| --- | --- |
| F1 | e2e「新增 -> 编辑 -> 搜索 -> 删除 闭环」；`ModelForm.vue` 名称规则 |
| F2 | e2e「远程拉取返回模型清单并标注『远程』」；`ModelForm.vue` base_model 选择器 |
| F3 | `ModelForm.vue` 切换提供商联动；e2e「上游 404 时回退『预设』清单」 |
| F4 | `ModelForm.vue` 选中模型地址联动（名称不联动）；e2e「远程拉取…」 |
| F5 | `ModelForm.vue` `tempConfigMap`（按 supplier-base_model 缓存） |
| F6 | `ModelForm.vue` `common_args` 合并去重 |
| F7 | `ModelForm.vue` 高级配置折叠区；e2e「上游格式 Anthropic 命中 /v1/messages」 |
| F8 | `ModelForm.vue` 参数显示名/key 重复校验 |
| F9 | `ModelForm.vue` api_key password + 编辑态明文回显 |
| F10 | `ModelForm.vue` 提交映射 `config_list` 为 `{key,name,val}` |
| F11 | `ModelForm.vue` 新建可选/编辑只读；e2e「上游格式 Anthropic…」（向量置灰） |
| F12 | `ModelForm.vue` 选中模型后才显示地址/Key/高级设置 |
| F13 | `ModelList.vue` 图标 + 名称；e2e「页面渲染与卡片信息」「切换语言后自定义供应商名称本地化」 |

### 有意偏离（D1–D7）

| # | 覆盖证据 |
| --- | --- |
| D1 | `AiModelService#create` 不自动设默认；`AiModelListAndConnectivityTest` |
| D2 | `AiModelRepository#clearDefaults(int)` 按类型清除；单测「清除 LLM 默认不影响向量默认」 |
| D3 | `V5` 迁移新增 `enable/valid`；e2e「连通校验 401 / 200」「已启用 / 未启用」双状态 |
| D4 | `ModelForm.vue` 新建可选类型、编辑只读；e2e「上游格式 Anthropic…」 |
| D5 | `AiModelService#update`（停用拒绝）、`#delete`（删除拒绝）；e2e「设为默认模型 + 默认模型不可删除保护」 |
| D6 | `UpstreamFormatResolver`；`UpstreamFormatResolverTest`；e2e「上游格式 Anthropic 命中 /v1/messages」 |
| D7 | 移除本地模型链路；`EmbeddingServiceVectorStoreRoutingTest`、`EmbeddingIndexServiceTest`；chat-real 走在线 TEI（`/v1/embeddings`） |

### 复核结论

- B 类 14 条、R 类 6 条、F 类 13 条（共 33 条）均已有覆盖证据，无空白项。
- D1–D7 全部有测试或代码位置佐证。
- 真实环境复核（2026-11-02）：后端 `mvn -pl askdata/askdata-server test` 全绿（唯一 skip 为 `EsEngineIntegrationTest`，属未配置 ES 凭据的环境门控，非本变更新增用例）；e2e `aimodel.spec.ts` 13 项、`chat-real.spec.ts`、`routes/api/scheduled/login` 全部通过。
