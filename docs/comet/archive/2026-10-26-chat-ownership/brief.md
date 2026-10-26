# chat-ownership

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A1: A1 会话归属与最近问题：同一工作空间内用户 A/B 的会话列表、分页、计数和最近问题只返回各自的会话/提问（`create_by + oid` 双过滤，最近问题限 10 条）；管理员读取他人会话或 `/chat/record/{rid}/data|log|usage|excel` 时同样被拒绝，不再有管理员 owner 豁免。
- A2: A2 分析/预测落库：对基础记录发起分析/预测后，新子记录的 `analysis_record_id`/`predict_record_id` 指向基础记录，基础记录两个外键保持 NULL；子记录复制基础记录的 chart/data，`/chat/{id}` 返回全部分析/预测子记录，刷新后气泡与预测数据仍可见。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
