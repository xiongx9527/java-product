package com.xiongx9527.askdata.system.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计资源名解析（对齐 SQLBot {@code build_resource_union_query} 的 14 表 UNION）。
 *
 * <p>审计记录只存 {@code resource_id}；资源名（人可读）由本解析器按「模块 + id」
 * 跨表查询得出，并写入 {@code sys_logs_resource} 关联表（对齐 SQLBot 的
 * {@code SystemLogsResource}）。</p>
 *
 * <p>表与模块映射（与 SQLBot 逐字一致）：</p>
 * <pre>
 * ai_model       → ai_model        （name）
 * chat           → chat            （brief）
 * core_dashboard → dashboard       （name）
 * core_datasource→ datasource      （name）
 * custom_prompt  → prompt_words    （name）
 * data_training  → data_training   （question）
 * ds_permission  → permission      （name）
 * ds_rules       → rules           （name）
 * sys_user       → user / member   （name）
 * sys_workspace  → workspace       （name）
 * terminology    → terminology     （word）
 * sys_assistant  → application     （name）
 * sys_apikey     → api_key         （access_key）
 * </pre>
 */
@Component
public class AuditResourceNameResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditResourceNameResolver.class);

    /** 资源来源定义（表名 → 名称列）。 */
    private static final Map<String, String> RESOURCE_SOURCES;

    static {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ai_model", "name");
        sources.put("chat", "brief");
        sources.put("core_dashboard", "name");
        sources.put("core_datasource", "name");
        sources.put("custom_prompt", "name");
        sources.put("data_training", "question");
        sources.put("ds_permission", "name");
        sources.put("ds_rules", "name");
        sources.put("sys_user", "name");
        sources.put("sys_workspace", "name");
        sources.put("terminology", "word");
        sources.put("sys_assistant", "name");
        sources.put("sys_apikey", "access_key");
        RESOURCE_SOURCES = Collections.unmodifiableMap(sources);
    }

    private final JdbcTemplate jdbc;

    public AuditResourceNameResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析资源名。
     *
     * @param resourceId 资源 id（可为 null）
     * @param module     所属模块（{@code AuditModule} 的 code）
     * @return 资源名；未命中返回 null
     */
    public String resolve(String resourceId, String module) {
        if (resourceId == null || resourceId.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> hits = resolveAll(java.util.Collections.singletonList(resourceId), module);
        return hits.isEmpty() ? null : String.valueOf(hits.get(0).get("resource_name"));
    }

    /**
     * 批量解析资源名（对齐 SQLBot {@code get_resource_name_by_id_and_module}）。
     *
     * @param resourceIds 资源 id 列表
     * @param module      所属模块
     * @return 形如 {@code [{resource_id, resource_name, module}]} 的列表
     */
    public List<Map<String, Object>> resolveAll(List<String> resourceIds, String module) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (resourceIds == null || resourceIds.isEmpty() || module == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : RESOURCE_SOURCES.entrySet()) {
            String table = entry.getKey();
            String nameColumn = entry.getValue();
            List<String> ids = idsForModule(resourceIds, module, table);
            if (ids.isEmpty()) {
                continue;
            }
            try {
                String sql = "SELECT id, " + nameColumn + " AS resource_name FROM " + table
                        + " WHERE id IN (" + placeholders(ids.size()) + ")";
                List<Object> args = new ArrayList<>(ids);
                for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resource_id", String.valueOf(row.get("id")));
                    item.put("resource_name", row.get("resource_name") == null ? "" : row.get("resource_name"));
                    item.put("module", module);
                    result.add(item);
                }
            } catch (Exception e) {
                // 表不存在（老库未建）等一律忽略：资源名解析失败不应阻断审计
                log.debug("解析资源名失败 {}/{}: {}", table, module, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 按模块筛出该模块对应的资源 id。
     *
     * <p>SQLBot 的 UNION 里同一张表可对应多个模块（{@code sys_user} 同时对应
     * {@code user} 与 {@code member}），此处按模块映射表过滤。</p>
     */
    private static List<String> idsForModule(List<String> resourceIds, String module, String table) {
        if (module == null) {
            return Collections.emptyList();
        }
        if ("sys_user".equals(table)) {
            if (!"user".equals(module) && !"member".equals(module)) {
                return Collections.emptyList();
            }
            return resourceIds;
        }
        String expected = MODULE_TO_TABLE.get(module);
        return table.equals(expected) ? resourceIds : Collections.emptyList();
    }

    /** 模块 → 资源表（与 {@link #RESOURCE_SOURCES} 对应；user/member 共用 sys_user）。 */
    private static final Map<String, String> MODULE_TO_TABLE;

    static {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("ai_model", "ai_model");
        mapping.put("chat", "chat");
        mapping.put("dashboard", "core_dashboard");
        mapping.put("datasource", "core_datasource");
        mapping.put("prompt_words", "custom_prompt");
        mapping.put("data_training", "data_training");
        mapping.put("permission", "ds_permission");
        mapping.put("rules", "ds_rules");
        mapping.put("user", "sys_user");
        mapping.put("member", "sys_user");
        mapping.put("workspace", "sys_workspace");
        mapping.put("terminology", "terminology");
        mapping.put("application", "sys_assistant");
        mapping.put("api_key", "sys_apikey");
        MODULE_TO_TABLE = Collections.unmodifiableMap(mapping);
    }

    /** 资源表清单（诊断用）。 */
    public static List<String> resourceTables() {
        return new ArrayList<>(RESOURCE_SOURCES.keySet());
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i > 0 ? ",?" : "?");
        }
        return sb.toString();
    }
}
