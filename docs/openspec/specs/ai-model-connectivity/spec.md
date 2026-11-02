# ai-model-connectivity Specification

## Purpose
定义模型连接相关的对外契约：Base URL 规范化、上游格式识别、模型列表获取与连通校验的行为与降级规则。

## Requirements

### Requirement: Base URL 规范化

系统 SHALL 根据 Base URL 与上游格式推导实际请求地址。

规范化规则 MUST 为：去除尾部 `/`；若以 `/chat/completions` 结尾则先剥离；随后解析路径，路径为空时补 `/v1`，非空时直接作为前缀。

当「完整 URL」开启时，系统 MUST 原样使用填写值，不得追加任何路径段。

#### Scenario: 仅填写主机
- **WHEN** Base URL 为 `https://api.deepseek.com`
- **THEN** 对话请求地址为 `https://api.deepseek.com/v1/chat/completions`

#### Scenario: 已含版本路径
- **WHEN** Base URL 为 `https://api.openai.com/v1`
- **THEN** 对话请求地址为 `https://api.openai.com/v1/chat/completions`

#### Scenario: 误粘贴完整路径
- **WHEN** Base URL 为 `https://api.deepseek.com/v1/chat/completions`
- **THEN** 规范化后请求地址仍为 `https://api.deepseek.com/v1/chat/completions`，不出现重复路径

#### Scenario: 完整 URL 开启
- **WHEN** 完整 URL 开启且填写 `https://gw.example.com/proxy/chat/completions`
- **THEN** 请求地址为填写值本身

### Requirement: 上游格式识别与覆盖

系统 SHALL 提供上游格式字段，取值包括：自动识别、Chat Completions、Anthropic Messages、Responses、Azure OpenAI。

自动识别 MUST 遵循：主机匹配 `*.openai.azure.com` 或 `*.openai.azure.cn` 时判为 Azure OpenAI；路径含 `/anthropic` 或主机为 `api.anthropic.com` 时判为 Anthropic Messages；其余判为 Chat Completions。

用户手动指定格式时 MUST 覆盖自动识别结果；切换格式 MUST NOT 自动改写 Base URL。

同一份格式判定 MUST 被模型列表获取、连通校验与运行时请求三处共用，不得各自实现。

#### Scenario: DeepSeek 双协议
- **WHEN** Base URL 为 `https://api.deepseek.com` 且格式为自动识别
- **THEN** 判定为 Chat Completions

#### Scenario: DeepSeek Anthropic 端点
- **WHEN** Base URL 为 `https://api.deepseek.com/anthropic` 且格式为自动识别
- **THEN** 判定为 Anthropic Messages

#### Scenario: 手动覆盖
- **WHEN** 自动识别为非标准结果，用户手动选择 Chat Completions
- **THEN** 后续请求按 Chat Completions 处理，Base URL 保持不变

### Requirement: 模型列表获取

系统 SHALL 提供模型列表获取能力，由服务端代理请求上游模型接口，避免浏览器跨域限制。

获取结果 MUST 携带来源标识：远程获取或供应商预设；前端 MUST 在选项中标注来源。

拉取 SHALL 在 Base URL 与凭据齐备后自动触发，并提供手动刷新入口；请求 MUST 防抖，且 MUST 以请求序号丢弃过期响应。

上游返回的模型 MUST 按名称启发式标注类型，前端 SHALL 依据当前模型类型过滤选项。

拉取失败、超时或返回空列表时，系统 SHALL 降级为供应商静态预设；自定义提供商没有静态预设，MUST 提示只能手动输入。

「完整 URL」开启时，系统 SHALL 剥离末尾的 `/chat/completions`、`/responses` 或 `/messages` 后再拼 `/models`；无法剥离时 MUST 禁用拉取并提示手动输入。

获取过程中 MUST NOT 输出或回显 API Key，MUST NOT 因上游错误返回 5xx。

#### Scenario: 正常拉取
- **WHEN** 用户填写 Base URL 与 API Key，且上游返回 30 个模型
- **THEN** 选项显示 30 个带「远程」标签的模型，并按当前类型过滤

#### Scenario: 拉取失败降级
- **WHEN** 上游返回 404
- **THEN** 选项回退为供应商静态预设并标注「预设」，用户仍可手动输入

#### Scenario: 自定义提供商无兜底
- **WHEN** 自定义提供商的拉取请求失败
- **THEN** 不展示任何预设，提示用户手动输入模型名

#### Scenario: 完整 URL 无法剥离
- **WHEN** 完整 URL 开启且地址为 `https://gw.example.com/api/v2/infer`
- **THEN** 刷新列表入口禁用，提示该地址不支持自动获取

### Requirement: 连通校验

系统 SHALL 提供连通校验能力，并依据模型类型选择端点：LLM 使用对话补全接口，向量模型使用 embeddings 接口。

校验 MUST 使用当前表单中的 Base URL、凭据与模型参数，无需先保存。

校验结果 MUST 回写模型的 `valid` 状态：通过置为可用，失败置为不可用。

校验耗时中 MUST 禁止重复提交，并在界面上显示进行中状态。

#### Scenario: LLM 校验
- **WHEN** 用户对 LLM 模型点击校验
- **THEN** 系统向其对话补全端点发起最小请求，并根据响应回写 valid

#### Scenario: 向量模型校验
- **WHEN** 用户对向量模型点击校验
- **THEN** 系统向其 embeddings 端点发起最小请求，而不是对话补全端点

#### Scenario: 校验通过后可启用
- **WHEN** 校验成功且模型原为不可用
- **THEN** 模型变为可用，「是否生效」开关解除置灰
