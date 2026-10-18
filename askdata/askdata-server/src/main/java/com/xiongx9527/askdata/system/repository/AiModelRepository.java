package com.xiongx9527.askdata.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 模型表（ai_model / ai_model_workspace_mapping）JDBC 访问层。
 * 表结构移植自 SQLBot alembic 019/040/067（字段名不变）。
 */
@Repository
public class AiModelRepository {

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public AiModelRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    private static final String COLUMNS =
            "id, api_key, api_domain, protocol, supplier, name, model_type, base_model, default_model, "
                    + "config, status, create_time";

    /** /system/aimodel 列表（含 ws 映射计数，默认模型优先）。 */
    public List<Map<String, Object>> list(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.name, m.model_type, m.base_model, m.supplier, m.protocol, m.default_model, "
                        + "COALESCE(c.cnt, 0) AS ws_mapping_count FROM ai_model m "
                        + "LEFT JOIN (SELECT ai_model_id, COUNT(*) AS cnt FROM ai_model_workspace_mapping "
                        + "GROUP BY ai_model_id) c ON m.id = c.ai_model_id");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" WHERE m.name LIKE ?");
            args.add("%" + UserRepository.escapeLike(keyword) + "%");
        }
        sql.append(" ORDER BY m.default_model DESC, m.name, m.create_time");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_model WHERE id = ?", id).stream().findFirst();
    }

    public Optional<Map<String, Object>> findDefault() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_model WHERE default_model = TRUE LIMIT 1")
                .stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_model", Long.class);
        return count == null ? 0L : count;
    }

    public void insert(long id, String apiKey, String apiDomain, int protocol, int supplier, String name,
                       int modelType, String baseModel, boolean defaultModel, String config, int status,
                       long createTime) {
        jdbc.update("INSERT INTO ai_model (id, api_key, api_domain, protocol, supplier, name, model_type, "
                        + "base_model, default_model, config, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, apiKey, apiDomain, protocol, supplier, name, modelType, baseModel, defaultModel, config,
                status, createTime);
    }

    public void update(long id, String apiKey, String apiDomain, int protocol, int supplier, String name,
                       int modelType, String baseModel, boolean defaultModel, String config) {
        jdbc.update("UPDATE ai_model SET api_key = ?, api_domain = ?, protocol = ?, supplier = ?, name = ?, "
                        + "model_type = ?, base_model = ?, default_model = ?, config = ? WHERE id = ?",
                apiKey, apiDomain, protocol, supplier, name, modelType, baseModel, defaultModel, config, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM ai_model WHERE id = ?", id);
    }

    public void clearDefaults() {
        jdbc.update("UPDATE ai_model SET default_model = FALSE WHERE default_model = TRUE");
    }

    public void setDefault(long id) {
        jdbc.update("UPDATE ai_model SET default_model = TRUE WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // 模型 × 工作空间映射（ws_mapping）
    // ------------------------------------------------------------------

    public List<Long> mappingIds(long modelId) {
        return jdbc.queryForList(
                "SELECT DISTINCT workspace_id FROM ai_model_workspace_mapping WHERE ai_model_id = ?",
                Long.class, modelId);
    }

    public List<Long> existingMappingIds(long modelId, List<Long> wsIds) {
        if (wsIds == null || wsIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = UserRepository.placeholders(wsIds.size());
        List<Object> args = new ArrayList<>();
        args.add(modelId);
        args.addAll(wsIds);
        return jdbc.queryForList(
                "SELECT DISTINCT workspace_id FROM ai_model_workspace_mapping WHERE ai_model_id = ? "
                        + "AND workspace_id IN (" + placeholders + ")", Long.class, args.toArray());
    }

    public void replaceMappings(long modelId, List<Long> wsIds) {
        jdbc.update("DELETE FROM ai_model_workspace_mapping WHERE ai_model_id = ?", modelId);
        for (Long wsId : wsIds) {
            jdbc.update("INSERT INTO ai_model_workspace_mapping (id, ai_model_id, workspace_id) VALUES (?, ?, ?)",
                    idGenerator.nextId(), modelId, wsId);
        }
    }

    public void addMapping(long modelId, long wsId) {
        jdbc.update("INSERT INTO ai_model_workspace_mapping (id, ai_model_id, workspace_id) VALUES (?, ?, ?)",
                idGenerator.nextId(), modelId, wsId);
    }

    public void deleteMappings(long modelId, List<Long> wsIds) {
        if (wsIds == null || wsIds.isEmpty()) {
            return;
        }
        String placeholders = UserRepository.placeholders(wsIds.size());
        List<Object> args = new ArrayList<>();
        args.add(modelId);
        args.addAll(wsIds);
        jdbc.update("DELETE FROM ai_model_workspace_mapping WHERE ai_model_id = ? AND workspace_id IN ("
                + placeholders + ")", args.toArray());
    }

    public void deleteAllMappings(long modelId) {
        jdbc.update("DELETE FROM ai_model_workspace_mapping WHERE ai_model_id = ?", modelId);
    }

    /** /system/aimodel/list/by_ws：指定工作空间可用模型（默认模型可选并入）。 */
    public List<Map<String, Object>> listByWorkspace(long wsId, boolean withDefault) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, default_model, supplier FROM ai_model WHERE id IN "
                        + "(SELECT ai_model_id FROM ai_model_workspace_mapping WHERE workspace_id = ?)");
        if (withDefault) {
            sql.append(" OR default_model = TRUE");
        }
        sql.append(" ORDER BY default_model DESC");
        return jdbc.queryForList(sql.toString(), wsId);
    }
}
