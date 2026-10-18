-- =====================================================================
-- askdata 元数据库表结构（H2）
-- 从 SQLBot backend/alembic/versions 精简移植；字段名与 SQLBot DDL 一致。
--   skeleton-auth：001/010/020/021/035/049（登录闭环）
--   system-foundation：019/040/067（ai_model、ai_model_workspace_mapping）、
--     056（sys_apikey）、062/063/064（system_variable、sys_user.system_variables）
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
