-- =====================================================================
-- askdata 元数据库表结构（H2）
-- 从 SQLBot backend/alembic/versions 精简移植；字段名与 SQLBot DDL 一致。
--   skeleton-auth：001/010/020/021/035/049（登录闭环）
--   system-foundation：019/040/067（ai_model、ai_model_workspace_mapping）、
--     056（sys_apikey）、062/063/064（system_variable、sys_user.system_variables）
--   value-add-modules：042/050/051（data_training）、039/041/045/069（terminology）、
--     046/069（custom_prompt）、009_1/011（core_dashboard）、055/057/068（sys_logs）、
--     026/027/030/071（ds_permission、ds_rules）、022/032/036/061/066（sys_assistant）
-- 可重复执行：CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS + MERGE 种子。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT PRIMARY KEY,
    account     VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    email       VARCHAR(255),
    oid         BIGINT       NOT NULL,
    status      INT          NOT NULL,
    create_time BIGINT       NOT NULL,
    language    VARCHAR(255) NOT NULL DEFAULT 'zh-CN',
    origin      INT          NOT NULL DEFAULT 0
);

-- alembic 063：用户绑定的系统变量值（SQLBot 为 JSONB，此处以 JSON 文本存储）
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS system_variables VARCHAR(10000);

CREATE TABLE IF NOT EXISTS sys_workspace (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    create_time BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS sys_user_ws (
    id     BIGINT PRIMARY KEY,
    uid    BIGINT NOT NULL,
    oid    BIGINT NOT NULL,
    weight INT    NOT NULL
);

CREATE TABLE IF NOT EXISTS sys_arg (
    id      BIGINT PRIMARY KEY,
    pkey    VARCHAR(255) NOT NULL,
    pval    VARCHAR(255),
    ptype   VARCHAR(255) NOT NULL DEFAULT 'str',
    sort_no INT          NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------------
-- AI 模型（alembic 019/040；ai_model.api_key/api_domain/config 原为 Text）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_model (
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
CREATE TABLE IF NOT EXISTS ai_model_workspace_mapping (
    id           BIGINT PRIMARY KEY,
    ai_model_id  BIGINT,
    workspace_id BIGINT
);

-- ---------------------------------------------------------------------
-- API Key（alembic 056；internal 认证用）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_apikey (
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
CREATE TABLE IF NOT EXISTS system_variable (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    var_type    VARCHAR(128) NOT NULL,
    type        VARCHAR(128) NOT NULL,
    `value`     VARCHAR(10000),
    create_time TIMESTAMP,
    create_by   BIGINT
);

-- ---------------------------------------------------------------------
-- SQL 示例训练（alembic 042/050/051；question 向量化 embedding 按 D5 以 JSON 文本存储）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_training (
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
CREATE TABLE IF NOT EXISTS terminology (
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
CREATE TABLE IF NOT EXISTS custom_prompt (
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
CREATE TABLE IF NOT EXISTS core_dashboard (
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
CREATE TABLE IF NOT EXISTS sys_logs (
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

-- ---------------------------------------------------------------------
-- 数据权限（alembic 026/027/030/071；ds_rules 为规则组，
-- permission_list/user_list 原为 JSONB，此处以 JSON 文本存储；
-- 行/列权限在问数执行时的过滤由 datasource-engine/chat-core 消费本表配置）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ds_permission (
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

CREATE TABLE IF NOT EXISTS ds_rules (
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
-- 小助手/嵌入应用（alembic 022/032/036/061/066；type 0=普通小助手、
-- 1=高级应用、4=页面嵌入；app_id/app_secret 为嵌入密钥）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_assistant (
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
-- 种子数据（MERGE 语义：按主键 upsert）
-- admin 口令 SQLBot@123456 的 MD5 hex = 8f32d1e371702c1b1b7346f4b07a701d
-- ---------------------------------------------------------------------

MERGE INTO sys_user (id, account, name, password, email, oid, status, create_time, language, origin)
KEY (id)
VALUES (1, 'admin', 'Administrator', '8f32d1e371702c1b1b7346f4b07a701d', 'fit2cloud.com', 1, 1, 1672531199000, 'zh-CN', 0);

MERGE INTO sys_workspace (id, name, create_time)
KEY (id)
VALUES (1, 'i18n_default_workspace', 1672531199000);

MERGE INTO sys_user_ws (id, uid, oid, weight)
KEY (id)
VALUES (1, 1, 1, 100);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (1, 'login.initial_pwd_disabled', 'false', 'str', 1);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (2, 'login.initial_pwd_hidden', 'false', 'str', 2);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (3, 'login.default_login', '0', 'str', 3);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (4, 'login.account_login', 'true', 'str', 4);

-- chat 组参数（/system/parameter 与 /system/parameter/chat 读取；SQLBot 由 xpack
-- 配置初始化，此处按前端参数页消费的 key 落种子）
MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (5, 'chat.sqlbot_name', 'SQLBot', 'str', 5);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (6, 'chat.hide_thinking_block', 'false', 'str', 6);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (7, 'chat.expand_thinking_block', 'false', 'str', 7);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (8, 'chat.limit_rows', 'false', 'str', 8);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (9, 'chat.show_sql', 'false', 'str', 9);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (10, 'chat.show_log', 'false', 'str', 10);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (11, 'chat.context_record_count', '5', 'str', 11);

MERGE INTO sys_arg (id, pkey, pval, ptype, sort_no)
KEY (id)
VALUES (12, 'chat.rows_of_data', '100', 'str', 12);

-- 系统变量内置三项（alembic 062 种子；id 1-3 前端变量页置灰不可选）
MERGE INTO system_variable (id, name, var_type, type, `value`, create_time, create_by)
KEY (id)
VALUES (1, 'i18n_variable.name', 'text', 'system', '["name"]', NULL, NULL);

MERGE INTO system_variable (id, name, var_type, type, `value`, create_time, create_by)
KEY (id)
VALUES (2, 'i18n_variable.account', 'text', 'system', '["account"]', NULL, NULL);

MERGE INTO system_variable (id, name, var_type, type, `value`, create_time, create_by)
KEY (id)
VALUES (3, 'i18n_variable.email', 'text', 'system', '["email"]', NULL, NULL);
