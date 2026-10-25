---
name: Runtime Config capability
description: askdata 运行时可配置化能力（缓存后端 / Sa-Token 会话存储 / OpenAPI 元数据 / 启动守卫锁后端与防抖 / 接口防抖）
type: project
---

# Runtime Config 能力规格（askdata-runtime-configurable）

## 概述

askdata 把「依赖部署形态」的部分收敛为配置开关：单机无外部依赖即可运行，需要多实例共享时切到 Redis。涉及的配置面：

- 缓存后端（`: memory / redis / none`）
- Sa-Token 会话存储（`: memory / redis`）
- OpenAPI 文档元数据（title / version / description）
- 单 worker 启动守卫锁后端（`: db / redis`）与防抖窗口
- 接口防抖（enabled / window / paths）

## 配置

- askdata.cache.type：memory（默认，Caffeine 进程内）/ redis / none
- askdata.cache.max-size / askdata.cache.ttl-seconds：内存后端条目上限与存活时间
- askdata.cache.sa-token-store：memory（默认）/ redis；留空跟随 askdata.cache.type
- askdata.lock.type：db（默认，元数据库表行独占锁）/ redis（SET NX PX）
- askdata.lock.debounce-seconds：防抖窗口（默认 300），0 表示不防抖
- askdata.debounce.enabled / window-ms / paths：接口防抖开关、窗口与生效路径（Ant 风格）
- askdata.openapi.title / version / description：文档元数据；version 留空取构建版本
- spring.data.redis.*：Redis 连接（仅启用 Redis 时生效）
- sa-token.token-name：token 头名（文档与鉴权共用同一来源）

## 行为

1. **缓存后端可配置**：type=memory 时热点数据（用户信息 / API Key 信息 / 助手信息）走 Caffeine 进程内缓存；type=redis 时走 Redis 且多实例共享；type=none 时不缓存（NoOp）。Redis 模式与内存模式的 TTL 语义一致。
2. **失效覆盖变更路径**：用户改密 / 停用 / 删除 / 切工作空间、API Key 停用或删除、助手配置或密钥变更，对应缓存立即失效——失效声明挂在各 Repository 的写方法上，故所有服务层调用点自动覆盖。
3. **缓存取值语义一致**：JDBC 行是 LinkedCaseInsensitiveMap（key 大写、忽略大小写），经缓存（尤其 Redis 的 JSON 往返）会退化为普通 Map，小写直取将静默返回 null；故缓存边界统一把 key 归一为小写，使「缓存命中」与「直查数据库」两条路径取值语义一致。
4. **Redis 真正可选**：未启用 Redis 时容器内不存在任何 Redis bean，框架依赖 StringRedisTemplate 的 Redis 集群模式不会激活，应用可无 Redis 启动。
5. **Sa-Token 会话存储可配置**：sa-token-store=redis 时登录态（token / session）存 Redis，多实例共享；缺省为进程内。
6. **单 worker 守卫**：锁后端可配置（db / redis）；db 锁作用域与被保护数据一致（多实例共享同一元库时集群内一次；各实例独立库时各跑各的），redis 锁为全局。防抖窗口内不重复执行已成功的一次性任务。
7. **接口防抖**：同一「调用方 + 接口」在窗口内只放行一次，重复请求返回 429 + Retry-After；调用方按鉴权头摘要识别，无则用来源 IP；缓存异常时放行，不中断业务。
8. **OpenAPI 元数据**：标题 / 版本 / 说明可配置，版本缺省取构建版本（Maven 过滤 askdata-build.properties，随构建自动更新）；文档中的 token 头名取 sa-token.token-name。

## 验收

- 验收：A1
- 验收：A2
- 验收：A3
- 验收：A4
- 验收：A5
- 验收：A6
- 验收：A7
- 验收：A9
