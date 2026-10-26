# chart

子任务归档: askdata-sqlbot-p0-parity

本归档仅覆盖以下已确认验收项。Spec 是 Parent 已确认内容的快照，不表示独立 Child Spec 变更或新增验收。

- A4: A4 服务端图表语义：服务端出图对 series 使用不同颜色、保留 multi-quota 多指标轴、横向 bar 条长按数值比例、area 填充、pie 标签与数值格式；`ChatChartBuilder` 保留 `axis.multi-quota`，未知 chart type 返回错误而不是静默改 table。
- A5: A5 出图 URL 生命周期：MCP/IM/第三方拿到的 `image_url` 使用持久化 token→文件映射，进程重启后仍可打开；未登录第三方在有效期内可访问，篡改/过期 token 返回 404/403；`/api/chart/image/*` 在鉴权白名单内；过期图片文件与 token 按 TTL 清理，不无限增长。

[Parent Spec](spec.md) · [来源与原始简报](archive-source.md) · [验收记录](verification.md)
