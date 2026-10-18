-- =====================================================================
-- askdata 元数据库表结构（H2）
-- 从 SQLBot backend/alembic/versions（001/010/020/021/035/049）精简移植本
-- skeleton-auth 子任务登录闭环所需表；字段名与 SQLBot DDL 一致。
-- 可重复执行：CREATE TABLE IF NOT EXISTS + MERGE 种子。
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
