# askdata-sqlbot-remaining-fidelity 目标规格

> 本规格描述收尾 change 归档后 askdata 相对 SQLBot 开源社区版的**剩余保真项**的目标行为。
> 除本文明确新增/变更的部分外，其余行为沿用已归档的正式规格：
> `docs/comet/specs/askdata/spec.md`、`docs/comet/specs/askdata-sqlbot-gap/spec.md`、
> `docs/comet/specs/askdata-cn-table-fix/spec.md`、`docs/comet/specs/askdata-mcp-framework/spec.md`、
> `docs/comet/specs/askdata-sqlbot-parity/spec.md`、`docs/comet/specs/askdata-sqlbot-full-parity/spec.md`、
> `docs/comet/specs/askdata-sqlbot-xpack-parity/spec.md`。
>
> 事实源：`docs/audit/2026-10-23-askdata-sqlbot-remaining-parity-audit.md`（编号 F-xx / §x.x）。

## 1. 强制改密链路（F-03）

### 场景：默认口令用户登录

管理员以默认口令（由 `askdata.user.default-password` 配置）登录时，登录判定链路识别「当前口令
== 默认口令」，按 SQLBot 语义走改密分支：响应标记 `need_change_pwd=true` 并进入改密引导，而非
直接发放主 token。`login.initial_pwd_disabled=true` 禁用默认口令。改密成功后 `(uid, md5(new))
的判定不再命中。

### 场景：新口令不可再为默认口令

`changePwd` 校验新口令：等于默认口令时以 400 + i18n 文案拒绝。

验收：A1

## 2. 系统管理面审计（F-05 / F-07 / F-08 / F-19）

### 场景：管理端点全覆盖

`system/` 全模块（用户、工作空间、成员、AI 模型、API Key、助手应用、参数页、外观、认证）
的写操作端点标注 `@AuditLog`；审计行含 uid、module、operation、resourceId、resource 展示名
（API Key 取 `access_key` 列）、请求方法与路径。

### 场景：支线通道审计 uid 回写

API Key 通道 / 嵌入助手通道 / 平台 SSO 回调请求触发审计时，`AuditRecorder.currentUid()` 优先
Sa-Token，失败回退 `AuthContext.currentUser()`；uid 为 NULL 的支线通道写操作审计行从根消除。

### 场景：embedded 助手 oid 对齐

嵌入助手鉴权成功后的 assistant 上下文 `oid` 与助手归属工作空间一致（SQLBot
`assistant.oid = user.oid` 语义）。

验收：A2、A3

## 3. 登出与其他鉴权细节（F-11 / F-13 / F-20）

### 场景：SSO 登出分支

`/login/logout` 按登录来源（本地 / 平台 SSO origin）走对应分支，SSO 用户登出时先完成平台侧
会话语义（等同 SQLBot logout 的 origin 分支），再清除本地状态。

### 场景：尾斜杠与注释一致

鉴权白名单路径匹配不受尾斜杠数量影响（`/path`、`/path/`、`//path/` 同判）；白名单注释与实际
条目数量一致（修复「33 条」漂移）。

验收：A6（随全量测试）、注释一致性断言

## 4. i18n 错误文案收敛（F-15 / F-17）

### 场景：§3 清单清零

`2026-10-23` 审计 §3 列出的硬编码英文/中文文案（`Missing required argument`、
`Terminology does not exist`、`Account does not exist`、`Assistant application not exist`、
`Dynamic datasource is only supported...` 等 15+ 处）迁移到 `messages_*.properties` 五语言
消息包，键随异常抛出点注入 `MessageSource`；响应用户侧的 `ApiExceptionHandler` 按
`Accept-Language` 渲染。

### 场景：用户 Excel 导入列集

用户批量导入支持与 SQLBot 对齐的列集与打开语义（原 4 列扩展项在 brief/verify 复核对齐口径后
纳入或声明豁免理由）。

验收：A4

## 5. /docs 文档站（§1.3 + 本轮已引入 Knife4j）

### 场景：文档站可用性

`/doc.html`（Knife4j UI）与 `/v3/api-docs`（OpenAPI 3 JSON）免鉴权可达
（`AuthWhitelist`F 白名单含 `/doc.html`、`/v3/api-docs*`，路径不匹配尾斜杠变体）；前端
API KEY 页的文档入口可打开。Knife4j 语言设置生效。

验收：A5

## 6. schema 一致性（F-18）

### 场景：重复 DDL 清理

`sys_logs_resource` 在 `schema.sql` 仅保留一份 CREATE TABLE；幂等 MERGE 种子不受影响；
H2 启动无重复表冲突。

验收：A6

## 7. MCP 细节回归（§2.8 / §1.2 复验）

### 场景：returnImg=false 不出图

MCP `question_answer` 在 `returnImg=false` 时不调用 SSR 渲染、响应不含 `imageUrl`；
缺省（未传）保持 true（对齐 SQLBot `McpQuestion.return_img` 命中即返图）。

### 场景：数据源作用域

MCP `datasource_list` 按 `workspaceId` 透传作用域（缺省当前用户 oid 作用域，管理员可跨空间），
不回退为全量列表。

验收：A7

## 8. 本轮已完成的项（不重复实现）

- chat 参数空转（sqlbot_name / limit_rows / context_record_count）→ 已接入运行链路。
- 分析/预测自定义提示词（ANALYSIS / PREDICT_DATA 插槽）→ 已接入。
- 模型推理参数透传（temperature / top_p / max_tokens / additional_params）→ 已接入。
- 动态数据源表级 SQL 模板（dynamic_sql → CTE 提示注入）→ 已接入。
- 术语召回使用抽取关键词 + 逗号分词逐词匹配 → 已接入。
- SQL 示例 Excel 导入后向量补算 → 已接入。
- 表名安全检查 AST 化（CTE 别名剔除、引号标识符）→ 已接入。
- 行上限落库 `limit` 戳 → 已接入（阈值 1000，对齐 SQLBot save_sql_data）。
- 审计 API_KEY 资源名列（access_key）→ 已接入。

以上项仅在 verify 阶段做回归断言，不在 Build 重写。
