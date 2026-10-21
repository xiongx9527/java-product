package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统参数表（sys_arg）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 035（字段名不变）；参数分组按 pkey 前缀
 * （login./chat.，与 SQLBot xpack get_group_args 的 flag 语义一致）。
 */
@Repository
public class SysArgRepository {

    private final JdbcTemplate jdbc;

    public SysArgRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "id, pkey, pval, ptype, sort_no";

    /** /system/parameter：全部参数（排除 xpack 外观类 appearance.*）。 */
    public List<Map<String, Object>> listWithoutAppearance() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_arg WHERE pkey NOT LIKE 'appearance.%' "
                + "ORDER BY sort_no, id");
    }

    /**
     * /system/appearance/ui：外观参数（pkey 以 appearance. 开头的行）。
     *
     * <p>注意：{@link #listWithoutAppearance()} 排除 appearance.* 是为通用参数列表；
     * 外观端点需要自己的查询（SQLBot xpack get_group_args('appearance') 语义）。</p>
     */
    public List<Map<String, Object>> listAppearance() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_arg WHERE pkey LIKE 'appearance.%' "
                + "ORDER BY sort_no, id");
    }

    /** /system/parameter/{login|chat}：按前缀分组。 */
    public List<Map<String, Object>> listByPrefix(String prefix) {
        return jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM sys_arg WHERE pkey LIKE ? ORDER BY sort_no, id", prefix + ".%");
    }

    public Optional<Map<String, Object>> findByPkey(String pkey) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM sys_arg WHERE pkey = ? LIMIT 1", pkey).stream()
                .findFirst();
    }

    /** 按 pkey upsert（保存参数页提交的键值）。 */
    public void upsert(long id, String pkey, String pval) {
        Optional<Map<String, Object>> existing = findByPkey(pkey);
        if (existing.isPresent()) {
            jdbc.update("UPDATE sys_arg SET pval = ? WHERE pkey = ?", pval, pkey);
            return;
        }
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no), 0) FROM sys_arg", Integer.class);
        jdbc.update("INSERT INTO sys_arg (id, pkey, pval, ptype, sort_no) VALUES (?, ?, ?, 'str', ?)",
                id, pkey, pval, (maxSort == null ? 0 : maxSort) + 1);
    }
}
