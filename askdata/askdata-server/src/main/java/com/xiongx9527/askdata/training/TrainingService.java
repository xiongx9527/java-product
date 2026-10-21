package com.xiongx9527.askdata.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.datasource.DatasourceNameLookup;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SQL 示例训练（对齐 SQLBot apps/data_training：api/data_training.py + curd/data_training.py）：
 * 分页/增删改/启停/导出/模板/Excel 导入。
 *
 * <p>向量化存储（D5）的 embedding 列已随表落库；问数链路的检索生效由 chat-core 消费，
 * 本模块仅负责管理面与数据落库。</p>
 */
@Service
public class TrainingService {

    /** 导入允许的扩展名（对齐 SQLBot ALLOWED_EXTENSIONS）。 */
    private static final String[] ALLOWED_EXTENSIONS = {"xlsx", "xls"};

    private final TrainingRepository trainingRepository;

    private final CurrentUserService currentUserService;

    private final DatasourceNameLookup datasourceNameLookup;

    private final AuditRecorder auditRecorder;

    private final ObjectMapper objectMapper;

    private final TrainingRecallService trainingRecallService;

    /** Excel 文件目录（对齐 SQLBot settings.EXCEL_PATH，用于失败明细回执）。 */
    @Value("${askdata.excel.path:./data/excel}")
    private String excelPath;

    @Autowired
    public TrainingService(TrainingRepository trainingRepository, CurrentUserService currentUserService,
                           DatasourceNameLookup datasourceNameLookup, AuditRecorder auditRecorder,
                           ObjectMapper objectMapper, TrainingRecallService trainingRecallService) {
        this.trainingRepository = trainingRepository;
        this.currentUserService = currentUserService;
        this.datasourceNameLookup = datasourceNameLookup;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.trainingRecallService = trainingRecallService;
    }

    /** 构造（保留旧签名；未注入向量写入服务时向量写入被跳过，词法召回不受影响）。 */
    public TrainingService(TrainingRepository trainingRepository, CurrentUserService currentUserService,
                           DatasourceNameLookup datasourceNameLookup, AuditRecorder auditRecorder,
                           ObjectMapper objectMapper) {
        this(trainingRepository, currentUserService, datasourceNameLookup, auditRecorder, objectMapper, null);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** GET /system/data-training/page/{current_page}/{page_size}。 */
    public PagerResult<Map<String, Object>> pager(int currentPage, int pageSize, String question,
                                                  List<Long> dsList, List<Long> advList) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        int size = Math.max(10, pageSize);
        List<Map<String, Object>> rows = trainingRepository.page(oid, question, dsList, advList, currentPage, size);
        long total = trainingRepository.count(oid, question, dsList, advList);
        return new PagerResult<>(toResults(rows), total, currentPage, size);
    }

    /** GET /system/data-training/{id}（编辑器数据）。 */
    public Map<String, Object> editor(long id) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        Map<String, Object> row = trainingRepository.findById(id)
                .orElseThrow(() -> new BadRequestException("DataTraining with id " + id + " not found"));
        if (Rows.lng(row, "oid", 0L) != oid) {
            throw new BadRequestException("DataTraining with id " + id + " not found");
        }
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("id", Rows.lng(row, "id", 0L));
        editor.put("question", Rows.str(row, "question"));
        editor.put("description", Rows.str(row, "description"));
        editor.put("datasource", Rows.lngOrNull(row, "datasource"));
        editor.put("advanced_application", Rows.str(row, "advanced_application"));
        editor.put("enabled", Rows.bool(row, "enabled", true));
        return editor;
    }

    private List<Map<String, Object>> toResults(List<Map<String, Object>> rows) {
        List<Long> dsIds = new ArrayList<>();
        List<Long> advIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long ds = Rows.lngOrNull(row, "datasource");
            if (ds != null) {
                dsIds.add(ds);
            }
            Long adv = Rows.lngOrNull(row, "advanced_application");
            if (adv != null) {
                advIds.add(adv);
            }
        }
        Map<Long, String> dsNames = datasourceNameLookup.namesOf(dsIds);
        Map<Long, String> advNames = trainingRepository.assistantNames(advIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long ds = Rows.lngOrNull(row, "datasource");
            Long adv = Rows.lngOrNull(row, "advanced_application");
            result.add(Rows.of(
                    "id", String.valueOf(Rows.lng(row, "id", 0L)),
                    "oid", String.valueOf(Rows.lng(row, "oid", 0L)),
                    "datasource", ds,
                    "datasource_name", ds == null ? null : dsNames.get(ds),
                    "question", Rows.str(row, "question"),
                    "create_time", Rows.lng(row, "create_time", 0L),
                    "description", Rows.str(row, "description"),
                    "enabled", Rows.bool(row, "enabled", true),
                    "advanced_application", adv == null ? null : String.valueOf(adv),
                    "advanced_application_name", adv == null ? null : advNames.get(adv)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    /** PUT /system/data-training（create_or_update，对齐 SQLBot create_or_update）。 */
    @Transactional
    public long createOrUpdate(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String question = requiredText(body, "question");
        String description = requiredText(body, "description");
        Long datasource = Rows.lngOrNull(body, "datasource");
        Long advancedApplication = Rows.lngOrNull(body, "advanced_application");
        boolean enabled = Rows.get(body, "enabled") == null ? true : Rows.bool(body, "enabled", true);
        if (datasource == null && advancedApplication == null) {
            throw new BadRequestException("Datasource and assistant cannot both be empty");
        }
        Long id = Rows.lngOrNull(body, "id");
        if (id != null) {
            return update(oid, id, question, description, datasource, advancedApplication, enabled);
        }
        if (trainingRepository.duplicateExists(oid, question, datasource, advancedApplication, null)) {
            throw new BadRequestException("Data training already exists in db");
        }
        long newId = trainingRepository.insert(oid, datasource, question, description, enabled,
                advancedApplication, System.currentTimeMillis());
        auditRecorder.success(AuditOperationType.CREATE_OR_UPDATE, AuditModule.DATA_TRAINING,
                String.valueOf(newId), question);
        if (trainingRecallService != null) {
            trainingRecallService.saveEmbeddings(java.util.Collections.singletonList(newId));
        }
        return newId;
    }

    private long update(long oid, long id, String question, String description, Long datasource,
                        Long advancedApplication, boolean enabled) {
        if (!trainingRepository.exists(oid, id)) {
            throw new BadRequestException("Data training does not exist");
        }
        if (trainingRepository.duplicateExists(oid, question, datasource, advancedApplication, id)) {
            throw new BadRequestException("Data training already exists in db");
        }
        trainingRepository.update(id, datasource, question, description, enabled, advancedApplication);
        auditRecorder.success(AuditOperationType.CREATE_OR_UPDATE, AuditModule.DATA_TRAINING,
                String.valueOf(id), question);
        if (trainingRecallService != null) {
            trainingRecallService.saveEmbeddings(java.util.Collections.singletonList(id));
        }
        return id;
    }

    /** DELETE /system/data-training（body 为 id 数组）。 */
    @Transactional
    public void delete(List<Long> ids) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        trainingRepository.deleteByIds(oid, ids);
        auditRecorder.success(AuditOperationType.DELETE, AuditModule.DATA_TRAINING, String.join(",", strIds(ids)));
    }

    /** GET /system/data-training/{id}/enable/{enabled}。 */
    @Transactional
    public void enable(long id, boolean enabled) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        if (!trainingRepository.exists(oid, id)) {
            throw new BadRequestException("Data training does not exist");
        }
        trainingRepository.updateEnabled(id, enabled);
        auditRecorder.success(AuditOperationType.UPDATE, AuditModule.DATA_TRAINING, String.valueOf(id));
    }

    // ------------------------------------------------------------------
    // 导出 / 模板 / 导入
    // ------------------------------------------------------------------

    /** GET /system/data-training/export（xlsx 流）。 */
    public void export(HttpServletResponse response, String question, List<Long> dsList, List<Long> advList)
            throws IOException {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> rows = trainingRepository.all(oid, question, dsList, advList);
        List<Map<String, Object>> results = toResults(rows);
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("问题描述", "question"));
        columns.add(new ExcelUtils.Column("示例 SQL", "description"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_name"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        writeExcel(response, "data_training.xlsx", columns, results);
        auditRecorder.success(AuditOperationType.EXPORT, AuditModule.DATA_TRAINING, null);
    }

    /** GET /system/data-training/template（含示例行的导入模板）。 */
    public void template(HttpServletResponse response) throws IOException {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("问题描述", "question"));
        columns.add(new ExcelUtils.Column("示例 SQL", "description"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_name"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        List<Map<String, Object>> example = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question", "查询TEST表内所有ID");
        row.put("description", "SELECT id FROM TEST");
        row.put("datasource_name", "生效数据源1");
        row.put("advanced_application_name", "生效高级应用名称");
        example.add(row);
        writeExcel(response, "data_training_template.xlsx", columns, example);
    }

    /**
     * POST /system/data-training/uploadExcel（对齐 SQLBot upload_excel）：
     * 读取 问题/描述/数据源名称/高级应用名称 四列，逐行校验后批量入库；
     * 失败行生成 _error.xlsx 回执（经 /system/download-fail-info 下载）。
     */
    @Transactional
    public Map<String, Object> uploadExcel(byte[] content, String filename) throws IOException {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String lower = filename == null ? "" : filename.toLowerCase();
        boolean allowed = false;
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith("." + ext)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new BadRequestException("Only support .xlsx/.xls");
        }
        List<String[]> rows = ExcelUtils.readRows(new ByteArrayInputStream(content));
        // 首行为表头；数据行取 问题/描述/数据源名称/高级应用名称 四列（对齐 SQLBot use_cols = [0,1,2,3]）
        List<Map<String, Object>> failed = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int successCount = 0;
        int duplicateCount = 0;
        int originalCount = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            originalCount++;
            List<String> errors = new ArrayList<>();
            String question = cell(values, 0);
            String description = cell(values, 1);
            String datasourceName = cell(values, 2);
            String advancedApplicationName = cell(values, 3);
            String uniqueKey = (question + "|" + datasourceName + "|" + advancedApplicationName).toLowerCase();
            if (seen.contains(uniqueKey)) {
                duplicateCount++;
                continue;
            }
            seen.add(uniqueKey);
            if (question.isEmpty()) {
                errors.add("Question cannot be empty");
            }
            if (description.isEmpty()) {
                errors.add("Description cannot be empty");
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
                advancedApplication = resolveAssistantByName(oid, advancedApplicationName);
                if (advancedApplication == null) {
                    errors.add("Advanced application [" + advancedApplicationName + "] not found");
                }
            }
            if (datasource == null && advancedApplication == null) {
                errors.add("Datasource and assistant cannot both be empty");
            }
            if (!errors.isEmpty()) {
                failed.add(importFailed(question, description, datasourceName, advancedApplicationName, errors));
                continue;
            }
            try {
                if (trainingRepository.duplicateExists(oid, question, datasource, advancedApplication, null)) {
                    throw new BadRequestException("Data training already exists in db");
                }
                trainingRepository.insert(oid, datasource, question, description, true, advancedApplication,
                        System.currentTimeMillis());
                successCount++;
            } catch (Exception e) {
                List<String> single = new ArrayList<>();
                single.add(e.getMessage() == null ? e.toString() : e.getMessage());
                failed.add(importFailed(question, description, datasourceName, advancedApplicationName, single));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success_count", successCount);
        result.put("failed_count", failed.size());
        result.put("duplicate_count", duplicateCount);
        result.put("original_count", originalCount);
        result.put("error_excel_filename", failed.isEmpty() ? null
                : writeErrorExcel(filename, failed));
        auditRecorder.success(AuditOperationType.IMPORT, AuditModule.DATA_TRAINING, null,
                "success=" + successCount + ", failed=" + failed.size());
        return result;
    }

    private Long resolveAssistantByName(long oid, String name) {
        List<Map<String, Object>> rows = trainingRepository.findAssistantsByName(oid, name);
        if (rows.isEmpty()) {
            return null;
        }
        return Rows.lngOrNull(rows.get(0), "id");
    }

    private Map<String, Object> importFailed(String question, String description, String datasourceName,
                                             String advancedApplicationName, List<String> errors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question", question);
        row.put("description", description);
        row.put("datasource_name", datasourceName);
        row.put("advanced_application_name", advancedApplicationName);
        row.put("errors", String.join("; ", errors));
        return row;
    }

    /** 失败明细回执：<原文件名>_<随机后缀>_error.xlsx（对齐 SQLBot 命名约定）。 */
    private String writeErrorExcel(String originalFilename, List<Map<String, Object>> failed) throws IOException {
        String safeName = new File(originalFilename == null ? "import.xlsx" : originalFilename).getName();
        int dot = safeName.lastIndexOf('.');
        String root = dot > 0 ? safeName.substring(0, dot) : safeName;
        String base = root + "_" + UUID.randomUUID().toString().substring(0, 10);
        String errorFilename = base + "_error.xlsx";
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("问题描述", "question"));
        columns.add(new ExcelUtils.Column("示例 SQL", "description"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_name"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        columns.add(new ExcelUtils.Column("错误信息", "errors"));
        writeExcelFile(new File(excelPath, errorFilename), columns, failed);
        return errorFilename;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static String cell(String[] values, int index) {
        if (index >= values.length || values[index] == null) {
            return "";
        }
        return values[index].trim();
    }

    private static String requiredText(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("Missing required argument: [" + key + "]");
        }
        return value.trim();
    }

    private static List<String> strIds(List<Long> ids) {
        List<String> result = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                result.add(String.valueOf(id));
            }
        }
        return result;
    }

    private void writeExcel(HttpServletResponse response, String filename, List<ExcelUtils.Column> columns,
                            List<Map<String, Object>> rows) throws IOException {
        byte[] bytes = excelBytes(columns, rows);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }

    private void writeExcelFile(File target, List<ExcelUtils.Column> columns, List<Map<String, Object>> rows)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create excel directory: " + parent);
        }
        byte[] bytes = excelBytes(columns, rows);
        try (OutputStream out = new java.io.FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
        }
    }

    private byte[] excelBytes(List<ExcelUtils.Column> columns, List<Map<String, Object>> rows) throws IOException {
        List<ExcelUtils.MapStringArray> adapted = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            adapted.add(key -> {
                Object value = row.get(key);
                return value == null ? null : String.valueOf(value);
            });
        }
        return ExcelUtils.writeXlsx(columns, adapted);
    }
}
