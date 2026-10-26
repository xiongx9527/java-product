-- ---------------------------------------------------------------------
-- 种子数据（Flyway V2）：空库首次迁移时写入；已存在的 H2 旧库通过 baseline=2 跳过本脚本。
-- 语句按方言无关写法编写：不指定 system_variable 列名（value 为各库保留字差异点）。
-- admin 默认口令的 MD5 hex = 8f32d1e371702c1b1b7346f4b07a701d
-- ---------------------------------------------------------------------

INSERT INTO sys_user (id, account, name, password, email, oid, status, create_time, language, origin) VALUES (1, 'admin', 'Administrator', '8f32d1e371702c1b1b7346f4b07a701d', 'fit2cloud.com', 1, 1, 1672531199000, 'zh-CN', 0);

INSERT INTO sys_workspace (id, name, create_time) VALUES (1, 'i18n_default_workspace', 1672531199000);

INSERT INTO sys_user_ws (id, uid, oid, weight) VALUES (1, 1, 1, 100);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (1, 'login.initial_pwd_disabled', 'false', 'str', 1);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (2, 'login.initial_pwd_hidden', 'false', 'str', 2);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (3, 'login.default_login', '0', 'str', 3);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (4, 'login.account_login', 'true', 'str', 4);

-- chat 组参数（/system/parameter 与 /system/parameter/chat 读取；SQLBot 由 xpack
-- 配置初始化，此处按前端参数页消费的 key 落种子）
INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (5, 'chat.sqlbot_name', 'SQLBot', 'str', 5);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (6, 'chat.hide_thinking_block', 'false', 'str', 6);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (7, 'chat.expand_thinking_block', 'false', 'str', 7);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (8, 'chat.limit_rows', 'true', 'str', 8);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (9, 'chat.show_sql', 'false', 'str', 9);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (10, 'chat.show_log', 'false', 'str', 10);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (11, 'chat.context_record_count', '3', 'str', 11);

INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (12, 'chat.rows_of_data', '100', 'str', 12);

-- 系统变量内置三项（alembic 062 种子；id 1-3 前端变量页置灰不可选）
INSERT INTO system_variable VALUES (1, 'i18n_variable.name', 'text', 'system', '["name"]', NULL, NULL);

INSERT INTO system_variable VALUES (2, 'i18n_variable.account', 'text', 'system', '["account"]', NULL, NULL);

INSERT INTO system_variable VALUES (3, 'i18n_variable.email', 'text', 'system', '["email"]', NULL, NULL);

-- ---------------------------------------------------------------------
-- 演示数据源（brief D8：192.168.1.100:3306 MySQL 5.7；口令由 configuration 密文承载（不在此写入明文），
-- 演示库 askdata_demo，订单/用户/商品样例表）。configuration 为应用 SECRET_KEY
-- AES-ECB 密文（明文 JSON：host/port/username/password/database/extraJdbc/dbSchema/
-- filename/sheets/mode/timeout/lowVersion/ssl/poolSize/engine），服务端解密后使用。
-- 演示库与样例表由初始化脚本创建（D8）；本机连不通时按 SQLBot 错误约定返回连接失败，
-- 种子记录照写。表/字段元数据同步真实库结构，库可达时经 syncFields 刷新。
-- ---------------------------------------------------------------------
INSERT INTO core_datasource (id, name, description, type, type_name, configuration, create_time, create_by, status, num, oid, table_relation, embedding, recommended_config) VALUES (1, 'askdata_demo 演示库', '演示数据源：MySQL 5.7 @ 192.168.1.100，订单/用户/商品样例表',
        'mysql', 'MySQL',
        'hIpB9A0WThYnmHrUb8w1TCVGAgpvpsLG2uiGEK5f+Jl8xg3alPddR/AbO+Y7LPTAZ8P5cI3TTYxMvQXy+x72XQHj0rv/dao6K6Tvc3uZa5mamV/48NcQwWs1NapZGZSfLT18HJEAZtWeTdedRp9pzgGRXxSNscDxA26JKSMWcTelwa/od9mn4tKIVEd+zyGVL9TWup0K5EveL2BgZ6sRqGErSUNcn6sjWKAa34imG7EgsnxrXCMI9Zna4Aax1zeHhwhqW7gbIffhnRQF8eJhlfiNMrer8O7OAjFb8d6hh52xvwkZUPzyJAPWZNV8qU+AkpTzpgIeCxReBk5n/6iO1y+jBAPK7dmKVib0I98xN0Q=',
        TIMESTAMP '2026-10-18 00:00:00', 1, 'Success', '3/3', 1, NULL, NULL, 1);

-- 演示业务表（与 askdata_demo 初始化脚本的样例表对应；库可达后以 syncFields 刷新）
INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, embedding) VALUES (1, 1, TRUE, 'orders', '订单表', '订单表', NULL);

INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, embedding) VALUES (2, 1, TRUE, 'users', '用户表', '用户表', NULL);

INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, embedding) VALUES (3, 1, TRUE, 'products', '商品表', '商品表', NULL);

-- orders 字段（field_index 与初始化脚本列序一致）
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (1, 1, 1, TRUE, 'id', 'bigint', '订单ID', '订单ID', 0);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (2, 1, 1, TRUE, 'user_id', 'bigint', '下单用户ID', '下单用户ID', 1);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (3, 1, 1, TRUE, 'product_id', 'bigint', '商品ID', '商品ID', 2);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (4, 1, 1, TRUE, 'quantity', 'int', '购买数量', '购买数量', 3);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (5, 1, 1, TRUE, 'amount', 'decimal(10,2)', '订单金额', '订单金额', 4);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (6, 1, 1, TRUE, 'status', 'varchar(20)', '订单状态', '订单状态', 5);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (7, 1, 1, TRUE, 'created_at', 'datetime', '下单时间', '下单时间', 6);

-- users 字段
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (8, 1, 2, TRUE, 'id', 'bigint', '用户ID', '用户ID', 0);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (9, 1, 2, TRUE, 'name', 'varchar(50)', '用户姓名', '用户姓名', 1);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (10, 1, 2, TRUE, 'email', 'varchar(100)', '邮箱', '邮箱', 2);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (11, 1, 2, TRUE, 'phone', 'varchar(20)', '手机号', '手机号', 3);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (12, 1, 2, TRUE, 'city', 'varchar(50)', '城市', '城市', 4);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (13, 1, 2, TRUE, 'created_at', 'datetime', '注册时间', '注册时间', 5);

-- products 字段
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (14, 1, 3, TRUE, 'id', 'bigint', '商品ID', '商品ID', 0);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (15, 1, 3, TRUE, 'name', 'varchar(100)', '商品名称', '商品名称', 1);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (16, 1, 3, TRUE, 'category', 'varchar(50)', '商品分类', '商品分类', 2);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (17, 1, 3, TRUE, 'price', 'decimal(10,2)', '商品单价', '商品单价', 3);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (18, 1, 3, TRUE, 'stock', 'int', '库存数量', '库存数量', 4);
INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, field_comment, custom_comment, field_index) VALUES (19, 1, 3, TRUE, 'created_at', 'datetime', '上架时间', '上架时间', 5);
