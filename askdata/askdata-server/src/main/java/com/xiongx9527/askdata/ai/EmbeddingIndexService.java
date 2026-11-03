package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.startup.EmbeddingBackfillRunner;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.repository.AiEmbeddingIndexStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量索引重建（全空间、强制重算、状态持久化）。
 *
 * <p>默认向量模型是全局配置，而向量按工作空间存储，因此切换默认向量模型后必须对
 * <b>全部工作空间</b>的术语、SQL 示例、表、数据源四类向量全量重算。重建状态写入
 * {@code ai_embedding_index_state}，刷新页面或重启服务后仍然可见。</p>
 */
@Service
public class EmbeddingIndexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);

    private final JdbcTemplate jdbc;

    private final ChatModelRouter modelRouter;

    private final EmbeddingBackfillRunner backfillRunner;

    private final AiEmbeddingIndexStateRepository stateRepository;

    private final SingleWorkerGuard singleWorkerGuard;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "embedding-index-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 向量服务：重建前清空在线集合。Spring 装配注入；直接构造（单测）时可缺省，
     * 缺省时重建仍会重算全部向量，但集合维度不一致会显式失败而不是静默删集合。
     */
    private EmbeddingService embeddingService;

    /** Spring 装配：重建前清理在线集合与维度缓存（直接构造的单测可缺省）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public EmbeddingIndexService(JdbcTemplate jdbc, ChatModelRouter modelRouter,
                                 EmbeddingBackfillRunner backfillRunner,
                                 AiEmbeddingIndexStateRepository stateRepository,
                                 SingleWorkerGuard singleWorkerGuard) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.backfillRunner = backfillRunner;
        this.stateRepository = stateRepository;
        this.singleWorkerGuard = singleWorkerGuard;
    }

    /**
     * 索引状态：是否待重建、当前进度与上次结果。
     *
     * <p>待重建判定：当前默认向量模型 id 与 {@code state.model_id} 不同，或上次重建失败。</p>
     */
    public Map<String, Object> status() {
        Optional<ModelRef> model = modelRouter.embeddingModel();
        Map<String, Object> state = stateRepository.find().orElse(null);
        Long currentModelId = model.map(ref -> ref.id).orElse(null);
        Integer currentDimension = model.map(ref -> ref.dimension).orElse(null);
        Long rebuiltModelId = state == null || Rows.get(state, "model_id") == null
                ? null : Rows.lng(state, "model_id", 0L);
        Integer rebuiltDimension = state == null || Rows.get(state, "dimension") == null
                ? null : Rows.intg(state, "dimension", 0);
        String status = state == null ? "idle" : Rows.str(state, "status");
        boolean failed = "failed".equals(status);
        // 联合校验：在线集合维度必须与当前默认向量模型维度一致，否则写入会被拒（需重建）
        Map<String, Integer> storeDimensions = embeddingService == null
                ? new LinkedHashMap<String, Integer>() : embeddingService.storeDimensions();
        Integer storeDimension = null;
        boolean dimensionConsistent = true;
        for (Integer dimension : storeDimensions.values()) {
            if (storeDimension == null) {
                storeDimension = dimension;
            }
            if (currentDimension != null && !currentDimension.equals(dimension)) {
                dimensionConsistent = false;
            }
        }
        boolean storeMismatch = currentModelId != null && !dimensionConsistent;
        boolean pending = currentModelId != null
                && (rebuiltModelId == null || !rebuiltModelId.equals(currentModelId) || failed || storeMismatch);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("store_dimension", storeDimension);
        result.put("dimension_consistent", dimensionConsistent);
        result.put("status", status);
        result.put("running", running.get() || "running".equals(status));
        result.put("model_id", currentModelId);
        result.put("dimension", currentDimension);
        result.put("rebuilt_model_id", rebuiltModelId);
        result.put("rebuilt_dimension", rebuiltDimension);
        result.put("available", currentModelId != null);
        if (state != null) {
            result.put("total", Rows.intg(state, "total", 0));
            result.put("done", Rows.intg(state, "done", 0));
            result.put("failed", Rows.intg(state, "failed", 0));
            result.put("current_scope", Rows.get(state, "current_scope"));
            result.put("started_at", Rows.get(state, "started_at"));
            result.put("finished_at", Rows.get(state, "finished_at"));
            result.put("message", Rows.get(state, "message"));
        }
        return result;
    }

    /**
     * 问答入口前置校验：返回阻断原因的消息 key，{@code null} 表示可用。
     *
     * <p>两种阻断：没有任何可用在线向量模型；或默认向量模型与索引状态未对齐（待重建）。</p>
     */
    public String chatBlockReasonKey() {
        Optional<ModelRef> model = modelRouter.embeddingModel();
        if (!model.isPresent() || !model.get().usable()) {
            return "aimodel.embedding.unavailable";
        }
        if (Boolean.TRUE.equals(status().get("pending"))) {
            return "aimodel.embedding.pending_rebuild";
        }
        return null;
    }

    /**
     * 启动全空间重建。
     *
     * @return {@code {started, reason?}}；已有任务在跑时 {@code started=false, reason=任务进行中}
     */
    public Map<String, Object> rebuild() {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<ModelRef> model = modelRouter.embeddingModel();
        if (!model.isPresent()) {
            result.put("started", false);
            result.put("reason", "no_embedding_model");
            return result;
        }
        if (!running.compareAndSet(false, true)) {
            result.put("started", false);
            result.put("reason", "running");
            return result;
        }
        final ModelRef modelRef = model.get();
        try {
            executor.submit(() -> runRebuild(modelRef));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        result.put("started", true);
        result.put("model_id", modelRef.id);
        result.put("dimension", modelRef.dimension);
        return result;
    }

    /** 重建主流程：逐工作空间执行四类重算，单空间失败不中断。 */
    private void runRebuild(ModelRef model) {
        if (!singleWorkerGuard.tryAcquire(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY)) {
            log.info("向量重建未获得单 worker 守卫，跳过本次重建");
            running.set(false);
            return;
        }
        // 重建是全量重算：先删集合，避免旧维度 point 残留导致写入被拒
        if (embeddingService != null) {
            embeddingService.resetCollections();
        } else {
            log.warn("未注入 EmbeddingService，本次重建不清理旧集合（维度变化时会写入失败）");
        }
        List<Long> scopes = workspaces();
        stateRepository.markRunning(model.id, model.dimension, scopes.size());
        int done = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Long oid : scopes) {
                stateRepository.updateProgress(done, failed, String.valueOf(oid));
                try {
                    backfillRunner.rebuildWorkspace(oid);
                    done++;
                } catch (Exception e) {
                    failed++;
                    failures.add(oid + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    log.warn("工作空间 {} 向量重建失败: {}", oid, e.getMessage());
                }
                stateRepository.updateProgress(done, failed, String.valueOf(oid));
            }
            if (failed == 0) {
                stateRepository.markFinished(model.id, model.dimension, done, failed, null);
            } else {
                stateRepository.markFailed(done, failed, String.join("; ", failures));
            }
        } catch (Exception e) {
            log.warn("向量重建异常终止: {}", e.getMessage());
            stateRepository.markFailed(done, failed, e.getMessage());
        } finally {
            singleWorkerGuard.release(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY);
            running.set(false);
        }
    }

    /** 全部工作空间：三类业务表的 distinct oid ∪ sys_workspace。 */
    List<Long> workspaces() {
        Set<Long> scopes = new LinkedHashSet<>();
        for (String table : new String[] {"terminology", "data_training", "core_datasource"}) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT DISTINCT oid FROM " + table + " WHERE oid IS NOT NULL")) {
                scopes.add(Rows.lng(row, "oid", 0L));
            }
        }
        try {
            for (Map<String, Object> row : jdbc.queryForList("SELECT id FROM sys_workspace")) {
                scopes.add(Rows.lng(row, "id", 0L));
            }
        } catch (Exception e) {
            log.debug("读取 sys_workspace 失败（忽略）: {}", e.getMessage());
        }
        return new ArrayList<>(scopes);
    }
}
