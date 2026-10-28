# AskData 多实例集群就绪——完整目标规格

本规格描述本 change 完成后 `askdata/askdata-server` 在多实例集群部署下应具备的行为与可验证边界。行为按「集群模式与自检、跨实例文件访问、图表图片、会话与缓存一致性、定时任务租约、ID 唯一性、部署基线」分组。

核心约束：**任意请求落到任意实例，结果一致**。凡是「写在本地、读在本地」的状态都必须收敛到共享基础设施（元数据库 / Redis / 对象存储），或明确声明为单实例能力并在集群模式下被自检拦截。

## 集群模式与启动自检

### Requirement: 集群模式开关

系统应提供 `askdata.cluster.enabled` 开关（默认 `false`），开启后进入集群语义：启动期执行就绪自检，并对不满足共享化要求的配置项给出诊断。

关闭时保持现有单实例行为完全不变：H2 文件库 + 本地文件存储 + 进程内会话/缓存可零外部依赖启动。

#### Scenario: 单实例零回归

- **WHEN** 不设置 `askdata.cluster.enabled`（或显式设为 `false`）启动
- **THEN** 应用以 H2 文件库、本地文件存储、内存会话与内存缓存正常启动，既有全量测试与探针全部通过，行为与本 change 之前一致

验收：A14

### Requirement: 启动期集群就绪自检

开启集群模式后，系统应在启动阶段逐项检查：元数据库是否为共享库（非 H2 本地文件）、`askdata.secret.key` 是否已显式配置、会话存储是否为 Redis、缓存后端是否为 Redis、单例锁后端是否可用、文件存储平台是否为远端、雪花 ID 的 workerId 是否可保证跨实例唯一。

每项检查输出「检查项 / 结果 / 说明 / 修复建议」，汇总为一条可读启动日志；不得在自检阶段抛出未捕获异常导致无诊断的启动失败。

#### Scenario: 不满足项给出可读诊断

- **WHEN** `askdata.cluster.enabled=true`，但元数据库仍为 `jdbc:h2:file:`、`ASKDATA_SECRET_KEY` 为空、会话与缓存仍为 memory
- **THEN** 启动日志逐项列出不满足项及对应配置键与修复建议，且不因自检本身导致启动崩溃

验收：A1

#### Scenario: fail-fast 拒绝启动

- **WHEN** 集群模式下设置 `askdata.cluster.fail-fast=true`，且存在不满足项
- **THEN** 应用拒绝完成启动，错误信息明确指出第一处不满足项与修复方式

验收：A1

#### Scenario: 全部满足时自检通过

- **WHEN** 元数据库为 MySQL/PostgreSQL、Redis 可用、对象存储可用、`ASKDATA_SECRET_KEY` 已配置
- **THEN** 自检全部通过，启动日志打印通过汇总，无告警、无 fail-fast

验收：A2

### Requirement: MCP SSE 跨实例消息路由

框架已提供 Redis 版 SSE 会话注册表：当容器内存在 `StringRedisTemplate` 时，`McpAutoConfiguration.RedisClusterConfiguration` 会注册 `McpRedisBroadcaster`、`McpRedisMessageConsumer` 与 Redis 监听容器，SSE 会话注册表切换为 `RedisServerSseRegistry`，跨实例消息经 Redis pub/sub 路由到持有连接的实例。

系统应保证：集群模式启用 Redis 时，MCP 自动获得跨实例能力，不要求负载均衡粘性会话；集群模式未启用 Redis 时，自检必须明确指出当前为进程内会话并给出两种解决方式。

本改造不得破坏「未部署 Redis 时应用可正常启动」这一现状：`RedisAutoConfiguration` 保持排除，Redis 相关 Bean 仅在确实启用 Redis 时装配。

#### Scenario: A 建流 B 收消息

- **WHEN** 集群模式（Redis 已启用）下，实例 A 收到 `GET /mcp/sse` 并建立会话，客户端把 `POST /mcp/messages?sessionId=xxx` 发到实例 B
- **THEN** B 通过 Redis 广播把请求转给 A，A 在 SSE 流上推送响应；客户端完成 `initialize` 与 `tools/call` 全流程，无 404、无 session not found

验收：A13

#### Scenario: 集群但无 Redis 时告警

- **WHEN** `askdata.cluster.enabled=true` 但未启用 Redis（容器内无 `StringRedisTemplate`）
- **THEN** 启动自检输出告警：MCP 当前为进程内 SSE 会话，跨实例调用需要粘性会话或启用 Redis

验收：A13

#### Scenario: 无 Redis 仍可启动

- **WHEN** 不启用 Redis（单实例默认配置）启动
- **THEN** 应用正常启动，MCP 使用进程内注册表，既有 MCP 端到端测试通过

验收：A14

## 跨实例文件访问

### Requirement: 所有业务文件走统一文件存储

系统内所有需要跨请求或跨实例访问的文件（助手 logo、设置图片、数据源 Excel 上传与解析中间文件、术语导入与错误行、训练数据导入导出、定时任务导出、图表图片）应统一通过文件存储抽象读写，不得再依赖进程本地目录。

单实例部署下允许本地平台作为实现，但读写入口必须是同一抽象，以便切换平台后无需改业务代码。

#### Scenario: 通用文件跨实例读写

- **WHEN** 实例 A 上传助手 logo，实例 B 收到同一文件的下载请求
- **THEN** B 返回的字节与 A 上传内容完全一致；A 替换 logo 后，B 访问旧文件返回 404

验收：A3

### Requirement: Excel 上传与导入支持跨实例

数据源 Excel 的「解析预览」与「导入建表」是两个独立请求，中间产物必须存放在共享存储中，且返回给调用方的引用可在任意实例解析。

为兼容既有调用方，历史返回值（本地文件名）仍需能被识别与读取。

#### Scenario: A 上传 B 导入

- **WHEN** 实例 A 调用 `POST /datasource/parseExcel` 上传 Excel，随后实例 B 使用返回的 `filePath` 调用 `POST /datasource/importToDb`
- **THEN** B 成功读取该文件并建表，返回的表结构与 A 的解析预览一致

验收：A4

#### Scenario: 旧引用兼容

- **WHEN** 调用方传入的是历史形态的本地文件名（文件确实存在于配置的本地目录）
- **THEN** 导入仍成功，不因改造而破坏既有前端调用

验收：A4

### Requirement: 术语、训练与设置文件支持跨实例

术语导入、术语错误行导出、训练数据导入导出、设置类图片上传，均应写入共享文件存储；下载端点从存储读取，不依赖请求命中的实例。

#### Scenario: A 写 B 读

- **WHEN** 在实例 A 完成术语导入并产生错误行文件，随后在实例 B 下载该文件
- **THEN** B 返回完整文件内容；设置图片同理，A 上传后 B 可取回

验收：A5

### Requirement: 定时任务导出文件可跨实例获取

定时任务执行产生的 CSV/XLSX 应写入共享文件存储，运行记录中的 `export_path` 保存可在任意实例解析的引用；历史记录中形如本地绝对路径的值仍需保持可读或给出明确提示。

#### Scenario: A 执行 B 下载

- **WHEN** 定时任务在实例 A 执行并导出，随后在实例 B 读取该次运行记录并下载导出文件
- **THEN** 下载成功，文件行数与当次查询结果一致

验收：A6

## 图表图片

### Requirement: 图表图片 token 跨实例可解析

图表图片本体存放于文件存储；token 到文件的映射必须共享，不得只存在于生成图片的进程内。token 过期语义保持不变。

#### Scenario: A 生成 B 访问

- **WHEN** 实例 A 生成图表图片并返回 token，实例 B 使用同一 token 请求 `GET /api/chart/image`
- **THEN** B 返回与 A 相同的 PNG；token 过期后返回 404

验收：A7

### Requirement: 图表清理任务单实例执行

图表图片与 token 的定时清理应保证同一时刻只有一个实例执行，避免多实例并发清理造成重复删除或互相干扰。

#### Scenario: 双实例并发清理

- **WHEN** 两个实例的清理任务同时触发
- **THEN** 实际清理动作只执行一次；仍在有效期内的文件不被误删

验收：A8

## 会话、缓存与防抖一致性

### Requirement: 会话跨实例共享

集群模式下登录会话必须存放在共享存储，同一 token 在任意实例均有效；单实例模式保持进程内会话。

#### Scenario: A 登录 B 访问

- **WHEN** 用户在实例 A 登录取得 token，随后携带该 token 请求实例 B
- **THEN** B 返回 200 且用户身份一致；登出后 B 同样拒绝该 token

验收：A10

### Requirement: 缓存跨实例一致

用户、API Key、助手等缓存区域在集群模式下必须共享，写操作后的失效对所有实例立即生效，不得依赖 TTL 过期才收敛。

#### Scenario: 禁用用户立即生效

- **WHEN** 在实例 A 禁用某用户，随后该用户请求实例 B
- **THEN** B 立即拒绝该用户，不等待缓存 TTL

验收：A10

### Requirement: 防抖跨实例生效

接口防抖在集群模式下必须是全局窗口：同一用户对同一接口的重复请求落在不同实例时，只允许一次通过。

#### Scenario: 双实例并发重复提交

- **WHEN** 同一用户对同一防抖接口在两个实例上并发提交
- **THEN** 只有一次被执行，另一次返回防抖错误

验收：A11

## 定时任务租约

### Requirement: 任务执行权带租约可回收

定时任务的 `running` 抢占必须附带租约（持有者标识 + 过期时间）。执行实例异常退出（进程被杀、容器重启）后，租约到期应被其他实例回收，任务恢复可调度。

回收不得误伤仍在正常执行中的任务：执行期间应续租，续租失败的实例应停止继续持有。

#### Scenario: 节点被强杀后任务恢复

- **WHEN** 任务在实例 A 执行期间 A 被强杀，`running` 标志残留
- **THEN** 租约到期后实例 B 的轮询回收该任务并重新执行，最终产生一条成功的运行记录

验收：A9

### Requirement: 单例后台任务在集群下只执行一次

嵌入回填等单例启动任务应使用共享锁后端；锁后端不可用时跳过执行并告警，不得在多个实例上并发执行。

#### Scenario: 双实例启动只执行一次

- **WHEN** 两个实例同时启动且都满足回填触发条件
- **THEN** 回填只在一个实例上执行，另一个实例记录跳过原因

验收：A9

## ID 唯一性

### Requirement: 雪花 ID 跨实例唯一

集群模式下实体 ID 必须保证跨实例唯一。默认使用框架的 workerId 分配（共享元数据库）；当该机制被关闭且未显式配置 `askdata.id.worker-id` 时，自检必须给出撞号风险告警。

#### Scenario: 双实例批量生成无重复

- **WHEN** 两个实例各生成 1 万个 ID
- **THEN** 合并去重后数量为 2 万，无重复

验收：A12

#### Scenario: 关闭框架分配时告警

- **WHEN** `fw.plus.uid.enabled=false` 且未配置 `askdata.id.worker-id`
- **THEN** 集群自检输出撞号风险告警并给出配置建议

验收：A12

## 部署基线

### Requirement: 提供可运行的集群编排与文档

应提供最小集群编排（元数据库 + Redis + 对象存储 + 两个 askdata 实例 + 负载均衡）与配套部署文档，文档至少覆盖：必备外部依赖、关键配置矩阵、粘性会话要求、连接池容量换算、对象存储必填项与降级行为。

#### Scenario: 一键起集群并跑通探针

- **WHEN** 使用提供的编排启动集群，并通过负载均衡执行 `probe-cluster.mjs`
- **THEN** 探针全部通过：登录态跨实例、上传→导入跨实例、导出跨实例、图表图片跨实例、租约回收、防抖与缓存一致性

验收：A15
