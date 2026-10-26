# 元数据库与 Flyway 迁移（A9）

askdata 的元数据库（登录、会话、数据源、权限等系统表）由 Flyway 版本化迁移统一管理。
H2、PostgreSQL、MySQL 均可从**空库**启动：Flyway 先执行当前方言 baseline，再写入 common 种子。
运行期不再执行 `schema.sql`（文件已删除，`spring.sql.init.mode=never`）。

## 迁移脚本布局

```text
askdata/askdata-server/src/main/resources/db/migration/
├── common/
│   └── V2__seed.sql            # 方言无关种子：管理员、工作空间、系统参数、演示数据源/表/字段
├── h2/
│   └── V1__baseline.sql        # H2 完整建表（35 张表）
├── postgresql/
│   └── V1__baseline.sql        # PostgreSQL 完整建表（TEXT / INTEGER / identity）
└── mysql/
    └── V1__baseline.sql        # MySQL 完整建表（LONGTEXT / utf8mb4）
```

`application.yaml` 的 Flyway 配置：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration/common,classpath:db/migration/{vendor}
    baseline-on-migrate: true
    baseline-version: "2"
  sql:
    init:
      mode: never
```

`{vendor}` 由 Flyway 按 JDBC URL 解析为 `h2` / `postgresql` / `mysql`，因此切换库不需要改 SQL 路径。

### 空库与旧 H2 库

- **空库**：没有 `flyway_schema_history` 且没有业务表 → 执行 `V1__baseline.sql` 后再执行
  `V2__seed.sql`，迁移版本到达 v2。
- **schema.sql 时代的旧 H2 库**：已有业务表但没有 Flyway 历史 → `baseline-on-migrate` 以
  **v2** 为基线，只创建 `flyway_schema_history` 并标记 V1/V2 已应用，不会重复建表或重复插入种子。
- 新环境不要手工建表，只需提供空数据库/空 schema。

## 切换元数据库

### H2（默认）

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/askdata;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
```

### PostgreSQL

先创建空库（示例）：

```sql
CREATE DATABASE askdata;
```

启动：

```bash
SPRING_PROFILES_ACTIVE=postgresql \
ASKDATA_DB_URL='jdbc:postgresql://127.0.0.1:5432/askdata' \
ASKDATA_DB_USERNAME=askdata \
ASKDATA_DB_PASSWORD='***' \
java -jar askdata-server.jar
```

`application-postgresql.yaml` 使用 `org.postgresql.Driver`，Flyway 自动加载
`flyway-database-postgresql` 并执行 `postgresql/V1__baseline.sql` + `common/V2__seed.sql`。

### MySQL

先创建空库（建议 `utf8mb4`）：

```sql
CREATE DATABASE askdata CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

启动：

```bash
SPRING_PROFILES_ACTIVE=mysql \
ASKDATA_DB_URL='jdbc:mysql://127.0.0.1:3306/askdata?useUnicode=true&characterEncoding=utf8&useSSL=false' \
ASKDATA_DB_USERNAME=askdata \
ASKDATA_DB_PASSWORD='***' \
java -jar askdata-server.jar
```

`application-mysql.yaml` 使用 `com.mysql.cj.jdbc.Driver`，Flyway 自动加载 `flyway-mysql`
并执行 `mysql/V1__baseline.sql` + `common/V2__seed.sql`。

> 连接串/账号/口令只通过 `ASKDATA_DB_*` 环境变量提供，仓库内不保存任何真实凭据。
> 生产环境还需要按部署要求设置 `ASKDATA_SECRET_KEY`（数据源连接串加密用的应用密钥）。

## 新增迁移的约定

- **不要修改已发布的 V1/V2**：已迁移环境会因 checksum 不一致拒绝启动。
- 新变更从 `V3__<描述>.sql` 开始；只影响单一方言的脚本放在对应方言目录，三种方言通用的
  数据变更放在 `common/`。
- `common` 脚本必须使用方言无关 SQL：避免 `INSTR`、`::`、`AUTO_INCREMENT` 等方言专有写法；
  时间列使用标准 `TIMESTAMP '...'` 字面量或 epoch 毫秒。
- baseline 只负责“空库完整结构”，后续结构变更统一走增量版本。

## 测试与验收

```bash
# H2：现有 Spring/非 Spring 测试统一通过 MetadbMigration 执行 Flyway
mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Dtest=ChatContextLoadTest test

# PostgreSQL：embedded-postgres 真库（无需 Docker / 本机 psql）
mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Dtest=PostgresMetadbMigrationTest test

# MySQL：H2 MODE=MySQL 方言单测（无真实 MySQL 实例）
mvn -pl askdata/askdata-server -Dmaven.test.skip=false -Dtest=MysqlMetadbDialectTest test
```

- `PostgresMetadbMigrationTest`：空 PostgreSQL → Flyway 建表/种子 → 种子管理员登录 →
  一次问数（SQL 生成/执行/表格数据）。
- `MysqlMetadbDialectTest`：在 H2 `MODE=MySQL` 上完整执行 MySQL baseline + common 种子，
  并静态校验 `utf8mb4` / `LONGTEXT` / 不复用 PostgreSQL identity 等方言差异。
- `MetadbMigration`（测试支持类）：把历史测试的 `schema.sql` 执行替换为同一套 Flyway 迁移，
  确保测试与运行时使用完全相同的脚本。

## WORKER_NODE

框架 UID 生成器通过 `WORKER_NODE` 表分配 worker id。框架自带的
`DisposableWorkerIdAssigner` 用 `setObject(Date)` 写时间列，PostgreSQL JDBC 无法推断类型；
项目提供 `PortableWorkerIdAssigner` 显式绑定 `String`/`Timestamp`，三种元数据库均可启动。
表结构保持 `launch_date VARCHAR(64)`、`created/modified TIMESTAMP`，兼容历史 H2 库。
