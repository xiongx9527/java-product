package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.common.audit.AuditModule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 审计资源名解析（对齐 SQLBot {@code common/audit/schemas/log_utils.py:build_resource_union_query}
 * 的跨表资源名解析）。
 *
 * <p>给定 module + resourceId，按模块映射到对应业务表查询展示名（名称列）。任一映射缺失或查询失败，
 * 均安全返回 {@code null}（不抛异常、不影响审计写入）。</p>
 */
@Component
public class AuditResourceNameResolver {

    /** module -> [table, idColumn, nameColumn] */
    private static final Map<AuditModule, String[]> TABLE_MAP = Maps.ofEntries(
            Maps.entry(AuditModule.DASHBOARD, new String[]{"core_dashboard", "id", "name"}),
            Maps.entry(AuditModule.DATASOURCE, new String[]{"core_datasource", "id", "name"}),
            Maps.entry(AuditModule.PERMISSION, new String[]{"ds_permission", "id", "name"}),
            Maps.entry(AuditModule.RULES, new String[]{"ds_rules", "id", "name"}),
            Maps.entry(AuditModule.TERMINOLOGY, new String[]{"terminology", "id", "word"}),
            Maps.entry(AuditModule.DATA_TRAINING, new String[]{"data_training", "id", "question"}),
            Maps.entry(AuditModule.PROMPT_WORDS, new String[]{"custom_prompt", "id", "name"}),
            Maps.entry(AuditModule.APPLICATION, new String[]{"sys_assistant", "id", "name"}),
            Maps.entry(AuditModule.USER, new String[]{"sys_user", "id", "name"}),
            Maps.entry(AuditModule.WORKSPACE, new String[]{"sys_workspace", "id", "name"}),
            Maps.entry(AuditModule.AI_MODEL, new String[]{"ai_model", "id", "name"}),
            // sys_apikey 无 name 列，用 access_key 做展示名（先前映射到不存在的 name 列导致恒空）
            Maps.entry(AuditModule.API_KEY, new String[]{"sys_apikey", "id", "access_key"}),
            // 会话标题落在 chat.brief（rename 的写入列）；F-07 补齐此前缺失的 CHAT 映射
            Maps.entry(AuditModule.CHAT, new String[]{"chat", "id", "brief"}),

            Maps.entry(AuditModule.MEMBER, new String[]{"sys_user", "id", "name"})
    );

    private final JdbcTemplate jdbc;

    public AuditResourceNameResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String resolve(AuditModule module, String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) {
            return null;
        }
        String[] mapping = TABLE_MAP.get(module);
        if (mapping == null) {
            return null;
        }
        try {
            Object idParam = toIdParam(resourceId);
            List<String> rows = jdbc.query(
                    "SELECT " + mapping[2] + " FROM " + mapping[0] + " WHERE " + mapping[1] + " = ?",
                    (ps) -> ps.setObject(1, idParam),
                    (rs, i) -> rs.getString(1));
            if (rows.isEmpty()) {
                return null;
            }
            String name = rows.get(0);
            return name == null ? null : name;
        } catch (Exception e) {
            // 资源名解析失败不影响审计落地
            return null;
        }
    }

    private Object toIdParam(String resourceId) {
        try {
            return Long.parseLong(resourceId.trim());
        } catch (NumberFormatException e) {
            return resourceId;
        }
    }
}
