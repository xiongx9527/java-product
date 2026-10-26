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
- 完成时间: 2026-10-26T13:23:39.695Z
- 摘要: 独立复核候选 33cd966：A6 端点授权与 A7 权限硬化均真实实现，Runtime 检查 vc-authz-a6/vc-authz-a7 绑定该候选且通过。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A6 | passed | brief.md | A6 数据源端点授权：表关系 save/get 与推荐问题 list/base/save 要求 ws_admin 且数据源 `type='ds'`/资源授权，未授权用户被拒绝；`POST /datasource/execSql/{id}` 保留但要求 ws_admin，并在执行前应用 `hiddenColumnsFor` 与 `rowFilterWhereFor`，非 ws_admin 无法绕过行列权限。 | TableRelationService.checkAuthorized(50-66) 与 RecommendedProblemService.checkAuthorized(62-74) 在 save/get/list/base/save 入口调 requireWsAdmin + dsGrantService.checkUsable（资源授权 type='ds'）；DatasourceController.execSql 改调 execSqlAuthorized(148-150)，先 requireWsAdmin 再经 PermissionEnforcer.enforce 注入行权限并 applyHiddenColumns 后执行。DatasourceEndpointAuthzTest 覆盖非 ws_admin 拒绝、资源未授权拒绝、授权放行、execSql 强制链路。 |
| A7 | passed | brief.md | A7 权限硬化：行权限按 varType 校验值域并对 kv 做 key→value 映射，越界值被拒绝；只读 SQL 校验基于 AST 函数节点而不是原文正则，字符串字面量中的 `version(` 不误拒、真实危险函数被拒；列权限在构造 schema/提示词/样例行之前剔除无权字段。 | SqlSafety 改为 jsqlparser AST 遍历 Function 节点收集函数名，字符串字面量 version( 不再误判而真实 version()/current_user() 被拒；DsPermissionQueryService.parseKv + buildUserVariableWhereValue 越界 kv 抛 IllegalArgumentException(fail-closed) 合法值映射真实列；SchemaContextBuilder.hiddenColumnsFor 构造 schema/提示词前剔除无权限列。测试覆盖双方向与列剔除。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| DatasourceEndpointAuthzTest (A6 端点授权与 execSql 权限强制) | -Dmaven.test.skip=false -Dtest=DatasourceEndpointAuthzTest test | askdata/askdata-server | passed | 0 | 25637 ms |
| RowColumnPermissionHardeningTest (A7 AST 函数检测/kv 值域/列剔除) | -Dmaven.test.skip=false -Dtest=RowColumnPermissionHardeningTest test | askdata/askdata-server | passed | 0 | 26746 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- DatasourceEndpointAuthzTest (A6 端点授权与 execSql 权限强制): passed — —
- RowColumnPermissionHardeningTest (A7 AST 函数检测/kv 值域/列剔除): passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立复核候选 33cd966：A6 端点授权与 A7 权限硬化均真实实现，Runtime 检查 vc-authz-a6/vc-authz-a7 绑定该候选且通过。
