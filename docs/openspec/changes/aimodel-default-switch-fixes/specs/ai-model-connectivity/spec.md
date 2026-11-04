# ai-model-connectivity Spec Delta

## MODIFIED Requirements

### Requirement: 连通校验
系统 SHALL 提供连通校验能力，并依据模型类型选择端点：LLM 使用对话补全接口，向量模型使用 embeddings 接口，重排模型使用 rerank 接口。校验 MUST 使用当前表单中的 Base URL、凭据与模型参数，无需先保存。校验结果 MUST 回写模型的 enable 状态：通过置为可用，失败置为不可用。校验耗时中 MUST 禁止重复提交，并在界面上显示进行中状态。嵌入类校验 MUST 在返回时比对响应中 data[0].embedding 的长度与模型 dimension 字段：长度不一致 MUST 判定本次校验失败并指明维度不一致（实际 X 与配置 Y 不符）。将某模型设为默认、或编辑已是默认的模型时，若其连线字段（Base URL / API Key / 基础模型 / 协议 / 供应商 / 上游格式）相对既有值发生任一变化，MUST 重新执行联网探测；仅当连线字段无变化时才允许免探测直接落库（避免端点抖动时纯无关编辑被卡死）。探测失败 MUST 以统一、可读的友好文案返回原因，MUST NOT 直接透传原始异常或乱码。

#### Scenario: LLM 校验
- **WHEN** 用户对 LLM 模型点击校验
- **THEN** 系统向其对话补全端点发起最小请求，并根据响应回写 enable

#### Scenario: 向量模型校验
- **WHEN** 用户对向量模型点击校验
- **THEN** 系统向其 embeddings 端点发起最小请求，而不是对话补全端点

#### Scenario: 校验通过后可启用
- **WHEN** 校验成功且模型原为不可用
- **THEN** 模型变为可用，「是否生效」开关解除置灰

#### Scenario: 嵌入探测比对返回向量长度
- **WHEN** 模型 dimension=1024，上游返回 512 维向量
- **THEN** 校验失败，结果指明维度不一致（实际 512 与配置 1024 不符）

#### Scenario: 默认模型连线字段变化才重探
- **WHEN** 默认模型 A 的 Base URL 未变化、仅改显示名
- **THEN** 保存时免探测直接落库；而改 Base URL 时重新探测，失败则拒绝

#### Scenario: 探测失败返回友好文案
- **WHEN** 上游不可达，设为默认触发探测
- **THEN** 返回「联网探测未通过（Connection refused）」之类可读文案，而非异常堆栈
