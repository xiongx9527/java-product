package com.xiongx9527.askdata.terminology;

import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.datasource.DatasourceNameLookup;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.ExcelUtils;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 术语库（对齐 SQLBot apps/terminology：api/terminology.py + curd/terminology.py）：
 * 分页/增删改/启停/导出/模板/Excel 导入。父记录为主词，子记录（pid 非空）为同义词。
 */
@Service
public class TerminologyService {

    private static final String[] ALLOWED_EXTENSIONS = {"xlsx", "xls"};

    private final TerminologyRepository terminologyRepository;

    private final CurrentUserService currentUserService;

    private final DatasourceNameLookup datasourceNameLookup;

    private final AuditRecorder auditRecorder;

    private final TerminologyRecallService terminologyRecallService;

    /** 错误文案统一走消息包（A4 / F-15）。 */
    @Autowired
    private MessageResolver messageResolver;

    @Value("${askdata.excel.path:./data/excel}")
    private String excelPath;

    @Autowired
    public TerminologyService(TerminologyRepository terminologyRepository, CurrentUserService currentUserService,
                              DatasourceNameLookup datasourceNameLookup, AuditRecorder auditRecorder,
                              TerminologyRecallService terminologyRecallService) {
        this.terminologyRepository = terminologyRepository;
        this.currentUserService = currentUserService;
        this.datasourceNameLookup = datasourceNameLookup;
        this.auditRecorder = auditRecorder;
        this.terminologyRecallService = terminologyRecallService;
    }

    /** 构造（保留旧签名；未注入向量召回服务时向量写入被跳过，词法召回不受影响）。 */
    public TerminologyService(TerminologyRepository terminologyRepository, CurrentUserService currentUserService,
                              DatasourceNameLookup datasourceNameLookup, AuditRecorder auditRecorder) {
        this(terminologyRepository, currentUserService, datasourceNameLookup, auditRecorder, null);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** GET /system/terminology/page/{current_page}/{page_size}。 */
    public PagerResult<Map<String, Object>> pager(int currentPage, int pageSize, String word,
                                                  List<Long> dsList, List<Long> advList) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        int size = Math.max(10, pageSize);
        List<Map<String, Object>> rows = terminologyRepository.pageParents(oid, word, dsList, advList,
                currentPage, size);
        long total = terminologyRepository.countParents(oid, word, dsList, advList);
        return new PagerResult<>(toResults(rows), total, currentPage, size);
    }

    /** GET /system/terminology/{id}（编辑器数据）。 */
    public Map<String, Object> editor(long id) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        Map<String, Object> row = terminologyRepository.findParentById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("i18n_terminology.terminology_not_exists")));
        if (Rows.lng(row, "oid", 0L) != oid) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.terminology_not_exists"));
        }
        List<Long> ids = terminologyRepository.parseDatasourceIds(Rows.str(row, "datasource_ids"));
        Map<Long, String> dsNames = datasourceNameLookup.namesOf(ids);
        List<String> datasourceNames = new ArrayList<>();
        for (Long dsId : ids) {
            String name = dsNames.get(dsId);
            datasourceNames.add(name == null ? String.valueOf(dsId) : name);
        }
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("id", Rows.lng(row, "id", 0L));
        editor.put("word", Rows.str(row, "word"));
        editor.put("description", Rows.str(row, "description"));
        editor.put("specific_ds", Rows.bool(row, "specific_ds", false));
        editor.put("datasource_ids", ids);
        editor.put("datasource_names", datasourceNames);
        editor.put("other_words", terminologyRepository.childWords(
                java.util.Collections.singletonList(Rows.lng(row, "id", 0L)))
                .getOrDefault(Rows.lng(row, "id", 0L), new ArrayList<>()));
        editor.put("enabled", Rows.bool(row, "enabled", true));
        editor.put("advanced_application", Rows.str(row, "advanced_application"));
        return editor;
    }

    private List<Map<String, Object>> toResults(List<Map<String, Object>> rows) {
        List<Long> parentIds = new ArrayList<>();
        List<Long> dsIds = new ArrayList<>();
        List<Long> advIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            parentIds.add(Rows.lng(row, "id", 0L));
            dsIds.addAll(terminologyRepository.parseDatasourceIds(Rows.str(row, "datasource_ids")));
            Long adv = Rows.lngOrNull(row, "advanced_application");
            if (adv != null) {
                advIds.add(adv);
            }
        }
        Map<Long, List<String>> childWords = terminologyRepository.childWords(parentIds);
        Map<Long, String> dsNames = datasourceNameLookup.namesOf(dsIds);
        Map<Long, String> advNames = terminologyRepository.assistantNames(advIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = Rows.lng(row, "id", 0L);
            boolean specificDs = Rows.bool(row, "specific_ds", false);
            List<Long> ids = terminologyRepository.parseDatasourceIds(Rows.str(row, "datasource_ids"));
            List<String> datasourceNames = new ArrayList<>();
            if (specificDs) {
                for (Long dsId : ids) {
                    String name = dsNames.get(dsId);
                    datasourceNames.add(name == null ? String.valueOf(dsId) : name);
                }
            }
            Long adv = Rows.lngOrNull(row, "advanced_application");
            result.add(Rows.of(
                    "id", id,
                    "word", Rows.str(row, "word"),
                    "create_time", Rows.lng(row, "create_time", 0L),
                    "description", Rows.str(row, "description"),
                    "other_words", childWords.getOrDefault(id, new ArrayList<>()),
                    "specific_ds", specificDs,
                    "all_data_sources", specificDs ? "N" : "Y",
                    "datasource_ids", ids,
                    "datasource_names", datasourceNames,
                    "enabled", Rows.bool(row, "enabled", true),
                    "advanced_application", adv == null ? null : String.valueOf(adv),
                    "advanced_application_name", adv == null ? null : advNames.get(adv)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    /** PUT /system/terminology（create_or_update，对齐 SQLBot create_or_update）。 */
    @Transactional
    public long createOrUpdate(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String word = requiredText(body, "word");
        String description = requiredText(body, "description");
        boolean specificDs = Rows.bool(body, "specific_ds", false);
        List<Long> datasourceIds = Rows.longList(Rows.get(body, "datasource_ids"));
        List<String> otherWords = normalizeOtherWords(Rows.get(body, "other_words"));
        boolean enabled = Rows.get(body, "enabled") == null ? true : Rows.bool(body, "enabled", true);
        Long advancedApplication = Rows.lngOrNull(body, "advanced_application");
        if (specificDs && datasourceIds.isEmpty() && advancedApplication == null) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.datasource_cannot_be_none"));
        }
        Long id = Rows.lngOrNull(body, "id");
        if (id != null) {
            return update(oid, id, word, description, specificDs, datasourceIds, otherWords, enabled,
                    advancedApplication);
        }
        return create(oid, word, description, specificDs, datasourceIds, otherWords, enabled, advancedApplication);
    }

    private long create(long oid, String word, String description, boolean specificDs, List<Long> datasourceIds,
                        List<String> otherWords, boolean enabled, Long advancedApplication) {
        List<String> words = allWords(word, otherWords);
        if (terminologyRepository.duplicateExists(oid, words, specificDs, datasourceIds, advancedApplication, null)) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.exists_in_db"));
        }
        long now = System.currentTimeMillis();
        String dsJson = terminologyRepository.datasourceIdsJson(datasourceIds);
        long id = terminologyRepository.insertParent(oid, word, description, specificDs, dsJson, enabled,
                advancedApplication, now);
        for (String other : otherWords) {
            terminologyRepository.insertChild(oid, id, other, specificDs, dsJson, enabled, advancedApplication, now);
        }
        if (terminologyRecallService != null) {
            terminologyRecallService.saveEmbeddings(java.util.Collections.singletonList(id));
        }
        return id;
    }

    private long update(long oid, long id, String word, String description, boolean specificDs,
                        List<Long> datasourceIds, List<String> otherWords, boolean enabled,
                        Long advancedApplication) {
        if (!terminologyRepository.exists(oid, id)) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.terminology_not_exists"));
        }
        List<String> words = allWords(word, otherWords);
        if (terminologyRepository.duplicateExists(oid, words, specificDs, datasourceIds, advancedApplication, id)) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.exists_in_db"));
        }
        terminologyRepository.updateParent(id, word, description, specificDs,
                terminologyRepository.datasourceIdsJson(datasourceIds), enabled, advancedApplication);
        terminologyRepository.deleteChildren(id);
        long now = System.currentTimeMillis();
        String dsJson = terminologyRepository.datasourceIdsJson(datasourceIds);
        for (String other : otherWords) {
            terminologyRepository.insertChild(oid, id, other, specificDs, dsJson, enabled, advancedApplication, now);
        }
        if (terminologyRecallService != null) {
            terminologyRecallService.saveEmbeddings(java.util.Collections.singletonList(id));
        }
        return id;
    }

    /** DELETE /system/terminology（body 为 id 数组，级联删除子记录）。 */
    @Transactional
    @AuditLog(module = AuditModule.TERMINOLOGY, operation = AuditOperationType.DELETE)
    public void delete(List<Long> ids) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        terminologyRepository.deleteByIds(oid, ids);
    }

    /** GET /system/terminology/{id}/enable/{enabled}（父与子记录同步启停）。 */
    @Transactional
    @AuditLog(module = AuditModule.TERMINOLOGY, operation = AuditOperationType.UPDATE)
    public void enable(long id, boolean enabled) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        if (!terminologyRepository.exists(oid, id)) {
            throw new BadRequestException(messageResolver.get("i18n_terminology.terminology_not_exists"));
        }
        terminologyRepository.updateEnabled(id, enabled);
    }

    // ------------------------------------------------------------------
    // 导出 / 模板 / 导入
    // ------------------------------------------------------------------

    /** GET /system/terminology/export（xlsx 流）。 */
    @AuditLog(module = AuditModule.TERMINOLOGY, operation = AuditOperationType.EXPORT)
    public void export(HttpServletResponse response, String word, List<Long> dsList, List<Long> advList)
            throws IOException {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> results = toResults(
                terminologyRepository.allParents(oid, word, dsList, advList));
        List<ExcelUtils.Column> columns = terminologyExportColumns();
        writeExcel(response, "terminology.xlsx", columns, results);
    }

    /** GET /system/terminology/template（含示例行的导入模板）。 */
    public void template(HttpServletResponse response) throws IOException {
        List<ExcelUtils.Column> columns = terminologyExportColumns();
        List<Map<String, Object>> example = new ArrayList<>();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("word", messageResolver.get("export.terminology.word.example"));
        row1.put("other_words", messageResolver.get("export.terminology.synonyms.example"));
        row1.put("description", messageResolver.get("export.terminology.description.example"));
        row1.put("datasource_names", messageResolver.get("export.datasource.example"));
        row1.put("all_data_sources", "N");
        row1.put("advanced_application_name", "");
        example.add(row1);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("word", messageResolver.get("export.terminology.word.example2"));
        row2.put("other_words", "");
        row2.put("description", messageResolver.get("export.terminology.description.example"));
        row2.put("datasource_names", "");
        row2.put("all_data_sources", "Y");
        row2.put("advanced_application_name", "");
        example.add(row2);
        writeExcel(response, "terminology_template.xlsx", columns, example);
    }

    /** A6: 导出/模板列头走 i18n（此前硬编码中文）。 */
    private List<ExcelUtils.Column> terminologyExportColumns() {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column(messageResolver.get("export.terminology.word"), "word"));
        columns.add(new ExcelUtils.Column(messageResolver.get("export.terminology.synonyms"), "other_words"));
        columns.add(new ExcelUtils.Column(messageResolver.get("export.terminology.description"), "description"));
        columns.add(new ExcelUtils.Column(messageResolver.get("export.datasource.names"), "datasource_names"));
        columns.add(new ExcelUtils.Column(messageResolver.get("export.all.data.sources"), "all_data_sources"));
        columns.add(new ExcelUtils.Column(messageResolver.get("export.advanced.application"), "advanced_application_name"));
        return columns;
    }

    /**
     * POST /system/terminology/uploadExcel（对齐 SQLBot upload_excel）：
     * 六列：术语名称/同义词/术语描述/生效数据源/全部数据源(Y|N)/高级应用名称。
     */
    @Transactional
    @AuditLog(module = AuditModule.TERMINOLOGY, operation = AuditOperationType.IMPORT)
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
            throw new BadRequestException(messageResolver.get("ds.only.xlsx"));
        }
        List<String[]> rows = ExcelUtils.readRows(new ByteArrayInputStream(content));
        List<Map<String, Object>> failed = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int successCount = 0;
        int duplicateCount = 0;
        int originalCount = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            originalCount++;
            List<String> errors = new ArrayList<>();
            String word = cell(values, 0);
            String description = cell(values, 2);
            List<String> otherWords = splitList(cell(values, 1));
            List<String> datasourceNames = splitList(cell(values, 3));
            String allDataSources = cell(values, 4);
            String advancedApplicationName = cell(values, 5);
            boolean specificDs = !("y".equalsIgnoreCase(allDataSources) || "yes".equalsIgnoreCase(allDataSources)
                    || "true".equalsIgnoreCase(allDataSources));
            String uniqueKey = (String.join(",", sortedWords(word, otherWords)) + "|"
                    + String.join(",", datasourceNames) + "|" + specificDs).toLowerCase();
            if (seen.contains(uniqueKey)) {
                duplicateCount++;
                continue;
            }
            seen.add(uniqueKey);
            if (word.isEmpty()) {
                errors.add(messageResolver.get("i18n_terminology.word_cannot_be_empty"));
            }
            if (description.isEmpty()) {
                errors.add(messageResolver.get("i18n_terminology.description_cannot_be_empty"));
            }
            List<Long> datasourceIds = new ArrayList<>();
            Long advancedApplication = null;
            if (specificDs) {
                for (String dsName : datasourceNames) {
                    Long dsId = datasourceNameLookup.idByName(dsName);
                    if (dsId == null) {
                        errors.add(messageResolver.get("i18n_terminology.datasource_name_not_found",
                                java.util.Map.of("name", dsName)));
                    } else {
                        datasourceIds.add(dsId);
                    }
                }
                if (!advancedApplicationName.isEmpty()) {
                    advancedApplication = resolveAssistantByName(oid, advancedApplicationName);
                    if (advancedApplication == null) {
                        errors.add(messageResolver.get("i18n_terminology.advanced_application_name_not_found",
                                java.util.Map.of("name", advancedApplicationName)));
                    }
                }
                if (datasourceIds.isEmpty() && advancedApplication == null) {
                    errors.add(messageResolver.get("i18n_terminology.datasource_assistant_cannot_be_none"));
                }
            }
            List<String> allWords = allWords(word, otherWords);
            if (allWords.size() != new java.util.LinkedHashSet<>(allWords).size()) {
                errors.add(messageResolver.get("i18n_terminology.cannot_be_repeated"));
            }
            if (!errors.isEmpty()) {
                failed.add(importFailed(word, otherWords, description, datasourceNames, specificDs,
                        advancedApplicationName, errors));
                continue;
            }
            try {
                create(oid, word, description, specificDs, datasourceIds, otherWords, true, advancedApplication);
                successCount++;
            } catch (Exception e) {
                List<String> single = new ArrayList<>();
                single.add(e.getMessage() == null ? e.toString() : e.getMessage());
                failed.add(importFailed(word, otherWords, description, datasourceNames, specificDs,
                        advancedApplicationName, single));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success_count", successCount);
        result.put("failed_count", failed.size());
        result.put("duplicate_count", duplicateCount);
        result.put("original_count", originalCount);
        result.put("error_excel_filename", failed.isEmpty() ? null : writeErrorExcel(filename, failed));
        return result;
    }

    private Long resolveAssistantByName(long oid, String name) {
        List<Map<String, Object>> rows = terminologyRepository.findAssistantsByName(oid, name);
        return rows.isEmpty() ? null : Rows.lngOrNull(rows.get(0), "id");
    }

    private Map<String, Object> importFailed(String word, List<String> otherWords, String description,
                                             List<String> datasourceNames, boolean specificDs,
                                             String advancedApplicationName, List<String> errors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("word", word);
        row.put("other_words", String.join(",", otherWords));
        row.put("description", description);
        row.put("datasource_names", String.join(",", datasourceNames));
        row.put("all_data_sources", specificDs ? "N" : "Y");
        row.put("advanced_application_name", advancedApplicationName);
        row.put("errors", String.join("; ", errors));
        return row;
    }

    private String writeErrorExcel(String originalFilename, List<Map<String, Object>> failed) throws IOException {
        String safeName = new File(originalFilename == null ? "import.xlsx" : originalFilename).getName();
        int dot = safeName.lastIndexOf('.');
        String root = dot > 0 ? safeName.substring(0, dot) : safeName;
        String errorFilename = root + "_" + UUID.randomUUID().toString().substring(0, 10) + "_error.xlsx";
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("术语名称", "word"));
        columns.add(new ExcelUtils.Column("同义词", "other_words"));
        columns.add(new ExcelUtils.Column("术语描述", "description"));
        columns.add(new ExcelUtils.Column("生效数据源", "datasource_names"));
        columns.add(new ExcelUtils.Column("全部数据源", "all_data_sources"));
        columns.add(new ExcelUtils.Column("高级应用", "advanced_application_name"));
        columns.add(new ExcelUtils.Column("错误信息", "errors"));
        File target = new File(excelPath, errorFilename);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create excel directory: " + parent);
        }
        byte[] bytes = excelBytes(columns, failed);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
        }
        return errorFilename;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 问数前术语上下文（对齐 SQLBot get_terminology_template + to_xml_string）：
     * 把命中的术语（标准词 + 同义词集合）及其含义注入 prompt，辅助 LLM 消歧。
     *
     * <p><b>召回</b>：向量召回（{@code terminology.embedding} 余弦相似度 &gt; 0.4，取前 5 条）
     * + 词法召回（子串匹配）——对齐 SQLBot {@code select_terminology_by_word}。
     * 向量不可用（未配置远程模型且本地模型缺失）时自动退化为纯词法召回，<b>不报错</b>。</p>
     *
     * @return 术语上下文文本；无匹配时返回空串
     */
    public String buildTerminologyContext(String question, long oid, long dsId, Long advancedApp) {
        if (question == null || question.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = terminologyRepository.findEnabledTerms(oid, dsId, advancedApp);
        if (rows.isEmpty()) {
            return "";
        }
        Map<Long, TermGroup> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long pid = Rows.lngOrNull(row, "pid");
            Long parentId = pid == null ? Rows.lng(row, "id", 0L) : pid;
            TermGroup g = groups.computeIfAbsent(parentId, k -> new TermGroup());
            g.words.add(Rows.str(row, "word"));
            if (pid == null) {
                g.description = Rows.str(row, "description");
            }
        }
        // 命中集合 = 向量召回 ∪ 词法召回（向量不可用时退化为纯词法）
        Set<Long> hitIds = new HashSet<>();
        if (terminologyRecallService != null) {
            for (Long id : terminologyRecallService.recall(question, oid,
                    dsId > 0 ? dsId : null, advancedApp)) {
                hitIds.add(id);
            }
        }
        String lower = question.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (Map.Entry<Long, TermGroup> entry : groups.entrySet()) {
            TermGroup g = entry.getValue();
            boolean hit = hitIds.contains(entry.getKey());
            if (!hit) {
                for (String w : g.words) {
                    if (w != null && !w.isEmpty() && lower.contains(w.toLowerCase())) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                continue;
            }
            matched++;
            sb.append("- 术语「").append(String.join(",", g.words)).append("」");
            if (g.description != null && !g.description.isEmpty()) {
                sb.append("：").append(g.description);
            }
            sb.append("\n");
        }
        if (matched == 0) {
            return "";
        }
        return "【术语库参考】\n" + sb;
    }

    private static final class TermGroup {
        final List<String> words = new ArrayList<>();
        String description;
    }

    private static List<String> allWords(String word, List<String> otherWords) {
        List<String> words = new ArrayList<>();
        words.add(word.trim());
        for (String other : otherWords) {
            if (!other.trim().isEmpty() && !words.contains(other.trim())) {
                words.add(other.trim());
            }
        }
        return words;
    }

    private static List<String> sortedWords(String word, List<String> otherWords) {
        List<String> words = new ArrayList<>(allWords(word, otherWords));
        java.util.Collections.sort(words);
        return words;
    }

    private static List<String> normalizeOtherWords(Object value) {
        List<String> result = new ArrayList<>();
        for (String item : Rows.strList(value)) {
            if (item != null && !item.trim().isEmpty() && !result.contains(item.trim())) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private static List<String> splitList(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        for (String part : text.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String cell(String[] values, int index) {
        if (index >= values.length || values[index] == null) {
            return "";
        }
        return values[index].trim();
    }

    private String requiredText(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", java.util.Map.of("key", key)));
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
}
