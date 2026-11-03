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

    /**
     * 模型配置写版本号：任何写操作自增。
     *
     * <p>供 {@code ChatModelRouter} 的解析缓存判断「配置是否变过」。缓存以版本号为准而不是
     * 依赖调用方记得失效——绕过服务层直接经仓储写入（测试夹具、数据修复脚本）也能立即生效。</p>
     */
    private final java.util.concurrent.atomic.AtomicLong writeVersion =
            new java.util.concurrent.atomic.AtomicLong();

    public AiModelRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** 当前写版本号（缓存键）。 */
    public long writeVersion() {
        return writeVersion.get();
    }

    private static final String COLUMNS =
            "id, api_key, api_domain, protocol, supplier, name, model_type, base_model, default_model, "
                    + "config, status, create_time, enable, upstream_format, dimension";

    /** /system/aimodel 列表（含 ws 映射计数，默认模型优先）。 */
    public List<Map<String, Object>> list(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.name, m.model_type, m.base_model, m.supplier, m.protocol, m.default_model, "
                        + "m.enable, m.upstream_format, m.dimension, "
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

    /** 指定类型的默认模型（default_model 的唯一性由 model_type 维度保证）。 */
    public Optional<Map<String, Object>> findDefault(int modelType) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_model WHERE default_model = TRUE AND model_type = ?",
                modelType).stream().findFirst();
    }

    /** 指定类型的模型列表（按创建时间升序，供无默认时回退首个可用模型）。 */
    public List<Map<String, Object>> listByType(int modelType) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ai_model WHERE model_type = ? ORDER BY create_time, id",
                modelType);
    }

    public long countByType(int modelType) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_model WHERE model_type = ?", Long.class, modelType);
        return count == null ? 0L : count;
    }

    public void insert(long id, String apiKey, String apiDomain, int protocol, int supplier, String name,
                       int modelType, String baseModel, boolean defaultModel, String config, int status,
                       long createTime, boolean enable, int upstreamFormat, Integer dimension) {
        jdbc.update("INSERT INTO ai_model (id, api_key, api_domain, protocol, supplier, name, model_type, "
                        + "base_model, default_model, config, status, create_time, enable, upstream_format, "
                        + "dimension) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, apiKey, apiDomain, protocol, supplier, name, modelType, baseModel, defaultModel, config,
                status, createTime, enable, upstreamFormat, dimension);
        writeVersion.incrementAndGet();
    }

    /**
     * 兼容旧调用方的插入：新列取默认值（enable=1、upstream_format=1、dimension 空）。
     * 主要用于测试夹具与历史代码路径；正式写入请使用带状态参数的版本。
     */
    public void insert(long id, String apiKey, String apiDomain, int protocol, int supplier, String name,
                       int modelType, String baseModel, boolean defaultModel, String config, int status,
                       long createTime) {
        insert(id, apiKey, apiDomain, protocol, supplier, name, modelType, baseModel, defaultModel, config,
                status, createTime, true, 1, null);
    }

    public void update(long id, String apiKey, String apiDomain, int protocol, int supplier, String name,
                       int modelType, String baseModel, boolean defaultModel, String config,
                       boolean enable, int upstreamFormat, Integer dimension) {
        jdbc.update("UPDATE ai_model SET api_key = ?, api_domain = ?, protocol = ?, supplier = ?, name = ?, "
                        + "model_type = ?, base_model = ?, default_model = ?, config = ?, enable = ?, "
                        + "upstream_format = ?, dimension = ? WHERE id = ?",
                apiKey, apiDomain, protocol, supplier, name, modelType, baseModel, defaultModel, config,
                enable, upstreamFormat, dimension, id);
        writeVersion.incrementAndGet();
    }

    /** 连通校验成功后回写探测到的向量维度。 */
    public void updateDimension(long id, Integer dimension) {
        jdbc.update("UPDATE ai_model SET dimension = ? WHERE id = ?", dimension, id);
        writeVersion.incrementAndGet();
    }

    /** 启用状态回写（enable 同时承载「已校验可用」与「是否生效」，连通校验通过即置 1、失败置 0）。 */
    public void updateEnable(long id, boolean enable) {
        jdbc.update("UPDATE ai_model SET enable = ? WHERE id = ?", enable, id);
        writeVersion.incrementAndGet();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM ai_model WHERE id = ?", id);
        writeVersion.incrementAndGet();
    }

    /** 只清除同一类型的默认标记，避免 LLM 与向量模型互相顶掉。 */
    public void clearDefaults(int modelType) {
        jdbc.update("UPDATE ai_model SET default_model = FALSE WHERE default_model = TRUE AND model_type = ?",
                modelType);
        writeVersion.incrementAndGet();
    }

    public void setDefault(long id) {
        jdbc.update("UPDATE ai_model SET default_model = TRUE WHERE id = ?", id);
        writeVersion.incrementAndGet();
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

    /**
     * 模型在指定工作空间是否可用：显式绑定到该空间，或该类型的默认模型
     * （对齐 SQLBot「工作空间模型列表 = 绑定模型 ∪ 默认模型」的语义）。
     */
    public boolean isAvailableInWorkspace(long modelId, long wsId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM ai_model WHERE id = ? AND (default_model = TRUE OR id IN "
                        + "(SELECT ai_model_id FROM ai_model_workspace_mapping WHERE workspace_id = ?))",
                modelId, wsId);
        return !rows.isEmpty();
    }

    /** 指定工作空间内可用（启用 + 已校验）的模型数，用于判定「无默认时是否唯一」。 */
    public int countAvailableInWorkspace(long wsId, int modelType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_model WHERE model_type = ? AND enable = TRUE "
                        + "AND (default_model = TRUE OR id IN "
                        + "(SELECT ai_model_id FROM ai_model_workspace_mapping WHERE workspace_id = ?))",
                Integer.class, modelType, wsId);
        return count == null ? 0 : count;
    }

    /**
     * /system/aimodel/list/by_ws：指定工作空间可用模型（默认模型可选并入）。
     *
     * <p>候选口径：指定类型 + 已启用；工作空间绑定模型与（可选）该类型默认模型取并集。</p>
     */
    public List<Map<String, Object>> listByWorkspace(long wsId, int modelType, boolean withDefault) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, default_model, supplier FROM ai_model WHERE model_type = ? "
                        + "AND enable = TRUE AND (id IN "
                        + "(SELECT ai_model_id FROM ai_model_workspace_mapping WHERE workspace_id = ?)");
        List<Object> args = new ArrayList<>();
        args.add(modelType);
        args.add(wsId);
        if (withDefault) {
            sql.append(" OR default_model = TRUE");
        }
        sql.append(") ORDER BY default_model DESC, name");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
}
