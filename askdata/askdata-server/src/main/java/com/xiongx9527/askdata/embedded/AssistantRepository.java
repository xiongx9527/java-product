package com.xiongx9527.askdata.embedded;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 小助手/嵌入应用 sys_assistant 表 JDBC 访问层。
 * 表结构移植自 SQLBot alembic 022/032/036/061/066（字段名不变；
 * type 0=普通小助手、1=高级应用、4=页面嵌入）。
 */
@Repository
public class AssistantRepository {

    private final JdbcTemplate jdbc;

    private final com.xiongx9527.askdata.common.util.IdGenerator idGenerator;

    public AssistantRepository(JdbcTemplate jdbc,
                               com.xiongx9527.askdata.common.util.IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    private static final String COLUMNS = "id, name, type, domain, description, configuration, create_time, "
            + "app_id, app_secret, oid, enable_custom_model, custom_model";

    /** /system/assistant 列表（对齐 SQLBot query：type != 4，按名称+创建时间排序）。 */
    public List<Map<String, Object>> list(long oid) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_assistant WHERE oid = ? AND type <> 4 "
                + "ORDER BY name, create_time", oid);
    }

    /** /system/assistant/advanced_application（高级应用 type=1）。 */
    public List<Map<String, Object>> listAdvancedApplications(long oid) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_assistant WHERE type = 1 AND oid = ? "
                + "ORDER BY name, create_time", oid);
    }

    /** /system/embedded 分页（页面嵌入 type=4；关键词命中名称）。 */
    public List<Map<String, Object>> page(long oid, String keyword, int pageNum, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sys_assistant WHERE type = 4 "
                + "AND (oid = ? OR oid IS NULL)");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND name LIKE ?");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY create_time DESC LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long count(long oid, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_assistant WHERE type = 4 "
                + "AND (oid = ? OR oid IS NULL)");
        List<Object> args = new ArrayList<>();
        args.add(oid);
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND name LIKE ?");
            args.add("%" + keyword + "%");
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_assistant WHERE id = ?", id).stream().findFirst();
    }

    public long insert(String name, int type, String domain, String description, String configuration,
                       String appId, String appSecret, Long oid, boolean enableCustomModel, String customModel,
                       long createTime) {
        long id = idGenerator.nextId();
        jdbc.update("INSERT INTO sys_assistant (id, name, type, domain, description, configuration, "
                        + "create_time, app_id, app_secret, oid, enable_custom_model, custom_model) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, type, domain, description, configuration, createTime, appId, appSecret, oid,
                enableCustomModel, customModel);
        return id;
    }

    public void update(long id, String name, int type, String domain, String description, String configuration,
                       Long oid, boolean enableCustomModel, String customModel) {
        jdbc.update("UPDATE sys_assistant SET name = ?, type = ?, domain = ?, description = ?, "
                        + "configuration = ?, oid = ?, enable_custom_model = ?, custom_model = ? WHERE id = ?",
                name, type, domain, description, configuration, oid, enableCustomModel, customModel, id);
    }

    public void updateConfiguration(long id, String configuration) {
        jdbc.update("UPDATE sys_assistant SET configuration = ? WHERE id = ?", configuration, id);
    }

    public void updateAppSecret(long id, String appSecret) {
        jdbc.update("UPDATE sys_assistant SET app_secret = ? WHERE id = ?", appSecret, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sys_assistant WHERE id = ?", id);
    }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM sys_assistant WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i > 0 ? ",?" : "?");
        }
        sql.append(")");
        jdbc.update(sql.toString(), ids.toArray());
    }
}
