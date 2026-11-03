package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.ai.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作空间删除时的数据与向量清理。
 *
 * <p>工作空间没有外键级联：删 {@code sys_workspace} 不会带走任何业务数据。这里显式删除
 * 该空间下<b>所有 oid 维度的数据</b>——数据源/表/字段/权限、术语、SQL 示例、会话记录、
 * 定时任务、自定义提示词、规则、助手、日志、看板与模型绑定，并返回待清理的向量 id 快照。</p>
 *
 * <p>业务行必须在事务内删（与空间删除同生共死），向量只能在提交后删（跨存储无法回滚）。
 * {@code sys_user} 不删：用户是全局实体，其 oid 由调用方改到仍所属的空间。</p>
 */
@Service
public class WorkspaceCleanupService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupService.class);

    private final JdbcTemplate jdbc;

    private final EmbeddingService embeddingService;

    public WorkspaceCleanupService(JdbcTemplate jdbc, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
    }

    /**
     * 删除工作空间的业务行（事务内调用），返回待清理的向量 id 快照。
     *
     * <p>快照必须在删除前取：行删掉之后就查不到 id，改造前写入的无 payload 向量只能靠它兜底。</p>
     */
    public Map<String, List<Long>> deleteBusinessRows(long oid) {
        List<Long> dsIds = ids("SELECT id FROM core_datasource WHERE oid = ?", oid);
        List<Long> tableIds = dsIds.isEmpty() ? new ArrayList<>()
                : jdbc.queryForList("SELECT id FROM core_table WHERE ds_id IN (" + placeholders(dsIds.size()) + ")",
                        Long.class, dsIds.toArray());
        List<Long> termIds = ids("SELECT id FROM terminology WHERE oid = ?", oid);
        List<Long> trainIds = ids("SELECT id FROM data_training WHERE oid = ?", oid);

        Map<String, List<Long>> vectorIds = new LinkedHashMap<>();
        vectorIds.put("core_table", tableIds);
        vectorIds.put("core_datasource", dsIds);
        vectorIds.put("terminology", termIds);
        vectorIds.put("data_training", trainIds);

        // 会话：先删推荐问题（只有 chat_id），再删记录与主表
        List<Long> chatIds = ids("SELECT id FROM chat WHERE oid = ?", oid);
        if (!chatIds.isEmpty()) {
            deleteIn("DELETE FROM recommended_questions WHERE chat_id IN (", chatIds);
        }
        jdbc.update("DELETE FROM chat_record WHERE oid = ?", oid);
        jdbc.update("DELETE FROM chat_log WHERE oid = ?", oid);
        jdbc.update("DELETE FROM chat WHERE oid = ?", oid);

        // 审计日志：先删资源明细，再删主表
        List<Long> logIds = ids("SELECT id FROM sys_logs WHERE oid = ?", oid);
        if (!logIds.isEmpty()) {
            deleteIn("DELETE FROM sys_logs_resource WHERE log_id IN (", logIds);
        }
        jdbc.update("DELETE FROM sys_logs WHERE oid = ?", oid);

        // 数据源及其附属：权限 / 推荐问题 / 授权 / 字段 / 表
        if (!dsIds.isEmpty()) {
            deleteIn("DELETE FROM ds_permission WHERE ds_id IN (", dsIds);
            deleteIn("DELETE FROM ds_recommended_problem WHERE datasource_id IN (", dsIds);
            deleteIn("DELETE FROM ds_grant WHERE ds_id IN (", dsIds);
        }
        if (!tableIds.isEmpty()) {
            deleteIn("DELETE FROM core_field WHERE table_id IN (", tableIds);
            deleteIn("DELETE FROM core_table WHERE id IN (", tableIds);
        }
        jdbc.update("DELETE FROM core_datasource WHERE oid = ?", oid);
        jdbc.update("DELETE FROM terminology WHERE oid = ?", oid);
        jdbc.update("DELETE FROM data_training WHERE oid = ?", oid);

        // 其余 oid 维度的业务数据
        jdbc.update("DELETE FROM custom_prompt WHERE oid = ?", oid);
        jdbc.update("DELETE FROM ds_rules WHERE oid = ?", oid);
        jdbc.update("DELETE FROM ds_grant WHERE oid = ?", oid);
        jdbc.update("DELETE FROM scheduled_task_run WHERE oid = ?", oid);
        jdbc.update("DELETE FROM scheduled_task WHERE oid = ?", oid);
        jdbc.update("DELETE FROM sys_assistant WHERE oid = ?", oid);
        // 看板与模型绑定用 workspace_id 维度
        jdbc.update("DELETE FROM core_dashboard WHERE workspace_id = ?", oid);
        jdbc.update("DELETE FROM ai_model_workspace_mapping WHERE workspace_id = ?", oid);

        log.info("工作空间 {} 数据已删除：数据源 {}，表 {}，术语 {}，SQL 示例 {}，会话 {}，日志 {}",
                oid, dsIds.size(), tableIds.size(), termIds.size(), trainIds.size(), chatIds.size(), logIds.size());
        return vectorIds;
    }

    /**
     * 事务提交后清理向量：先按 payload（{@code oid} / {@code ds_id}）删，再按 id 快照兜底。
     *
     * <p>两条路径都只记日志：残留点由后续重建清理，不影响空间删除主链路。</p>
     */
    public void deleteVectors(long oid, Map<String, List<Long>> vectorIds) {
        embeddingService.deleteEmbeddingsByPayload("terminology", "oid", oid);
        embeddingService.deleteEmbeddingsByPayload("data_training", "oid", oid);
        embeddingService.deleteEmbeddingsByPayload("core_datasource", "oid", oid);
        embeddingService.deleteEmbeddingsByPayload("core_table", "oid", oid);
        if (vectorIds == null) {
            return;
        }
        for (Map.Entry<String, List<Long>> entry : vectorIds.entrySet()) {
            embeddingService.deleteEmbeddings(entry.getKey(), entry.getValue());
        }
    }

    private List<Long> ids(String sql, long oid) {
        return jdbc.queryForList(sql, Long.class, oid);
    }

    /** 按 id 列表批量删除（IN 子句），空列表直接跳过。 */
    private void deleteIn(String sqlPrefix, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        jdbc.update(sqlPrefix + placeholders(ids.size()) + ")", ids.toArray());
    }

    private static String placeholders(int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }
}
