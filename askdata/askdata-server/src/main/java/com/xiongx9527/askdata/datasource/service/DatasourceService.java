package com.xiongx9527.askdata.datasource.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.engine.ColumnSchema;
import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.excel.ExcelService;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.datasource.support.DatasourceCrypto;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源模块（对齐 SQLBot apps/datasource/api/datasource.py + apps/datasource/crud/datasource.py）：
 * CRUD/check/表字段浏览/勾选同步/预览/执行 SQL/Excel 建表全周期。
 *
 * <p>权限语义（对齐 SQLBot require_permissions）：list/get/check_by_id/getTables/getFields/
 * previewData/execSql/tableList/fieldList 校验数据源归属（同 oid 或管理员）；
 * add/update/delete/chooseTables/editTable/editField/syncFields/getTablesByConf/getSchemaByConf
 * 等写操作要求工作空间管理员。</p>
 */
@Service
public class DatasourceService {

    private static final String ISO_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final DatasourceRepository datasourceRepository;

    private final TableRepository tableRepository;

    private final FieldRepository fieldRepository;

    private final ExcelService excelService;

    private final JdbcEngine engine;

    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper;

    private final MessageResolver messageResolver;

    public DatasourceService(DatasourceRepository datasourceRepository, TableRepository tableRepository,
                             FieldRepository fieldRepository, ExcelService excelService, JdbcEngine engine,
                             CurrentUserService currentUserService, ObjectMapper objectMapper,
                             MessageResolver messageResolver) {
        this.datasourceRepository = datasourceRepository;
        this.tableRepository = tableRepository;
        this.fieldRepository = fieldRepository;
        this.excelService = excelService;
        this.engine = engine;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** GET /datasource/list：当前工作空间数据源（admin 可带 oid 查其他空间，对齐 SQLBot query_by_oid/list）。 */
    public List<Map<String, Object>> list(Long oidParam) {
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        if (currentUserService.isAdmin(user) && oidParam != null) {
            oid = oidParam;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : datasourceRepository.listByOid(oid)) {
            result.add(toVo(row));
        }
        return result;
    }

    /** GET /datasource/ws/{oid}：嵌入式通道按 oid 取数（对齐 SQLBot query_by_oid）。 */
    public List<Map<String, Object>> ws(long oid) {
        Map<String, Object> user = currentUserService.currentUser();
        long target = oid;
        if (!currentUserService.isAdmin(user) || oid <= 0) {
            target = currentUserService.oidOf(user);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : datasourceRepository.listByOid(target)) {
            result.add(toVo(row));
        }
        return result;
    }

    /** POST /datasource/get/{id}。 */
    public Map<String, Object> get(long id) {
        return toVo(requireAccessible(id));
    }

    /** POST /datasource/check：按请求配置实时连通检查（失败按 SQLBot 约定抛 500 文本）。 */
    public boolean check(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        DatasourceType type = typeOf(Rows.str(body, "type"));
        DatasourceConf conf = confOf(Rows.str(body, "configuration"));
        return engine.checkConnection(type, conf, true);
    }

    /** GET /datasource/check/{ds_id}：按已存配置连通检查。 */
    public boolean checkById(long id) {
        Map<String, Object> row = requireAccessible(id);
        return engine.checkConnection(typeOf(Rows.str(row, "type")), confOf(Rows.str(row, "configuration")), true);
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    /** POST /datasource/add：新增数据源并同步勾选表/字段（对齐 SQLBot create_ds）。 */
    @Transactional
    public Map<String, Object> add(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        String name = Rows.str(body, "name");
        if (name == null || name.isEmpty()) {
            throw new BadRequestException("Missing required argument: [name]");
        }
        DatasourceType type = typeOf(Rows.str(body, "type"));
        String plainConf = DatasourceCrypto.decodeConfiguration(Rows.str(body, "configuration"));
        parseConf(plainConf);
        if (datasourceRepository.existsName(oid, name, null)) {
            throw new DatasourceException(messageResolver.get("ds.name.exists"));
        }
        long id = datasourceRepository.nextId();
        datasourceRepository.insert(id, name, Rows.str(body, "description"), type.getType(), type.getDbName(),
                DatasourceCrypto.encodeForStore(plainConf), currentUserService.uid(), oid);
        Map<String, Object> row = datasourceRepository.findById(id)
                .orElseThrow(() -> new DatasourceException(messageResolver.get("ds.not.found", id)));
        syncTables(row, type, parseConf(plainConf), Rows.mapList(Rows.get(body, "tables")));
        updateNum(row, type, parseConf(plainConf));
        return toVo(datasourceRepository.findById(id).orElse(row));
    }

    /** POST /datasource/update：仅更新连接配置（勾选表走 chooseTables，对齐 SQLBot update_ds）。 */
    @Transactional
    public void update(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long id = Rows.lng(body, "id", 0L);
        Map<String, Object> existing = datasourceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", id)));
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        String name = Rows.str(body, "name");
        DatasourceType type = typeOf(Rows.str(body, "type"));
        String plainConf = DatasourceCrypto.decodeConfiguration(Rows.str(body, "configuration"));
        parseConf(plainConf);
        if (datasourceRepository.existsName(oid, name == null ? Rows.str(existing, "name") : name, id)) {
            throw new DatasourceException(messageResolver.get("ds.name.exists"));
        }
        datasourceRepository.update(id,
                name == null ? Rows.str(existing, "name") : name,
                Rows.get(body, "description") == null ? Rows.str(existing, "description")
                        : Rows.str(body, "description"),
                type.getType(), type.getDbName(), DatasourceCrypto.encodeForStore(plainConf));
    }

    /** POST /datasource/delete/{id}/{name}：excel 类型先 drop 应用库中的表（对齐 SQLBot delete_ds）。 */
    @Transactional
    public void delete(long id, String name) {
        currentUserService.requireWsAdmin();
        Map<String, Object> row = datasourceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", id)));
        DatasourceType type = typeOf(Rows.str(row, "type"));
        if (type.usesAppDatabase()) {
            DatasourceConf conf = confOf(Rows.str(row, "configuration"));
            for (Map<String, Object> sheet : conf.getSheets()) {
                Object tableName = sheet.get("tableName");
                if (tableName != null && !String.valueOf(tableName).isEmpty()) {
                    engine.executeAppDdl("DROP TABLE IF EXISTS " + engine.appQuote(String.valueOf(tableName)));
                }
            }
        }
        datasourceRepository.delete(id);
        tableRepository.deleteByDsId(id);
        fieldRepository.deleteByDsId(id);
    }

    /** POST /datasource/chooseTables/{id}：连通检查 + 同步勾选表/字段（对齐 SQLBot chooseTables）。 */
    @Transactional
    public void chooseTables(long id, List<Map<String, Object>> tables) {
        currentUserService.requireWsAdmin();
        Map<String, Object> row = datasourceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", id)));
        DatasourceType type = typeOf(Rows.str(row, "type"));
        DatasourceConf conf = confOf(Rows.str(row, "configuration"));
        engine.checkConnection(type, conf, true);
        syncTables(row, type, conf, tables);
        updateNum(row, type, conf);
    }

    /** POST /datasource/editTable：更新表勾选/自定义注释（对齐 SQLBot updateTable）。 */
    @Transactional
    public void editTable(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long id = Rows.lng(body, "id", 0L);
        long dsId = Rows.lng(body, "ds_id", 0L);
        Map<String, Object> record = tableRepository.findById(id)
                .filter(item -> Rows.lng(item, "ds_id", 0L) == dsId)
                .orElseThrow(() -> new DatasourceException("The data source ID does not match the table ID"));
        tableRepository.updateCheckedAndComment(id, Rows.bool(body, "checked", Rows.bool(record, "checked", true)),
                Rows.str(body, "custom_comment"));
    }

    /** POST /datasource/editField：更新字段勾选/自定义注释（对齐 SQLBot updateField）。 */
    @Transactional
    public void editField(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long id = Rows.lng(body, "id", 0L);
        long tableId = Rows.lng(body, "table_id", 0L);
        long dsId = Rows.lng(body, "ds_id", 0L);
        fieldRepository.listByTableId(tableId).stream()
                .filter(item -> Rows.lng(item, "id", 0L) == id && Rows.lng(item, "ds_id", 0L) == dsId)
                .findFirst()
                .orElseThrow(() -> new DatasourceException("Resource ID does not matched"));
        fieldRepository.updateCheckedAndComment(id, Rows.bool(body, "checked", true),
                Rows.str(body, "custom_comment"));
    }

    /** POST /datasource/syncFields/{ds_id}/{id}：从数据源重新同步单表字段（对齐 SQLBot sync_single_fields）。 */
    @Transactional
    public void syncFields(long dsId, long tableId) {
        currentUserService.requireWsAdmin();
        Map<String, Object> table = tableRepository.findById(tableId)
                .filter(item -> Rows.lng(item, "ds_id", 0L) == dsId)
                .orElseThrow(() -> new DatasourceException("The data source ID does not match the table ID"));
        Map<String, Object> row = datasourceRepository.findById(dsId)
                .orElseThrow(() -> new BadRequestException("Datasource with id " + dsId + " not found"));
        DatasourceType type = typeOf(Rows.str(row, "type"));
        DatasourceConf conf = confOf(Rows.str(row, "configuration"));
        String tableName = Rows.str(table, "table_name");
        boolean exists = type.usesAppDatabase() ? engine.appTableExists(tableName)
                : engine.tables(type, conf).stream().anyMatch(item -> tableName.equals(item.getTableName()));
        if (!exists) {
            throw new DatasourceException(messageResolver.get("ds.table.not.exists"));
        }
        List<ColumnSchema> fields = engine.fields(type, conf, tableName);
        syncFieldRows(dsId, tableId, fields);
    }

    // ------------------------------------------------------------------
    // 表/字段浏览
    // ------------------------------------------------------------------

    /** POST /datasource/getTables/{id}：数据源实时表列表。 */
    public List<Map<String, Object>> getTables(long id) {
        Map<String, Object> row = requireAccessible(id);
        return tablesVo(typeOf(Rows.str(row, "type")), confOf(Rows.str(row, "configuration")));
    }

    /** POST /datasource/getTablesByConf：按请求配置实时表列表（ws_admin）。 */
    public List<Map<String, Object>> getTablesByConf(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        DatasourceType type = typeOf(Rows.str(body, "type"));
        return tablesVo(type, confOf(Rows.str(body, "configuration")));
    }

    /** POST /datasource/getSchemaByConf：按请求配置取 schema 列表（ws_admin）。 */
    public List<String> getSchemaByConf(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        DatasourceType type = typeOf(Rows.str(body, "type"));
        DatasourceConf conf = confOf(Rows.str(body, "configuration"));
        return engine.schemas(type, conf);
    }

    /** POST /datasource/getFields/{id}/{table_name}：数据源实时字段列表。 */
    public List<Map<String, Object>> getFields(long id, String tableName) {
        Map<String, Object> row = requireAccessible(id);
        DatasourceType type = typeOf(Rows.str(row, "type"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ColumnSchema field : engine.fields(type, confOf(Rows.str(row, "configuration")), tableName)) {
            result.add(Rows.of("fieldName", field.getFieldName(), "fieldType", field.getFieldType(),
                    "fieldComment", field.getFieldComment()));
        }
        return result;
    }

    /** POST /datasource/tableList/{id}：已勾选表列表（本地元数据）。 */
    public List<Map<String, Object>> tableList(long id) {
        requireAccessible(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : tableRepository.listByDsId(id)) {
            result.add(tableVo(row));
        }
        return result;
    }

    /** POST /datasource/fieldList/{ds_id}/{id}：表字段列表（本地元数据，支持名称模糊匹配）。 */
    public List<Map<String, Object>> fieldList(long dsId, long tableId, Map<String, Object> body) {
        requireAccessible(dsId);
        tableRepository.findById(tableId)
                .filter(item -> Rows.lng(item, "ds_id", 0L) == dsId)
                .orElseThrow(() -> new DatasourceException("The data source ID does not match the table ID"));
        String fieldName = Rows.str(body, "fieldName");
        List<Map<String, Object>> rows = fieldName == null || fieldName.isEmpty()
                ? fieldRepository.listByTableId(tableId)
                : fieldRepository.listByTableIdLike(tableId, fieldName);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(fieldVo(row));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 预览 / 执行
    // ------------------------------------------------------------------

    /** POST /datasource/previewData/{id}：表数据预览（勾选字段，方言 limit 100，对齐 SQLBot preview）。 */
    public Map<String, Object> previewData(long id, Map<String, Object> body) {
        Map<String, Object> dsRow = requireAccessible(id);
        Long tableId = Rows.lngOrNull(singleMap(Rows.get(body, "table")), "id");
        if (tableId == null || tableId <= 0) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>(), "sql", "");
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> row : fieldRepository.listByTableId(tableId)) {
            if (Rows.bool(row, "checked", true)) {
                fields.add(row);
            }
        }
        if (fields.isEmpty()) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>(), "sql", "");
        }
        Map<String, Object> tableRow = tableRepository.findById(tableId).orElse(null);
        if (tableRow == null) {
            return Rows.of("fields", new ArrayList<>(), "data", new ArrayList<>(), "sql", "");
        }
        List<String> fieldNames = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            fieldNames.add(Rows.str(field, "field_name"));
        }
        DatasourceType type = typeOf(Rows.str(dsRow, "type"));
        DatasourceConf conf = confOf(Rows.str(dsRow, "configuration"));
        String sql = type.previewSql(fieldNames, Rows.str(tableRow, "table_name"), conf, null);
        return engine.execSql(type, conf, sql);
    }

    /** POST /datasource/execSql/{id}：执行只读 SQL（对齐 SQLBot execSql → exec_sql(origin_column=True)）。 */
    public Map<String, Object> execSql(long id, Map<String, Object> body) {
        Map<String, Object> row = requireAccessible(id);
        DatasourceType type = typeOf(Rows.str(row, "type"));
        return engine.execSql(type, confOf(Rows.str(row, "configuration")), Rows.str(body, "sql"));
    }

    /** POST /datasource/fieldEnum/{ds_id}/{id}（SQLBot 保留路由）：字段去重值。 */
    public List<Object> fieldEnum(long dsId, long fieldId) {
        Map<String, Object> dsRow = requireAccessible(dsId);
        Map<String, Object> field = null;
        for (Map<String, Object> row : fieldRepository.listByTableId(fieldId)) {
            if (Rows.lng(row, "id", 0L) == fieldId) {
                field = row;
                break;
            }
        }
        if (field == null) {
            return new ArrayList<>();
        }
        Map<String, Object> table = tableRepository.findById(Rows.lng(field, "table_id", 0L)).orElse(null);
        if (table == null) {
            return new ArrayList<>();
        }
        DatasourceType type = typeOf(Rows.str(dsRow, "type"));
        DatasourceConf conf = confOf(Rows.str(dsRow, "configuration"));
        String fieldName = Rows.str(field, "field_name");
        String tableName = Rows.str(table, "table_name");
        String sql = "SELECT DISTINCT " + DatasourceType.quote(type, fieldName) + " FROM "
                + DatasourceType.quote(type, tableName);
        Map<String, Object> result = engine.execSql(type, conf, sql);
        List<?> columns = (List<?>) result.get("fields");
        List<Object> values = new ArrayList<>();
        if (columns != null && !columns.isEmpty()) {
            String key = String.valueOf(columns.get(0));
            for (Map<String, Object> item : Rows.mapList(result.get("data"))) {
                values.add(item.get(key));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Excel 上传/解析/建表/备注导入导出
    // ------------------------------------------------------------------

    /** POST /datasource/parseExcel：上传解析预览。 */
    public Map<String, Object> parseExcel(org.springframework.web.multipart.MultipartFile file) {
        currentUserService.requireWsAdmin();
        return excelService.parseExcel(file);
    }

    /** POST /datasource/importToDb：导入为应用库数据表（excel 建表流程）。 */
    public Map<String, Object> importToDb(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        return excelService.importToDb(Rows.str(body, "filePath"), Rows.mapList(Rows.get(body, "sheets")));
    }

    /** POST /datasource/uploadExcel：上传即建表（SQLBot deprecated 路由，保持兼容）。 */
    public Map<String, Object> uploadExcel(org.springframework.web.multipart.MultipartFile file) {
        currentUserService.requireWsAdmin();
        return excelService.uploadExcel(file);
    }

    /** GET /datasource/exportDsSchema/{id}：导出表/字段备注工作簿（id=0 为模板）。 */
    public ExportFile exportDsSchema(long id) {
        if (id == 0) {
            return new ExportFile("批量上传备注.xlsx", excelService.buildSchemaTemplate());
        }
        requireAccessible(id);
        List<Map<String, Object>> tables = tableRepository.listByDsId(id);
        if (tables.isEmpty()) {
            throw new BadRequestException("No tables");
        }
        String dsName = datasourceRepository.findById(id).map(row -> Rows.str(row, "name")).orElse("datasource");
        List<ExcelService.SchemaTable> schemaTables = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            Map<String, Object> table = tables.get(i);
            List<Map<String, String>> fields = new ArrayList<>();
            for (Map<String, Object> row : fieldRepository.listByTableId(Rows.lng(table, "id", 0L))) {
                Map<String, String> field = new LinkedHashMap<>();
                field.put("fieldName", Rows.str(row, "field_name"));
                field.put("fieldComment", Rows.str(row, "custom_comment"));
                fields.add(field);
            }
            schemaTables.add(new ExcelService.SchemaTable("Sheet" + i, Rows.str(table, "table_name"),
                    Rows.str(table, "custom_comment"), fields));
        }
        return new ExportFile(dsName + ".xlsx", excelService.buildSchemaWorkbook(schemaTables));
    }

    /** POST /datasource/uploadDsSchema/{id}：按工作簿批量更新表/字段自定义注释（对齐 SQLBot upload_ds_schema）。 */
    @Transactional
    public boolean uploadDsSchema(long id, org.springframework.web.multipart.MultipartFile file) {
        currentUserService.requireWsAdmin();
        datasourceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", id)));
        ExcelService.SchemaUpload upload = excelService.readSchemaWorkbook(file);
        Map<String, String> sheetTableMap = new LinkedHashMap<>();
        for (Map<String, String> table : upload.tableRows) {
            String sheetName = table.get("Sheet名称");
            String tableName = table.get("表名");
            String tableComment = table.get("表备注");
            if (tableName == null || tableName.isEmpty()) {
                continue;
            }
            sheetTableMap.put(sheetName, tableName);
            tableRepository.updateCustomCommentByDsAndName(id, tableName, tableComment == null ? "" : tableComment);
        }
        for (Map<String, Object> fieldSheet : upload.fieldSheets) {
            String sheetName = Rows.str(fieldSheet, "sheetName");
            String tableName = sheetTableMap.get(sheetName);
            if (tableName == null) {
                continue;
            }
            Map<String, Object> table = tableRepository.findByDsAndName(id, tableName).orElse(null);
            if (table == null) {
                continue;
            }
            for (Map<String, Object> row : Rows.mapList(fieldSheet.get("rows"))) {
                String fieldName = Rows.str(row, "字段名");
                String fieldComment = Rows.str(row, "字段备注");
                if (fieldName != null && !fieldName.isEmpty()) {
                    fieldRepository.updateCustomCommentByTableAndName(id, Rows.lng(table, "id", 0L), fieldName,
                            fieldComment == null ? "" : fieldComment);
                }
            }
        }
        return true;
    }

    /** 导出文件载体（文件名 + 字节）。 */
    public static class ExportFile {

        public final String filename;

        public final byte[] content;

        public ExportFile(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }
    }

    // ------------------------------------------------------------------
    // 内部：同步与统计
    // ------------------------------------------------------------------

    /** 同步勾选表与字段（移植 SQLBot sync_table/sync_fields）。 */
    private void syncTables(Map<String, Object> dsRow, DatasourceType type, DatasourceConf conf,
                            List<Map<String, Object>> tables) {
        long dsId = Rows.lng(dsRow, "id", 0L);
        List<Long> tableIds = new ArrayList<>();
        for (Map<String, Object> item : tables) {
            String tableName = Rows.str(item, "table_name");
            if (tableName == null || tableName.isEmpty()) {
                continue;
            }
            String tableComment = Rows.str(item, "table_comment");
            Optional<Map<String, Object>> record = tableRepository.findByDsAndName(dsId, tableName);
            long tableId;
            if (record.isPresent()) {
                tableId = Rows.lng(record.get(), "id", 0L);
                tableRepository.updateTableComment(tableId, tableComment == null ? "" : tableComment);
            } else {
                tableId = tableRepository.insert(dsId, true, tableName, tableComment == null ? "" : tableComment,
                        tableComment == null ? "" : tableComment);
            }
            tableIds.add(tableId);
            List<ColumnSchema> fields = engine.fields(type, conf, tableName);
            syncFieldRows(dsId, tableId, fields);
        }
        if (tableIds.isEmpty()) {
            tableRepository.deleteByDsId(dsId);
            fieldRepository.deleteByDsId(dsId);
        } else {
            tableRepository.deleteByDsIdNotIn(dsId, tableIds);
            fieldRepository.deleteByTableIdsNotIn(tableIds);
        }
    }

    private void syncFieldRows(long dsId, long tableId, List<ColumnSchema> fields) {
        List<Long> fieldIds = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            ColumnSchema item = fields.get(index);
            String fieldComment = item.getFieldComment() == null ? "" : item.getFieldComment();
            Optional<Map<String, Object>> record = fieldRepository.findByTableAndName(tableId, item.getFieldName());
            if (record.isPresent()) {
                long id = Rows.lng(record.get(), "id", 0L);
                fieldIds.add(id);
                fieldRepository.updateMeta(id, fieldComment, index, item.getFieldType());
            } else {
                fieldIds.add(fieldRepository.insert(dsId, tableId, true, item.getFieldName(), item.getFieldType(),
                        fieldComment, fieldComment, index));
            }
        }
        fieldRepository.deleteByTableIdNotIn(tableId, fieldIds);
    }

    /** num = 已勾选表数/数据源总表数（移植 SQLBot updateNum；excel 取 configuration.sheets）。 */
    private void updateNum(Map<String, Object> dsRow, DatasourceType type, DatasourceConf conf) {
        long dsId = Rows.lng(dsRow, "id", 0L);
        int total;
        if (type.usesAppDatabase()) {
            total = conf.getSheets().size();
        } else {
            total = engine.tables(type, conf).size();
        }
        long selected = tableRepository.countByDsId(dsId);
        datasourceRepository.updateNum(dsId, selected + "/" + total);
    }

    // ------------------------------------------------------------------
    // 内部：转换与校验
    // ------------------------------------------------------------------

    private Map<String, Object> requireAccessible(long id) {
        Map<String, Object> row = datasourceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", id)));
        Map<String, Object> user = currentUserService.currentUser();
        if (currentUserService.isAdmin(user)) {
            return row;
        }
        if (Rows.lng(row, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(messageResolver.get("ds.not.found", id));
        }
        return row;
    }

    private DatasourceType typeOf(String type) {
        try {
            return DatasourceType.fromType(type);
        } catch (IllegalArgumentException e) {
            throw new DatasourceException(e.getMessage());
        }
    }

    private DatasourceConf confOf(String storedConfiguration) {
        return parseConf(DatasourceCrypto.decodeConfiguration(storedConfiguration));
    }

    private DatasourceConf parseConf(String plainJson) {
        try {
            return objectMapper.readValue(plainJson, DatasourceConf.class);
        } catch (Exception e) {
            throw new DatasourceException("Invalid datasource configuration: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> tablesVo(DatasourceType type, DatasourceConf conf) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (type.usesAppDatabase()) {
            // excel：表清单以 configuration.sheets 为准（对齐前端编辑流），避免泄漏应用库元数据表
            for (Map<String, Object> sheet : conf.getSheets()) {
                result.add(Rows.of("tableName", String.valueOf(sheet.getOrDefault("tableName", "")),
                        "tableComment", String.valueOf(sheet.getOrDefault("tableComment", ""))));
            }
            return result;
        }
        for (com.xiongx9527.askdata.datasource.engine.TableSchema table : engine.tables(type, conf)) {
            result.add(Rows.of("tableName", table.getTableName(), "tableComment", table.getTableComment()));
        }
        return result;
    }

    private Map<String, Object> toVo(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object createTime = Rows.get(row, "create_time");
        String createTimeText = null;
        if (createTime instanceof java.sql.Timestamp) {
            createTimeText = new SimpleDateFormat(ISO_TIME).format((java.sql.Timestamp) createTime);
        } else if (createTime != null) {
            createTimeText = String.valueOf(createTime);
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", Rows.lng(row, "id", 0L));
        vo.put("name", Rows.str(row, "name"));
        vo.put("description", Rows.str(row, "description"));
        vo.put("type", Rows.str(row, "type"));
        vo.put("type_name", Rows.str(row, "type_name"));
        // 前端编辑表单 decrypted(configuration) 消费，必须回前端 AES 形态
        vo.put("configuration", DatasourceCrypto.encodeForFrontend(Rows.str(row, "configuration")));
        vo.put("create_time", createTimeText);
        vo.put("create_by", Rows.lng(row, "create_by", 0L));
        vo.put("status", Rows.str(row, "status"));
        vo.put("num", Rows.str(row, "num"));
        vo.put("oid", Rows.lng(row, "oid", 0L));
        vo.put("table_relation", parseRelation(Rows.str(row, "table_relation")));
        vo.put("embedding", Rows.str(row, "embedding"));
        vo.put("recommended_config", Rows.intg(row, "recommended_config", 1));
        return vo;
    }

    private Object parseRelation(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> tableVo(Map<String, Object> row) {
        return Rows.of(
                "id", Rows.lng(row, "id", 0L),
                "ds_id", Rows.lng(row, "ds_id", 0L),
                "checked", Rows.bool(row, "checked", true),
                "table_name", Rows.str(row, "table_name"),
                "table_comment", Rows.str(row, "table_comment"),
                "custom_comment", Rows.str(row, "custom_comment"));
    }

    private Map<String, Object> fieldVo(Map<String, Object> row) {
        return Rows.of(
                "id", Rows.lng(row, "id", 0L),
                "ds_id", Rows.lng(row, "ds_id", 0L),
                "table_id", Rows.lng(row, "table_id", 0L),
                "checked", Rows.bool(row, "checked", true),
                "field_name", Rows.str(row, "field_name"),
                "field_type", Rows.str(row, "field_type"),
                "field_comment", Rows.str(row, "field_comment"),
                "custom_comment", Rows.str(row, "custom_comment"),
                "field_index", Rows.intg(row, "field_index", 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> singleMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
