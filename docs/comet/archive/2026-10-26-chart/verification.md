---
generated_from_state_version: 1
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-26T13:21:58.723Z
- 摘要: 独立复核候选 3c362b0：A4 图表语义与 A5 token 生命周期均有真实代码路径与测试证据，Runtime 检查 vc-chart-a4/vc-chart-a5 绑定该候选且通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A4 | passed | brief.md | A4 服务端图表语义：服务端出图对 series 使用不同颜色、保留 multi-quota 多指标轴、横向 bar 条长按数值比例、area 填充、pie 标签与数值格式；`ChatChartBuilder` 保留 `axis.multi-quota`，未知 chart type 返回错误而不是静默改 table。 | ChartImageRenderer.seriesColor/areaFillColor 多 series 不同色、area 浅投影填充；drawCartesian barLen=((vals[i]-min)/(max-min))*plotW 横向条长按值比例；pieLabel=名称+数值+占比；ChatChartBuilder.normalize/build 保留 axis.multi-quota；normalize 未知/缺 type 抛 IllegalArgumentException。ChartSemanticsParityTest 像素级与行为断言全绿。 |
| A5 | passed | brief.md | A5 出图 URL 生命周期：MCP/IM/第三方拿到的 `image_url` 使用持久化 token→文件映射，进程重启后仍可打开；未登录第三方在有效期内可访问，篡改/过期 token 返回 404/403；`/api/chart/image/*` 在鉴权白名单内；过期图片文件与 token 按 TTL 清理，不无限增长。 | ChartImageService.saveStore 写磁盘 JSON 映射、initStore reload 支持重启；controller 免鉴权返回 200 供有效期内第三方访问；get() 对非 32-hex/unknown/expired 返回 null→404；AuthWhitelist.CHART_IMAGE_PATHS 含 /api/chart/image/*；cleanupExpired + @Scheduled 按期清理。ChartImageTokenLifecycleTest 全绿。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| ChartSemanticsParityTest (A4 服务端图表语义) | -Dmaven.test.skip=false -Dtest=ChartSemanticsParityTest test | askdata/askdata-server | passed | 0 | 23653 ms |
| ChartImageTokenLifecycleTest (A5 出图 URL 生命周期) | -Dmaven.test.skip=false -Dtest=ChartImageTokenLifecycleTest test | askdata/askdata-server | passed | 0 | 27180 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- ChartSemanticsParityTest (A4 服务端图表语义): passed — —
- ChartImageTokenLifecycleTest (A5 出图 URL 生命周期): passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立复核候选 3c362b0：A4 图表语义与 A5 token 生命周期均有真实代码路径与测试证据，Runtime 检查 vc-chart-a4/vc-chart-a5 绑定该候选且通过。
