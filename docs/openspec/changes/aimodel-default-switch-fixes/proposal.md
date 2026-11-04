# Proposal

## Why

默认模型切换与修改是高频运维动作，但当前实现与已确立规范（ai-model-configuration / ai-model-connectivity）存在多处漂移与缺口：update 把模型设为默认时不清除同类型旧默认，可能同类型出现多个默认；设为/改为默认向量模型时只做连通探测、未复用集合维度冲突校验，维度变化后问答入口直接被「索引待重建」拦死且无引导；强探测在改无关字段时也强制重连，端点抖动即卡死保存；嵌入探测不比对返回向量长度，维度不一致隐患潜伏。本次让实现对齐既有规范并补齐这些缺口。

## What Changes

- 默认唯一性：update 将 default_model 置 true 时，同步清除同类型旧默认（与 setDefault 一致），杜绝同类型多默认。
- 维度冲突拦截：setDefault 与 update（向量默认）复用 storeDimensionConflict，命中且与当前在线集合维度冲突时硬拦截并提示需先重建索引；非默认仅提示。
- 切换前确认与维度提示：切换向量默认前展示「旧维度 → 新维度」确认，维度不同明确指出需重建索引。
- 重建引导打通：切换后常驻「向量索引待重建」提示 + 一键重建入口；重建完成且维度对齐后解除问答拦截（状态 pending 已暴露，前端 banner 对接）。
- 放宽强探测：仅当连线字段（Base URL / API Key / 基础模型 / 协议 / 供应商 / 上游格式）真变化时强制联网探测；纯改名、无关参数编辑不重探。
- 探测口径加固：嵌入探测比对返回向量长度 == dimension；探测失败原因统一走友好文案，不再透传原始异常。
- 清理规范漂移：移除规范中已过时的 valid 列语义（已并入 enable），消除规范/实现不一致。

## Capabilities

### New Capabilities
<!-- 无新增能力，全部为对既有能力的修正与补齐 -->

### Modified Capabilities
- ai-model-configuration：修正「默认模型按类型唯一」「向量维度与存储对齐」「向量默认切换后的索引重建」「可用与启用状态」中关于默认切换/修改的行为与已过时的 valid 语义。
- ai-model-connectivity：修正「连通校验」中关于默认模型探测触发条件、嵌入维度比对与失败文案的要求。

## Impact

- 后端：AiModelService（setDefault / update / probeModel）、ChatModelRouter（已修 valid 残留）、EmbeddingService / VectorStore（storeDimensionConflict / collectionsWithDimensionOtherThan）、EmbeddingIndexService（重建与 pending 状态）。
- 前端：Model.vue 等（切换向量默认确认弹窗、待重建 banner、一键重建入口对接）。
- 行为变化：默认向量模型维度冲突改为硬拦截；非连线字段编辑不再因端点抖动失败而卡死。
