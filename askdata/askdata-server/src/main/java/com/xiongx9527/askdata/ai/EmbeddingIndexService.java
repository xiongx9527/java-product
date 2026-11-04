package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.startup.EmbeddingBackfillRunner;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.repository.AiEmbeddingIndexStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
 *
 * <p>重建可控性（V7）：支持中途停止、继续（resume）、仅重试失败（retry-failed），
 * 以及服务崩溃后的孤儿状态自愈（启动时把遗留的 running 修正为 interrupted）。</p>
 */
@Service
public class EmbeddingIndexService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);

    /** 重建模式：全量（清集合 + 重算）、继续、仅重试失败空间。 */
    static final String MODE_FULL = "full";
    static final String MODE_RESUME = "resume";
    static final String MODE_RETRY_FAILED = "retry-failed";

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

    /** 当前是否有重建任务在执行（进程内真值，崩溃后随进程消失）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 停止信号：runRebuild 每空间前轮询，为真则本次重建在下一个空间前终止。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

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

    /** Spring 装配：取当前登录用户以记录操作人（直接构造的单测可缺省）。 */
    private CurrentUserService currentUserService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCurrentUserService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
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
     * 启动后自愈：若 DB 残留 {@code status='running'} 但本进程并无活动重建线程，说明上次进程
     * 在重建中途崩溃，需将其修正为 {@code interrupted}，避免前端永久「重建中」、问答静默返回缺数据结果。
     */
    @Override
    public void run(ApplicationArguments args) {
        selfHealOnStartup();
    }

    /**
     * 索引状态：是否待重建、当前进度与上次结果。
     *
     * <p>待重建判定：当前默认向量模型 id 与 {@code state.model_id} 不同，或上次重建失败/中断。</p>
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
        boolean failedOrInterrupted = "failed".equals(status) || "interrupted".equals(status);
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
                && (rebuiltModelId == null || !rebuiltModelId.equals(currentModelId)
                    || failedOrInterrupted || storeMismatch);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("store_dimension", storeDimension);
        result.put("dimension_consistent", dimensionConsistent);
        result.put("status", status);
        // running 以进程内标记为主（崩溃恢复已把孤儿 running 修正为 interrupted，不会误报）
        result.put("running", running.get() || "running".equals(status));
        result.put("interrupted", "interrupted".equals(status));
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
            result.put("failed_scopes", Rows.get(state, "failed_scopes"));
            result.put("mode", Rows.get(state, "mode"));
            result.put("stop_requested", Rows.bool(state, "stop_requested", false));
            result.put("operator", Rows.get(state, "operator"));
            long startedAt = Rows.lng(state, "started_at", 0L);
            long finishedAt = Rows.lng(state, "finished_at", 0L);
            long durationMs = 0L;
            if (startedAt > 0L) {
                long end = "running".equals(status) ? System.currentTimeMillis()
                        : (finishedAt > 0L ? finishedAt : System.currentTimeMillis());
                durationMs = end - startedAt;
            }
            result.put("duration_ms", durationMs);
        }
        return result;
    }

    /**
     * 问答入口前置校验：返回阻断原因的消息 key，{@code null} 表示可用。
     *
     * <p>两种阻断：没有任何可用在线向量模型；或默认向量模型与索引状态未对齐（待重建，含中断）。</p>
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
     * 重建是否进行中（含待重建）：用于「重建期间锁定向量默认模型操作」的后端硬拦截。
     */
    public boolean isRebuildActive() {
        Map<String, Object> s = status();
        return Boolean.TRUE.equals(s.get("running")) || Boolean.TRUE.equals(s.get("pending"));
    }

    /**
     * 启动重建。
     *
     * @param mode {@code full}（默认，清集合全量）/ {@code resume}（从 last 进度续做）/ {@code retry-failed}（仅失败空间）
     * @return {@code {started, reason?, mode?}}；已有任务在跑或无可重试项时 {@code started=false}
     */
    public Map<String, Object> rebuild(String mode) {
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
        Map<String, Object> state = stateRepository.find().orElse(null);
        String status = state == null ? "idle" : Rows.str(state, "status");
        Integer stateDim = state == null ? null : Rows.intg(state, "dimension", 0);
        // 维度变化：必须 full（不清集合时维度不一致会写入失败）
        boolean dimensionChanged = stateDim != null && modelRef.dimension != null
                && !stateDim.equals(modelRef.dimension);

        String effectiveMode = MODE_FULL;
        if (!dimensionChanged) {
            if (MODE_RESUME.equals(mode)) {
                Object scope = state == null ? null : Rows.get(state, "current_scope");
                effectiveMode = (status.equals("interrupted") || status.equals("failed") || scope != null)
                        ? MODE_RESUME : MODE_FULL;
            } else if (MODE_RETRY_FAILED.equals(mode)) {
                String failedScopes = state == null ? null : Rows.str(state, "failed_scopes");
                if (failedScopes == null || failedScopes.trim().isEmpty()) {
                    running.set(false);
                    result.put("started", false);
                    result.put("reason", "no_failed");
                    return result;
                }
                effectiveMode = MODE_RETRY_FAILED;
            }
        }

        final List<Long> scopes;
        final int startIndex;
        if (MODE_RESUME.equals(effectiveMode)) {
            scopes = workspaces();
            startIndex = indexAfter(state, scopes);
        } else if (MODE_RETRY_FAILED.equals(effectiveMode)) {
            scopes = parseFailedScopes(state == null ? null : Rows.str(state, "failed_scopes"));
            startIndex = 0;
        } else {
            scopes = workspaces();
            startIndex = 0;
        }
        final int total = (state == null ? scopes.size() : Rows.intg(state, "total", scopes.size()));
        final String resolvedMode = effectiveMode;
        final String operator = currentOperator();
        stopped.set(false);
        try {
            executor.submit(() -> runRebuild(modelRef, resolvedMode, scopes, startIndex, total, operator));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        result.put("started", true);
        result.put("model_id", modelRef.id);
        result.put("dimension", modelRef.dimension);
        result.put("mode", resolvedMode);
        return result;
    }

    /**
     * 请求停止当前重建：置内存停止标志 + DB 停止信号；runRebuild 在下一个工作空间前终止并标记 interrupted。
     */
    public Map<String, Object> stop() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!running.get()) {
            result.put("stopped", false);
            result.put("reason", "not_running");
            return result;
        }
        stopped.set(true);
        stateRepository.requestStop();
        result.put("stopped", true);
        return result;
    }

    /**
     * 取消未完成的重建记录：若正在重建先中断；随后以当前默认向量模型为基线置 idle，
     * 释放「重建期间锁定默认向量模型」约束，使用户可继续设置/编辑默认向量模型。
     */
    public Map<String, Object> cancel() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (running.get()) {
            stopped.set(true);
            stateRepository.requestStop();
            result.put("canceled", false);
            result.put("stopped", true);
            return result;
        }
        Optional<ModelRef> model = modelRouter.embeddingModel();
        Long currentModelId = model.map(ref -> ref.id).orElse(null);
        Integer currentDimension = model.map(ref -> ref.dimension).orElse(null);
        stateRepository.markCanceled(currentModelId, currentDimension, currentOperator());
        result.put("canceled", true);
        return result;
    }

    /** 崩溃恢复：孤儿 running 状态转为 interrupted。 */
    public void selfHealOnStartup() {
        try {
            Map<String, Object> state = stateRepository.find().orElse(null);
            if (state == null) {
                return;
            }
            String status = Rows.str(state, "status");
            if ("running".equals(status) && !running.get()) {
                // 进程上次在重建中途崩溃：DB 仍标记 running，但本进程无活动线程
                stateRepository.markInterrupted(Rows.intg(state, "done", 0), Rows.intg(state, "failed", 0));
                log.info("检测到向量重建孤儿状态（status=running 但本进程无重建线程），已自愈为 interrupted");
            }
        } catch (Exception e) {
            log.warn("向量重建自愈检查失败（忽略）: {}", e.getMessage());
        }
    }

    /** 重建主流程：逐工作空间执行四类重算，单空间失败不中断；支持停止与续做。 */
    private void runRebuild(ModelRef model, String mode, List<Long> scopes, int startIndex, int total, String operator) {
        if (!singleWorkerGuard.tryAcquire(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY)) {
            log.info("向量重建未获得单 worker 守卫，跳过本次重建");
            running.set(false);
            return;
        }
        boolean reset = MODE_FULL.equals(mode);
        if (reset) {
            if (embeddingService != null) {
                embeddingService.resetCollections();
            } else {
                log.warn("未注入 EmbeddingService，本次重建不清理旧集合（维度变化时会写入失败）");
            }
        }
        if (MODE_FULL.equals(mode)) {
            stateRepository.markRunning(model.id, model.dimension, total, MODE_FULL, operator);
        } else {
            stateRepository.markRunningResuming(model.id, model.dimension, total, mode, operator);
        }
        int done = currentDone();
        // retry-failed 只重试失败空间：failed 重新计数，仅保留本次仍失败的空间；
        // 其余模式（full/resume）沿用既有进度（full 已由 markRunning 归零，resume 保留中断前计数）
        int failed = MODE_RETRY_FAILED.equals(mode) ? 0 : currentFailed();
        List<Long> failedOids = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean stoppedByUser = false;
        try {
            for (int i = startIndex; i < scopes.size(); i++) {
                if (stopped.get()) {
                    stoppedByUser = true;
                    break;
                }
                Long oid = scopes.get(i);
                stateRepository.updateProgress(done, failed, String.valueOf(oid));
                try {
                    backfillRunner.rebuildWorkspace(oid);
                    done++;
                } catch (Exception e) {
                    failed++;
                    failedOids.add(oid);
                    failures.add(oid + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    log.warn("工作空间 {} 向量重建失败: {}", oid, e.getMessage());
                }
                stateRepository.updateProgress(done, failed, String.valueOf(oid));
            }
            if (stoppedByUser) {
                stateRepository.markInterrupted(done, failed);
            } else if (failedOids.isEmpty()) {
                stateRepository.markFinished(model.id, model.dimension, done, failed, null);
            } else {
                stateRepository.markFailed(done, failed, String.join("; ", failures), toJson(failedOids));
            }
        } catch (Exception e) {
            log.warn("向量重建异常终止: {}", e.getMessage());
            stateRepository.markFailed(done, failed, e.getMessage(), toJson(failedOids));
        } finally {
            singleWorkerGuard.release(SingleWorkerGuard.EMBEDDING_BACKFILL_KEY);
            running.set(false);
        }
    }

    /** 读取当前 state 的已完成计数（resume / retry-failed 在已有进度上累加）。 */
    private int currentDone() {
        return stateRepository.find().map(s -> Rows.intg(s, "done", 0)).orElse(0);
    }

    private int currentFailed() {
        return stateRepository.find().map(s -> Rows.intg(s, "failed", 0)).orElse(0);
    }

    /** 当前登录用户账号（记录操作人）；无上下文或取不到时返回 null。 */
    private String currentOperator() {
        if (currentUserService == null) {
            return null;
        }
        try {
            Map<String, Object> user = currentUserService.currentUser();
            if (user == null) {
                return null;
            }
            String account = Rows.str(user, "account");
            return account != null && !account.isEmpty() ? account : Rows.str(user, "name");
        } catch (Exception e) {
            return null;
        }
    }

    /** resume：从 last 已完成空间之后续做（current_scope 为已完成的最大空间）。 */
    private int indexAfter(Map<String, Object> state, List<Long> scopes) {
        if (state == null) {
            return 0;
        }
        Object scope = Rows.get(state, "current_scope");
        if (scope == null) {
            return 0;
        }
        try {
            long last = Long.parseLong(String.valueOf(scope).trim());
            for (int i = 0; i < scopes.size(); i++) {
                if (scopes.get(i) != null && scopes.get(i).longValue() == last) {
                    // current_scope 为最后一个被触碰（可能未完成）的空间，续做时从它自身开始（幂等重算）
                    return i;
                }
            }
        } catch (NumberFormatException ignored) {
            // 无法解析则从头开始
        }
        return 0;
    }

    /** 解析 failed_scopes（JSON 数组）为 oid 列表；解析失败返回空表（调用方会退化为全量）。 */
    private List<Long> parseFailedScopes(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : node) {
                    if (n.isNumber()) {
                        result.add(n.asLong());
                    } else if (n.isTextual()) {
                        result.add(Long.parseLong(n.asText().trim()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 failed_scopes 失败（忽略，按全量重试）: {}", e.getMessage());
        }
        return result;
    }

    private String toJson(List<Long> oids) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(oids);
        } catch (Exception e) {
            return null;
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
