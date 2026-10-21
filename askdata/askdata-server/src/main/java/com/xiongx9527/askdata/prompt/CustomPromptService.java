package com.xiongx9527.askdata.prompt;

import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.datasource.DatasourceNameLookup;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义提示词（SQLBot 该组路由位于 xpack custom_prompt，此处按 D4「行为可用」重写；
 * 数据表 custom_prompt 与筛选/类型语义对齐 alembic 046/069 与前端 views/system/prompt）：
 * 分页/增删改/导出/模板/导入；类型区分 问数(GENERATE_SQL)/数据分析(ANALYSIS)/数据预测(PREDICT_DATA)。
 */
@Service
public class CustomPromptService {

    /** 提示词类型（对齐 SQLBot CustomPromptTypeEnum）。 */
    public static final String TYPE_GENERATE_SQL = "GENERATE_SQL";
    public static final String TYPE_ANALYSIS = "ANALYSIS";
    public static final String TYPE_PREDICT_DATA = "PREDICT_DATA";

    private final CustomPromptRepository customPromptRepository;

    private final CurrentUserService currentUserService;

    private final DatasourceNameLookup datasourceNameLookup;

    private final AuditRecorder auditRecorder;

    public CustomPromptService(CustomPromptRepository customPromptRepository, CurrentUserService currentUserService,
                               DatasourceNameLookup datasourceNameLookup, AuditRecorder auditRecorder) {
        this.customPromptRepository = customPromptRepository;
        this.currentUserService = currentUserService;
        this.datasourceNameLookup = datasourceNameLookup;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** POST /system/custom_prompt/{type}/page/{current_page}/{page_size}（body 为筛选条件）。 */
    public PagerResult<Map<String, Object>> pager(String type, int currentPage, int pageSize,
                                                  Map<String, Object> filter) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String name = Rows.str(filter, "name");
        List<Long> dsList = Rows.longList(Rows.get(filter, "ds_list"));
        List<Long> advList = Rows.longList(Rows.get(filter, "adv_list"));
        int size = Math.max(10, pageSize);
        List<Map<String, Object>> rows = customPromptRepository.page(oid, type, name, dsList, advList,
                currentPage, size);
        long total = customPromptRepository.count(oid, type, name, dsList, advList);
        return new PagerResult<>(toResults(rows), total, currentPage, size);
    }

    /** GET /system/custom_prompt/{id}（编辑器数据）。 */
    public Map<String, Object> editor(long id) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        Map<String, Object> row = customPromptRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("CustomPrompt with id " + id + " not found"));
        if (Rows.lng(row, "oid", 0L) != oid) {
            throw new BadRequestException("CustomPrompt with id " + id + " not found");
        }
        List<Long> ids = customPromptRepository.parseDatasourceIds(Rows.str(row, "datasource_ids"));
        Map<Long, String> dsNames = datasourceNameLookup.namesOf(ids);
        List<String> datasourceNames = new ArrayList<>();
        for (Long dsId : ids) {
            String name = dsNames.get(dsId);
            datasourceNames.add(name == null ? String.valueOf(dsId) : name);
        }
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("id", Rows.lng(row, "id", 0L));
        editor.put("type", Rows.str(row, "type"));
        editor.put("name", Rows.str(row, "name"));
        editor.put("prompt", Rows.str(row, "prompt"));
        editor.put("specific_ds", Rows.bool(row, "specific_ds", false));
        editor.put("datasource_ids", ids);
        editor.put("datasource_names", datasourceNames);
        editor.put("advanced_application", Rows.str(row, "advanced_application"));
        return editor;
    }

    private List<Map<String, Object>> toResults(List<Map<String, Object>> rows) {
        List<Long> dsIds = new ArrayList<>();
        List<Long> advIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            dsIds.addAll(customPromptRepository.parseDatasourceIds(Rows.str(row, "datasource_ids")));
            Long adv = Rows.lngOrNull(row, "advanced_application");
            if (adv != null) {
                advIds.add(adv);
            }
        }
        Map<Long, String> dsNames = datasourceNameLookup.namesOf(dsIds);
        Map<Long, String> advNames = customPromptRepository.assistantNames(advIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean specificDs = Rows.bool(row, "specific_ds", false);
            List<Long> ids = customPromptRepository.parseDatasourceIds(Rows.str(row, "datasource_ids"));
            List<String> datasourceNames = new ArrayList<>();
            if (specificDs) {
                for (Long dsId : ids) {
                    String name = dsNames.get(dsId);
                    datasourceNames.add(name == null ? String.valueOf(dsId) : name);
                }
            }
            Long adv = Rows.lngOrNull(row, "advanced_application");
            result.add(Rows.of(
                    "id", Rows.lng(row, "id", 0L),
                    "type", Rows.str(row, "type"),
                    "name", Rows.str(row, "name"),
                    "prompt", Rows.str(row, "prompt"),
                    "specific_ds", specificDs,
                    "datasource_ids", ids,
                    "datasource_names", datasourceNames,
                    "create_time", Rows.lng(row, "create_time", 0L),
                    "advanced_application", adv == null ? null : String.valueOf(adv),
                    "advanced_application_name", adv == null ? null : advNames.get(adv)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    /** PUT /system/custom_prompt（create_or_update，body 带 id 为更新）。 */
    @Transactional
    @AuditLog(module = AuditModule.PROMPT_WORDS, operation = AuditOperationType.CREATE_OR_UPDATE)
    public long createOrUpdate(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String type = requiredType(body);
        String name = requiredText(body, "name");
        String prompt = requiredText(body, "prompt");
        boolean specificDs = Rows.bool(body, "specific_ds", false);
        List<Long> datasourceIds = Rows.longList(Rows.get(body, "datasource_ids"));
        Long advancedApplication = Rows.lngOrNull(body, "advanced_application");
        if (specificDs && datasourceIds.isEmpty() && advancedApplication == null) {
            throw new BadRequestException("Datasource and assistant cannot both be empty");
        }
        Long id = Rows.lngOrNull(body, "id");
        if (id != null) {
            if (!customPromptRepository.exists(oid, id)) {
                throw new BadRequestException("CustomPrompt does not exist");
            }
            if (customPromptRepository.nameExists(oid, type, name, id)) {
                throw new BadRequestException("Custom prompt name already exists");
            }
            customPromptRepository.update(id, name, prompt, specificDs,
                    customPromptRepository.datasourceIdsJson(datasourceIds), advancedApplication);
            return id;
        }
        if (customPromptRepository.nameExists(oid, type, name, null)) {
            throw new BadRequestException("Custom prompt name already exists");
        }
        long newId = customPromptRepository.insert(oid, type, name, prompt, specificDs,
                customPromptRepository.datasourceIdsJson(datasourceIds), advancedApplication,
                System.currentTimeMillis());
        return newId;
    }

    /** DELETE /system/custom_prompt（body 为 id 数组）。 */
    @Transactional
    @AuditLog(module = AuditModule.PROMPT_WORDS, operation = AuditOperationType.DELETE)
    public void delete(List<Long> ids) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        customPromptRepository.deleteByIds(oid, ids);
        List<String> strIds = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                strIds.add(String.valueOf(id));
            }
        }
    }

    // ------------------------------------------------------------------
    // 导出 / 模板
    // ------------------------------------------------------------------

    /** POST /system/custom_prompt/{type}/export（xlsx 流；body 为筛选条件）。 */
    @AuditLog(module = AuditModule.PROMPT_WORDS, operation = AuditOperationType.EXPORT)
    public void export(HttpServletResponse response, String type, Map<String, Object> filter) throws IOException {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String name = Rows.str(filter, "name");
        List<Long> dsList = Rows.longList(Rows.get(filter, "ds_list"));
        List<Long> advList = Rows.longList(Rows.get(filter, "adv_list"));
        List<Map<String, Object>> results = toResults(
                customPromptRepository.all(oid, type, name, dsList, advList));
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("提示词名称", "name"));
        columns.add(new ExcelUtils.Column("提示词内容", "prompt"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_names"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        byte[] bytes = excelBytes(columns, results);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"custom_prompt.xlsx\"");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }

    /** GET /system/custom_prompt/template（含示例行的导入模板）。 */
    public void template(HttpServletResponse response) throws IOException {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("提示词名称", "name"));
        columns.add(new ExcelUtils.Column("提示词内容", "prompt"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_names"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        List<Map<String, Object>> example = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "示例提示词");
        row.put("prompt", "请根据以下问题生成 SQL：{question}");
        row.put("datasource_names", "生效数据源1");
        row.put("advanced_application_name", "");
        example.add(row);
        byte[] bytes = excelBytes(columns, example);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"custom_prompt_template.xlsx\"");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }

    private byte[] excelBytes(List<ExcelUtils.Column> columns, List<Map<String, Object>> rows) throws IOException {
        List<ExcelUtils.MapStringArray> adapted = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            adapted.add(key -> {
                Object value = row.get(key);
                if (value == null) {
                    return null;
                }
                if (value instanceof List) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : (List<?>) value) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(item);
                    }
                    return sb.toString();
                }
                return String.valueOf(value);
            });
        }
        return ExcelUtils.writeXlsx(columns, adapted);
    }

    /**
     * POST /system/custom_prompt/{type}/uploadExcel（对齐前端 Uploader 行为）：
     * 四列：提示词名称/提示词内容/生效数据源名称/高级应用名称。
     */
    @Transactional
    @AuditLog(module = AuditModule.PROMPT_WORDS, operation = AuditOperationType.IMPORT)
    public Map<String, Object> uploadExcel(String type, byte[] content, String filename) throws IOException {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String lower = filename == null ? "" : filename.toLowerCase();
        boolean allowed = lower.endsWith(".xlsx") || lower.endsWith(".xls");
        if (!allowed) {
            throw new BadRequestException("Only support .xlsx/.xls");
        }
        List<String[]> rows = com.xiongx9527.askdata.common.util.ExcelUtils.readRows(
                new java.io.ByteArrayInputStream(content));
        List<Map<String, Object>> failed = new ArrayList<>();
        int successCount = 0;
        int originalCount = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            originalCount++;
            List<String> errors = new ArrayList<>();
            String name = cell(values, 0);
            String prompt = cell(values, 1);
            String datasourceName = cell(values, 2);
            String advancedApplicationName = cell(values, 3);
            if (name.isEmpty()) {
                errors.add("Name cannot be empty");
            }
            if (prompt.isEmpty()) {
                errors.add("Prompt cannot be empty");
            }
            Long datasource = null;
            Long advancedApplication = null;
            if (!datasourceName.isEmpty()) {
                datasource = datasourceNameLookup.idByName(datasourceName);
                if (datasource == null) {
                    errors.add("Datasource [" + datasourceName + "] not found");
                }
            }
            if (!advancedApplicationName.isEmpty()) {
                List<Map<String, Object>> assistants = customPromptRepository.findAssistantsByName(oid,
                        advancedApplicationName);
                if (assistants.isEmpty()) {
                    errors.add("Advanced application [" + advancedApplicationName + "] not found");
                } else {
                    advancedApplication = Rows.lngOrNull(assistants.get(0), "id");
                }
            }
            if (datasource == null && advancedApplication == null) {
                errors.add("Datasource and assistant cannot both be empty");
            }
            if (errors.isEmpty() && customPromptRepository.nameExists(oid, type, name, null)) {
                errors.add("Custom prompt name already exists");
            }
            if (!errors.isEmpty()) {
                failed.add(importFailed(name, prompt, datasourceName, advancedApplicationName, errors));
                continue;
            }
            List<Long> datasourceIds = new ArrayList<>();
            if (datasource != null) {
                datasourceIds.add(datasource);
            }
            customPromptRepository.insert(oid, type, name, prompt, !datasourceIds.isEmpty(),
                    customPromptRepository.datasourceIdsJson(datasourceIds), advancedApplication,
                    System.currentTimeMillis());
            successCount++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success_count", successCount);
        result.put("failed_count", failed.size());
        result.put("duplicate_count", 0);
        result.put("original_count", originalCount);
        result.put("error_excel_filename", null);
        return result;
    }

    private Map<String, Object> importFailed(String name, String prompt, String datasourceName,
                                             String advancedApplicationName, List<String> errors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("prompt", prompt);
        row.put("datasource_names", datasourceName);
        row.put("advanced_application_name", advancedApplicationName);
        row.put("errors", String.join("; ", errors));
        return row;
    }

    private static String cell(String[] values, int index) {
        if (index >= values.length || values[index] == null) {
            return "";
        }
        return values[index].trim();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static String requiredType(Map<String, Object> body) {
        String type = Rows.str(body, "type");
        if (type == null || type.isEmpty()) {
            type = TYPE_GENERATE_SQL;
        }
        if (!TYPE_GENERATE_SQL.equals(type) && !TYPE_ANALYSIS.equals(type) && !TYPE_PREDICT_DATA.equals(type)) {
            throw new BadRequestException("Invalid custom prompt type: [" + type + "]");
        }
        return type;
    }

    private static String requiredText(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("Missing required argument: [" + key + "]");
        }
        return value.trim();
    }
}
