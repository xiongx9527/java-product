# 目标

askdata 相对 SQLBot 的能力覆盖已通过 2026-10-19～10-23 的多轮 change 完成主链路对齐；本轮收尾
`docs/audit/2026-10-23-askdata-sqlbot-remaining-parity-audit.md` 中**当轮未修复**的中低优先项，
使 askdata 对 SQLBot 开源社区版达到「接口等价 + 实现不打折」的目标态。

具体消除以下审计编号（2026-10-23 复验后仍未修复的）：

- F-03 强制改密链路：`LoginController.accessToken` 增加默认口令判定/发 token/need_change_pwd
  回写；`changePwd` 拒绝「新口令 == 默认口令」；`login.initial_pwd_disabled` 参与判定。
- F-04 默认口令可预测风险：`askdata.user.default-password` 全链路语义明确（配置缺省 → 拒绝创建
  或显式回退策略），不得静默 UUID。
- F-05 系统管理面审计补齐：`system/` 全模块补 `@AuditLog`（用户/工作空间/AI 模型/API Key/
  助手应用/参数页/appearance 等）。
- F-07 审计资源名：CHAT 模块资源名解析、API_KEY 已在本轮临时修（access_key 列）纳入回归。
- F-08 embedded 通道 `assistant.oid` 回写缺失。
- F-11 登出 SSO origin 分支。
- F-13 尾斜杠归一化 / F-14 IP 头链策略复核（如属有意偏离需在 verify 记录豁免理由）。
- F-15 i18n：§3 清单中硬编码英文/中文错误文案按 5 语言消息包收敛（本轮 quota：全部）。
- F-17 Excel 导入列集对齐 SQLBot（用户导入 4 列 → 目标列集）。
- F-18 schema.sql 重复 DDL 清理（sys_logs_resource 两处）。
- F-19 审计关联行写入条件（resourceId 为空时也写关联表的语义复核）。
- F-20 注释漂移（AuthWhitelist「33 条」注释与实际不符）。
- §1.3 /docs 文档站收尾：Knife4j 已引入（本轮），确认 AuthWhitelist 白名单 + 前端 API KEY
  页文档入口链路可用；i18n 语言切换渲染。
- §1.2 MCP 协议细节回归：mcp_ds_list 权限作用域宽度复核（本轮已接 workspaceId）。
- §2.8 MCP `question_answer` returnImg=false 语义回归。
- §2.10 推荐问题模板增强（图表类型引导 + 以往提问注入）。
- §2.7 表名校验 AST 化已在本轮完成（记录为已完成，不再重复）。
- F-10 缓存层：如评估为超范围，在 brief 的非目标中显式声明并给出理由，不做静默裁剪。

# 范围

## 目录结构

### 新建

- 无独立新模块；改动分布在既有 controller/service/枚举与 i18n 消息包内。

### 修改

- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/system/**`：改密链路、
  默认口令策略、@AuditLog 覆盖、外观/认证参数联动。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/common/web/**`：鉴权通道
  （尾斜杠、SSO 登出分支、embedded oid 回写）。
- `askdata/askdata-server/src/main/java/com/xiongx9527/askdata/mcp/**`：MCP 工具细节回归。
- `askdata/askdata-server/src/main/resources/messages_*.properties`：§3 错误文案键补齐。
- `askdata/askdata-server/src/main/resources/schema.sql`：重复 DDL 清理。
- `askdata/askdata-server/src/test/java/**`：以上各项的单测/端到端回归。

### 删除

- 无。

### 明确不建

- 不引入新数据源类型、不改流式协议帧格式。
- 不做 Spring Cache/Redis 引入（F-10，如需单独评估请开独立 change）。

# 非目标

- 不重写已完成的主链路对齐（问数/RAG/动态数据源/MCP 接线/审计骨架）。
- 不处理 2026-10-23 审计已标注「有意偏离」且用户已接受的项（如 F-09/F-12 的 400/回退策略，
  除非 verify 发现新证据）。
- 不做跨语言移植（SQLBot 保持 Python 事实源，仅做行为对照）。

# 验收示例

- A1：默认口令用户登录 → accessToken 响应携带 `need_change_pwd=true` 且不发放主 token
  （或按 SQLBot 语义走 changePwd 引导）；`changePwd` 提交相同默认口令被 400 拒绝，
  文案走 i18n 键。
- A2：`system/` 任一管理端点（如 AI 模型删除、工作空间成员移除、API Key 重置）操作后，
  `sys_logs_*` 出现对应审计行，含 uid、resource 名、请求路径；API Key 行资源名取 access_key。
- A3：API Key / 嵌入助手通道写操作，审计行 uid 非 NULL（读 AuthContext 回写）。
- A4：硬编码英文文案扫描（测试）在 §3 清单文件集上 0 命中；错误响应随 Accept-Language 切换。
- A5：`/doc.html`、`/v3/api-docs` 免鉴权可达；前端 API KEY 页入口点击可打开文档站。
- A6：schema.sql 中 `sys_logs_resource` DDL 唯一；全量测试通过。
- A7：MCP `question_answer` 在 returnImg=false 时不产出 imageUrl 字段（回归断言）。
