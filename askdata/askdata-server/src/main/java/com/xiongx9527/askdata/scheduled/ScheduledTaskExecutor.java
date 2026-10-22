package com.xiongx9527.askdata.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelClient.ModelMessage;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.chat.service.ChatModelRouter.ModelRef;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chat.service.SchemaContextBuilder;
import com.xiongx9527.askdata.chat.support.ChatExcel;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.engine.SqlSafety;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 定时问数任务执行器（A10）。
 *
 * <p>复用问数主链路：{@link SchemaContextBuilder#buildSqlMessages} 生成 SQL 提示词 →
 * {@link ChatModelClient#generate} 调用默认模型 → {@link ChatQuestionService#extractSql}
 * 抽取 SQL → {@link SqlSafety#checkRead} 只读校验 → {@link DatasourceService#execSqlAsSystem}
 * 执行。执行结果可按 {@code csv/xlsx} 导出到磁盘，或推送到 webhook；每次执行落一条
 * {@link ScheduledTaskRun} 历史，失败可由重试入口再次执行。</p>
 */
@Service
public class ScheduledTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskExecutor.class);

    private final ScheduledTaskRepository repository;

    private final DatasourceService datasourceService;

    private final SchemaContextBuilder schemaContextBuilder;

    private final ChatModelClient modelClient;

    private final ChatModelRouter modelRouter;

    private final ScheduledTaskProperties properties;

    private final ObjectMapper objectMapper;

    private final MessageResolver messageResolver;

    public ScheduledTaskExecutor(ScheduledTaskRepository repository, DatasourceService datasourceService,
                                 SchemaContextBuilder schemaContextBuilder, ChatModelClient modelClient,
                                 ChatModelRouter modelRouter, ScheduledTaskProperties properties,
                                 ObjectMapper objectMapper, MessageResolver messageResolver) {
        this.repository = repository;
        this.datasourceService = datasourceService;
        this.schemaContextBuilder = schemaContextBuilder;
        this.modelClient = modelClient;
        this.modelRouter = modelRouter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    /**
     * 执行一次任务并落运行历史。
     *
     * @param task        任务（需已 claim 或为手动执行）
     * @param triggerType SCHEDULE / MANUAL / RETRY
     * @return 已完成的运行记录（状态 SUCCESS/FAILED）
     */
    public ScheduledTaskRun execute(ScheduledTask task, String triggerType) {
        long startedAt = System.currentTimeMillis();
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setTaskId(task.getId());
        run.setOid(task.getOid());
        run.setTriggerType(triggerType);
        run.setStatus(ScheduledTaskRun.STATUS_RUNNING);
        run.setStartedAt(startedAt);
        run.setCreateTime(startedAt);
        long runId = repository.insertRun(run);

        String sql = null;
        int rowCount = 0;
        String exportPath = null;
        String error = null;
        try {
            Optional<ModelRef> model = modelRouter.defaultChatModel();
            if (!model.isPresent() || !model.get().usable()) {
                throw new IllegalStateException(messageResolver.get("scheduled.no.default.model"));
            }
            ModelRef aiModel = model.get();
            List<ModelMessage> messages =
                    schemaContextBuilder.buildSqlMessages(task.getQuestion(), task.getDatasourceId());
            String text = modelClient.generate(aiModel.apiDomain, aiModel.apiKey, aiModel.baseModel, messages);
            sql = ChatQuestionService.extractSql(text);
            if (sql == null) {
                throw new IllegalStateException(messageResolver.get("scheduled.no.sql"));
            }
            SqlSafety.checkRead(sql, datasourceService.typeAsSystem(task.getDatasourceId()).getType());
            Map<String, Object> result = datasourceService.execSqlAsSystem(task.getDatasourceId(), sql);
            rowCount = Rows.mapList(result.get("data")).size();
            exportPath = export(task, result);
            push(task, result);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("定时任务执行失败 task={}: {}", task.getId(), error);
        } finally {
            long finishedAt = System.currentTimeMillis();
            String status = error == null ? ScheduledTaskRun.STATUS_SUCCESS : ScheduledTaskRun.STATUS_FAILED;
            repository.finishRun(runId, status, sql, rowCount, exportPath, error, finishedAt,
                    finishedAt - startedAt);
            Long nextRunAt = null;
            if (task.isEnabled()) {
                try {
                    nextRunAt = ScheduledTaskSchedule.nextRunAt(task, finishedAt);
                } catch (Exception e) {
                    log.warn("计算下次执行时间失败 task={}: {}", task.getId(), e.getMessage());
                }
            }
            repository.release(task.getId(), finishedAt, nextRunAt, finishedAt);
            run.setStatus(status);
            run.setSql(sql);
            run.setRowCount(rowCount);
            run.setExportPath(exportPath);
            run.setError(error);
            run.setFinishedAt(finishedAt);
            run.setDurationMs(finishedAt - startedAt);
        }
        return run;
    }

    /** 按任务配置导出 CSV/XLSX（空格式不导出）；返回落盘路径。 */
    private String export(ScheduledTask task, Map<String, Object> result) throws Exception {
        String format = task.getExportFormat();
        if (format == null || format.trim().isEmpty() || "none".equalsIgnoreCase(format.trim())) {
            return null;
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        Object rawFields = result.get("fields");
        if (rawFields instanceof List) {
            for (Object field : (List<?>) rawFields) {
                String name = String.valueOf(field);
                fields.add(Rows.of("name", name, "value", name));
            }
        }
        List<Map<String, Object>> rows = Rows.mapList(result.get("data"));
        ChatExcel.Format excelFormat = "csv".equalsIgnoreCase(format.trim())
                ? ChatExcel.Format.CSV : ChatExcel.Format.XLSX;
        String title = task.getName() == null ? "scheduled" : task.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        ChatExcel.ExportFile file = ChatExcel.exportResult(excelFormat, fields, rows, title);
        String dir = task.getExportPath() == null || task.getExportPath().trim().isEmpty()
                ? properties.getExportDir() : task.getExportPath().trim();
        Path target = Paths.get(dir, file.filename);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, file.content);
        return target.toString();
    }

    /** 推送结果到 webhook（空地址不推送；非 2xx 视为失败以便重试）。 */
    private void push(ScheduledTask task, Map<String, Object> result) {
        String url = task.getPushUrl();
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_id", task.getId());
            payload.put("name", task.getName());
            payload.put("fields", result.get("fields"));
            payload.put("data", result.get("data"));
            payload.put("row_count", Rows.mapList(result.get("data")).size());
            String body = objectMapper.writeValueAsString(payload);
            int timeout = Math.max(1, properties.getPushTimeoutSeconds());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Push failed with HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Push failed: " + e.getMessage(), e);
        }
    }
}

