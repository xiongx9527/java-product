-- =====================================================================
-- askdata 元数据库表结构（H2）
-- 从 SQLBot backend/alembic/versions 精简移植；字段名与 SQLBot DDL 一致。
--   skeleton-auth：001/010/020/021/035/049（登录闭环）
--   system-foundation：019/040/067（ai_model、ai_model_workspace_mapping）、
--     056（sys_apikey）、062/063/064（system_variable、sys_user.system_variables）
--   datasource-engine：core_datasource/core_table/core_field/ds_recommended_problem
--     （对应 SQLBot apps/datasource/models/datasource.py 的 SQLModel 表）
--   value-add-modules：042/050/051（data_training）、039/041/045/069（terminology）、
--     046/069（custom_prompt）、009_1/011（core_dashboard）、055/057/068（sys_logs）、
--     026/027/030/071（ds_permission、ds_rules）、022/032/036/061/066（sys_assistant）
-- Flyway V1 baseline：仅对空库执行一次，创建 H2 元数据库完整结构（不含种子数据）。
-- =====================================================================

CREATE TABLE sys_user (
    id          BIGINT PRIMARY KEY,
    account     VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    oid         BIGINT       NOT NULL,
    status      INT          NOT NULL,
    create_time BIGINT       NOT NULL,
    language    VARCHAR(255) NOT NULL DEFAULT 'zh-CN',
    origin      INT          NOT NULL DEFAULT 0,
    system_variables VARCHAR(10000)
);

-- alembic 063：用户绑定的系统变量值（SQLBot 为 JSONB，此处以 JSON 文本存储）

CREATE TABLE sys_workspace (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    create_time BIGINT       NOT NULL
);

CREATE TABLE sys_user_ws (
    id     BIGINT PRIMARY KEY,
    uid    BIGINT NOT NULL,
    oid    BIGINT NOT NULL,
    weight INT    NOT NULL
);

CREATE TABLE sys_arg (
    id      BIGINT PRIMARY KEY,
    pkey    VARCHAR(255) NOT NULL,
    pval    VARCHAR(255),
    ptype   VARCHAR(255) NOT NULL DEFAULT 'str',
    sort_no INT          NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------------
-- AI 模型（alembic 019/040；ai_model.api_key/api_domain/config 原为 Text）
-- ---------------------------------------------------------------------
CREATE TABLE ai_model (
    id            BIGINT PRIMARY KEY,
    api_key       VARCHAR(4000),
    api_domain    VARCHAR(4000) NOT NULL,
    protocol      INT           NOT NULL,
    supplier      INT           NOT NULL,
    name          VARCHAR(255)  NOT NULL,
    model_type    INT           NOT NULL,
    base_model    VARCHAR(255)  NOT NULL,
    default_model BOOLEAN       NOT NULL DEFAULT FALSE,
    config        VARCHAR(10000) NOT NULL DEFAULT '[]',
    status        INT           NOT NULL DEFAULT 1,
    create_time   BIGINT        NOT NULL
);

-- alembic 067：模型 × 工作空间映射
CREATE TABLE ai_model_workspace_mapping (
    id           BIGINT PRIMARY KEY,
    ai_model_id  BIGINT,
    workspace_id BIGINT
);

-- ---------------------------------------------------------------------
-- API Key（alembic 056；internal 认证用）
-- ---------------------------------------------------------------------
CREATE TABLE sys_apikey (
    id          BIGINT PRIMARY KEY,
    access_key  VARCHAR(255) NOT NULL,
    secret_key  VARCHAR(255) NOT NULL,
    create_time BIGINT       NOT NULL DEFAULT 0,
    uid         BIGINT       NOT NULL DEFAULT 0,
    status      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------------
-- 系统变量（alembic 062）：type='system' 为内置，'custom' 为自定义；
-- value 为 JSON 数组（SQLBot 为 JSONB，此处以 JSON 文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE system_variable (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    var_type    VARCHAR(128) NOT NULL,
    type        VARCHAR(128) NOT NULL,
    `value`     VARCHAR(10000),
    create_time TIMESTAMP,
    create_by   BIGINT
);

-- ---------------------------------------------------------------------
-- 数据源（SQLModel CoreDatasource；configuration 为应用 SECRET_KEY AES 密文，
-- table_relation 原 JSONB 此处以 JSON 文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE core_datasource (
    id                BIGINT PRIMARY KEY,
    name              VARCHAR(128)  NOT NULL,
    description       VARCHAR(512),
    type              VARCHAR(64),
    type_name         VARCHAR(64),
    configuration     CLOB,
    create_time       TIMESTAMP,
    create_by         BIGINT,
    status            VARCHAR(64),
    num               VARCHAR(256),
    oid               BIGINT,
    table_relation    CLOB,
    embedding         CLOB,
    recommended_config BIGINT
);

CREATE TABLE core_table (
    id             BIGINT PRIMARY KEY,
    ds_id          BIGINT,
    checked        BOOLEAN DEFAULT TRUE,
    table_name     CLOB,
    table_comment  CLOB,
    custom_comment CLOB,
    embedding      CLOB,
    -- 表级 RAG 向量（table-rag-embedding）：与 SQLBot 遗留 embedding 列分开存放
    table_embedding TEXT NULL
);

-- 表级 RAG 向量（幂等）：老库升级时补列，不影响 SQLBot 遗留 embedding 列

CREATE TABLE core_field (
    id             BIGINT PRIMARY KEY,
    ds_id          BIGINT,
    table_id       BIGINT,
    checked        BOOLEAN DEFAULT TRUE,
    field_name     CLOB,
    field_type     VARCHAR(128),
    field_comment  CLOB,
    custom_comment CLOB,
    field_index    BIGINT
);

-- 数据源推荐问题（SQLModel DsRecommendedProblem）
CREATE TABLE ds_recommended_problem (
    id            BIGINT PRIMARY KEY,
    datasource_id BIGINT,
    question      CLOB,
    remark        CLOB,
    sort          BIGINT,
    create_time   TIMESTAMP,
    create_by     BIGINT
);

-- ---------------------------------------------------------------------
-- 对话域（chat-core 子任务；字段名对齐 SQLBot apps/chat/models/chat_model.py
-- 的 Chat / ChatRecord / ChatLog 与前端 ChatInfo / ChatRecord 契约）。
-- 不在此处创建 value-add-modules 拥有的表（data_training / terminology /
-- custom_prompt 等由后续 change 集成），避免 Integration 冲突。
-- ---------------------------------------------------------------------

-- 会话（SQLBot Chat；前端以 brief 作为会话标题/摘要）
CREATE TABLE chat (
    id                 BIGINT PRIMARY KEY,
    create_by          BIGINT,
    oid                BIGINT,
    create_time        TIMESTAMP,
    update_time        TIMESTAMP,
    deleted            INT          NOT NULL DEFAULT 0,
    brief              VARCHAR(2000),
    datasource         BIGINT,
    engine_type        VARCHAR(64),
    ds_type            VARCHAR(64),
    origin             INT          NOT NULL DEFAULT 1,
    chat_type          VARCHAR(64),
    question           VARCHAR(2000),
    latest_record_time TIMESTAMP,
    brief_generate BOOLEAN DEFAULT FALSE,
    recommended_question_answer CLOB,
    recommended_question CLOB,
    recommended_generate BOOLEAN DEFAULT FALSE
);

-- 对话记录（SQLBot ChatRecord；sql_answer/chart_answer 为生成阶段的思考流，
-- data/chart/predict_data 为 JSON 文本，供 /record/{rid}/data|predict_data 读取）
CREATE TABLE chat_record (
    id                    BIGINT PRIMARY KEY,
    chat_id               BIGINT,
    question              CLOB,
    create_by             BIGINT,
    oid                   BIGINT,
    create_time           TIMESTAMP,
    update_time           TIMESTAMP,
    finish_time           TIMESTAMP,
    sql_answer            CLOB,
    sql                   CLOB,
    datasource            BIGINT,
    data                  CLOB,
    chart_answer          CLOB,
    chart                 CLOB,
    analysis              CLOB,
    analysis_thinking     CLOB,
    predict               CLOB,
    predict_content       CLOB,
    predict_data          CLOB,
    finish                BOOLEAN      NOT NULL DEFAULT FALSE,
    error                 CLOB,
    run_time              BIGINT,
    first_chat            BOOLEAN      NOT NULL DEFAULT FALSE,
    recommended_question  CLOB,
    analysis_record_id    BIGINT,
    predict_record_id     BIGINT,
    regenerate_record_id  BIGINT,
    duration              BIGINT,
    total_tokens          BIGINT,
    ai_modal_id BIGINT,
    engine_type VARCHAR(64),
    sql_exec_result CLOB,
    recommended_question_answer CLOB,
    datasource_select_answer CLOB,
    extracted_keywords CLOB,
    expanded_keywords CLOB
);

-- 对话日志（SQLBot ChatLog：操作级 token/耗时/错误；message 为 JSON 文本）
CREATE TABLE chat_log (
    id            BIGINT PRIMARY KEY,
    chat_record_id BIGINT,
    oid           BIGINT,
    create_by     BIGINT,
    start_time    TIMESTAMP,
    finish_time   TIMESTAMP,
    duration      BIGINT,
    total_tokens  BIGINT,
    operate       VARCHAR(255),
    local_operation BOOLEAN NOT NULL DEFAULT FALSE,
    error         BOOLEAN NOT NULL DEFAULT FALSE,
    message       CLOB,
    input_tokens BIGINT,
    output_tokens BIGINT,
    type VARCHAR(255),
    pid BIGINT,
    ai_modal_id BIGINT,
    base_modal VARCHAR(255),
    reasoning_content CLOB
);

-- 推荐问题（SQLBot RecommendedQuestion；与前端 recommended_question 流式帧对应）
CREATE TABLE recommended_questions (
    id          BIGINT PRIMARY KEY,
    chat_id     BIGINT,
    question    CLOB,
    sort        BIGINT,
    create_time TIMESTAMP
);

-- SQL 示例训练（alembic 042/050/051；question 向量化 embedding 按 D5 以 JSON 文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE data_training (
    id                   BIGINT PRIMARY KEY,
    oid                  BIGINT,
    datasource           BIGINT,
    create_time          BIGINT,
    question             VARCHAR(255),
    description          VARCHAR(10000),
    embedding            VARCHAR(10000),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    advanced_application BIGINT
);

-- ---------------------------------------------------------------------
-- 术语库（alembic 039/041/045/050/069；pid 非空为同义词子记录；
-- datasource_ids 原为 JSONB，此处以 JSON 数组文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE terminology (
    id                   BIGINT PRIMARY KEY,
    oid                  BIGINT,
    pid                  BIGINT,
    create_time          BIGINT,
    word                 VARCHAR(255),
    description          VARCHAR(10000),
    embedding            VARCHAR(10000),
    specific_ds          BOOLEAN      NOT NULL DEFAULT FALSE,
    datasource_ids       VARCHAR(10000),
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    advanced_application BIGINT
);

-- ---------------------------------------------------------------------
-- 自定义提示词（alembic 046/069；type ∈ GENERATE_SQL/ANALYSIS/PREDICT_DATA；
-- datasource_ids 原为 JSONB，此处以 JSON 数组文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE custom_prompt (
    id                   BIGINT PRIMARY KEY,
    oid                  BIGINT,
    type                 VARCHAR(20),
    create_time          BIGINT,
    name                 VARCHAR(255),
    prompt               VARCHAR(10000),
    specific_ds          BOOLEAN      NOT NULL DEFAULT FALSE,
    datasource_ids       VARCHAR(10000),
    advanced_application BIGINT
);

-- ---------------------------------------------------------------------
-- 仪表盘（alembic 009_1/011；folder/leaf 两级资源树 + 画布数据；
-- create_time/update_time 为秒级时间戳，与 SQLBot 前端展示约定一致）
-- ---------------------------------------------------------------------
CREATE TABLE core_dashboard (
    id                   VARCHAR(50) PRIMARY KEY,
    name                 VARCHAR(255),
    pid                  VARCHAR(50),
    workspace_id         VARCHAR(50),
    org_id               VARCHAR(50),
    level                INT,
    node_type            VARCHAR(255),
    type                 VARCHAR(50),
    canvas_style_data    VARCHAR(10000),
    component_data       VARCHAR(10000),
    canvas_view_info     VARCHAR(10000),
    mobile_layout        SMALLINT,
    status               INT,
    self_watermark_status INT,
    sort                 INT,
    create_time          BIGINT,
    create_by            VARCHAR(255),
    update_time          BIGINT,
    update_by            VARCHAR(255),
    remark               VARCHAR(255),
    source               VARCHAR(255),
    delete_flag          SMALLINT,
    delete_time          BIGINT,
    delete_by            VARCHAR(255),
    version              INT,
    content_id           VARCHAR(50),
    check_version        VARCHAR(50)
);

-- ---------------------------------------------------------------------
-- 审计日志（alembic 055/057/068；移植 SQLBot common/audit LogConfig 写入约定；
-- create_time 为毫秒时间戳）
-- ---------------------------------------------------------------------
CREATE TABLE sys_logs (
    id               BIGINT PRIMARY KEY,
    operation_type   VARCHAR(255),
    operation_detail VARCHAR(10000),
    user_id          BIGINT,
    user_name        VARCHAR(255),
    operation_status VARCHAR(255),
    oid              BIGINT,
    ip_address       VARCHAR(255),
    user_agent       VARCHAR(500),
    execution_time   BIGINT,
    error_message    VARCHAR(10000),
    create_time      BIGINT,
    module           VARCHAR(255),
    remark           VARCHAR(255),
    resource_id      VARCHAR(10000),
    resource_name    VARCHAR(10000),
    request_method   VARCHAR(255),
    request_path     VARCHAR(255)
);

-- 审计资源关联表（对齐 SQLBot sys_logs_resource）：每条审计记录关联的操作资源行
CREATE TABLE sys_logs_resource (
    id            BIGINT PRIMARY KEY,
    log_id        BIGINT,
    resource_id   VARCHAR(10000),
    resource_name VARCHAR(10000),
    module        VARCHAR(255)
);

-- ---------------------------------------------------------------------
-- 数据权限（alembic 026/027/030/071；ds_rules 为规则组，
-- permission_list/user_list 原为 JSONB，此处以 JSON 文本存储；
-- 行/列权限在问数执行时的过滤由 datasource-engine/chat-core 消费本表配置）
-- ---------------------------------------------------------------------
CREATE TABLE ds_permission (
    id               BIGINT PRIMARY KEY,
    enable           BOOLEAN,
    auth_target_type VARCHAR(128),
    auth_target_id   BIGINT,
    type             VARCHAR(64),
    ds_id            BIGINT,
    table_id         BIGINT,
    expression_tree  VARCHAR(10000),
    permissions      VARCHAR(10000),
    white_list_user  VARCHAR(10000),
    create_time      BIGINT,
    name             VARCHAR(128)
);

CREATE TABLE ds_rules (
    id              BIGINT PRIMARY KEY,
    enable          BOOLEAN,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    permission_list VARCHAR(10000),
    user_list       VARCHAR(10000),
    white_list_user VARCHAR(10000),
    create_time     BIGINT,
    oid             BIGINT
);

-- ---------------------------------------------------------------------
-- 定时问数任务（A10：cron/interval 周期问数，复用 NL2SQL+执行；
-- 导出 CSV/XLSX 或推送 webhook；任务运行历史与重试）
-- ---------------------------------------------------------------------
CREATE TABLE scheduled_task (
    id               BIGINT PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    oid              BIGINT,
    create_by        BIGINT,
    datasource_id    BIGINT,
    question         VARCHAR(2000),
    cron             VARCHAR(128),
    interval_seconds BIGINT,
    export_format    VARCHAR(16),
    export_path      VARCHAR(1024),
    push_url         VARCHAR(1024),
    enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    running          BOOLEAN NOT NULL DEFAULT FALSE,
    last_run_at      BIGINT,
    next_run_at      BIGINT,
    create_time      BIGINT,
    update_time      BIGINT
);

CREATE TABLE scheduled_task_run (
    id           BIGINT PRIMARY KEY,
    task_id      BIGINT,
    oid          BIGINT,
    trigger_type VARCHAR(16),
    status       VARCHAR(16),
    sql          VARCHAR(10000),
    row_count    INT,
    export_path  VARCHAR(1024),
    error        VARCHAR(4000),
    started_at   BIGINT,
    finished_at  BIGINT,
    duration_ms  BIGINT,
    create_time  BIGINT
);

-- ---------------------------------------------------------------------
-- 小助手/嵌入应用（alembic 022/032/036/061/066；type 0=普通小助手、
-- 1=高级应用、4=页面嵌入；app_id/app_secret 为嵌入密钥）
-- ---------------------------------------------------------------------
CREATE TABLE sys_assistant (
    id                  BIGINT PRIMARY KEY,
    name                VARCHAR(255)  NOT NULL,
    domain              VARCHAR(255)  NOT NULL,
    type                INT           NOT NULL DEFAULT 0,
    configuration       VARCHAR(10000),
    create_time         BIGINT        NOT NULL DEFAULT 0,
    description         VARCHAR(10000),
    app_id              VARCHAR(255),
    app_secret          VARCHAR(255),
    oid                 BIGINT,
    enable_custom_model BOOLEAN,
    custom_model        VARCHAR(255)
);

-- ---------------------------------------------------------------------
-- askdata-sqlbot-full-parity / infra-toolchain：会话表缺失列
-- （审计资源关联表 sys_logs_resource 已在上方统一定义，此处不再重复建表；
--   会话列取自 192.168.1.100 上运行中的 SQLBot 实例 PG 库 sqlbot 的 information_schema）
-- 全部为幂等语句：ADD COLUMN IF NOT EXISTS
-- =====================================================================

-- ---------------------------------------------------------------------
-- 单 worker 启动守卫（对齐 SQLBot common/utils/distributed_lock.py 的
-- SingleWorkerGuard；H2 无 advisory lock，改用表行独占锁实现）
-- ---------------------------------------------------------------------
CREATE TABLE sys_single_worker_lock (
    lock_key   VARCHAR(255) PRIMARY KEY,
    acquired_at BIGINT,
    owner      VARCHAR(255)
);

-- ---------------------------------------------------------------------
-- 会话表缺失列（对齐 SQLBot 实际 DDL；幂等追加，既有数据不迁移）
-- ---------------------------------------------------------------------

-- A12：token 用量明细（input/output），与 total_tokens 一起支撑 /chat/record/{rid}/log 与 usage

-- =====================================================================
-- ---------------------------------------------------------------------
-- 许可证（A28：POST/GET /system/license 持久化；status valid/invalid/expired）
-- 仅保留最新一条（id 固定为 1），上传即覆盖；license 为许可证原文（JSON）。
-- ---------------------------------------------------------------------
CREATE TABLE sys_license (
    id          BIGINT PRIMARY KEY,
    license     VARCHAR(20000) NOT NULL,
    status      VARCHAR(32)    NOT NULL DEFAULT 'invalid',
    version     VARCHAR(64),
    edition     VARCHAR(64),
    corporation VARCHAR(255),
    count       INT,
    serial_no   VARCHAR(128),
    remark      VARCHAR(1000),
    isv         VARCHAR(255),
    expire_at   BIGINT,
    fail_reason VARCHAR(1000),
    create_time BIGINT          NOT NULL DEFAULT 0
);

-- askdata-sqlbot-full-parity / auth-whitelist：SSO 认证源与企业平台同步表（append）
-- =====================================================================
-- askdata-sqlbot-full-parity / auth-whitelist：SSO 认证源与企业平台同步表
-- （对应 SQLBot sys_authentication / sys_user_platform / sys_platform_token 实际 DDL，
--   取自运行实例 192.168.1.100 的 PG 库 sqlbot；本段为 append-only，幂等可重复执行）
-- =====================================================================

-- SSO 认证源（SQLBot alembic 048；type 1=CAS 2=OIDC 3=LDAP 4=OAuth2 5=SAML2 6..9=企业 IM）
CREATE TABLE sys_authentication (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    type        INT          NOT NULL,
    config      VARCHAR(10000),
    enable      BOOLEAN      NOT NULL DEFAULT FALSE,
    valid       BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time BIGINT       NOT NULL DEFAULT 0
);

-- 企业平台用户绑定（SQLBot alembic 049；uid/origin/platform_uid）
CREATE TABLE sys_user_platform (
    id          BIGINT PRIMARY KEY,
    uid         BIGINT       NOT NULL,
    origin      INT          NOT NULL DEFAULT 0,
    platform_uid VARCHAR(255) NOT NULL
);

-- 平台临时令牌（SQLBot 运行实例实际 DDL；id/token/create_time/exp_time）
CREATE TABLE sys_platform_token (
    id          BIGINT PRIMARY KEY,
    token       VARCHAR(255) NOT NULL,
    create_time BIGINT       NOT NULL,
    exp_time    BIGINT       NOT NULL
);

-- =====================================================================
-- 通用文件存储记录（x-file-storage FileRecorder 落库；对齐 SQLBot 文件存储元数据）。
-- url 唯一（存储文件名随机，冲突概率可忽略），凭 url 反查 FileInfo，重启/多实例均可用。
-- =====================================================================
CREATE TABLE file_storage (
    id                BIGINT        PRIMARY KEY,
    url               VARCHAR(2000) NOT NULL,
    platform          VARCHAR(64),
    path              VARCHAR(1000),
    filename          VARCHAR(512),
    original_filename VARCHAR(512),
    content_type      VARCHAR(255),
    size              BIGINT,
    create_time       BIGINT        NOT NULL DEFAULT 0
);

-- =====================================================================
-- 数据源使用授权（对齐 SQLBot @require_permissions(type='ds') 的资源级授权层）。
-- 管理员把工作空间内数据源授权给成员；白名单覆盖语义：某数据源存在任一授权记录时，
-- 未被授权的成员不可用该数据源建会话/问数（否则拒绝在 start 卡住）；该数据源
-- 完全没有授权记录时维持工作空间内共享（向后兼容，管理员始终放行）。
-- =====================================================================
CREATE TABLE ds_grant (
    id          BIGINT PRIMARY KEY,
    oid         BIGINT   NOT NULL,
    ds_id       BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    create_by   BIGINT,
    create_time BIGINT   NOT NULL,
    CONSTRAINT uk_ds_grant UNIQUE (oid, ds_id, user_id)
);

-- ---------------------------------------------------------------------
-- 框架 UidGenerator 的 DB 化 worker id 分配（与 baomidou DisposableWorkerIdAssigner
-- 字段对齐；askdata-server 为单数据源、未引入动态数据源，由 JdbcWorkerIdAssigner 复用主库写入）。
-- 启动时插入一行，以自增主键作为 worker id，保证集群各实例拿到不同 worker id。
-- ---------------------------------------------------------------------
CREATE TABLE WORKER_NODE (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    host_name    VARCHAR(255),
    port         VARCHAR(64),
    type         INT,
    launch_date  VARCHAR(64),
    created      TIMESTAMP,
    modified     TIMESTAMP
);
