package com.xiongx9527.askdata.datasource.controller;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 数据源端点全集（对齐 SQLBot apps/datasource/api/datasource.py，路径逐字一致）：
 * list/get/check/add/update/delete/getTables/getTablesByConf/getSchemaByConf/getFields/
 * syncFields/chooseTables/tableList/fieldList/editTable/editField/previewData/execSql/
 * uploadExcel/parseExcel/importToDb/exportDsSchema/uploadDsSchema/ws。
 */
@RestController
@RequestMapping({"api/v1"})
public class DatasourceController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DatasourceService datasourceService;

    public DatasourceController(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    /** GET /datasource/ws/{oid}（SQLBot include_in_schema=False）：嵌入式通道按 oid 取数。 */
    @GetMapping({"/datasource/ws/{oid}"})
    public Result<List<Map<String, Object>>> ws(@PathVariable long oid) {
        return Result.ok(datasourceService.ws(oid));
    }

    @GetMapping({"/datasource/list"})
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Long oid) {
        return Result.ok(datasourceService.list(oid));
    }

    @PostMapping({"/datasource/get/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(datasourceService.get(id));
    }

    /** POST /datasource/check：按请求配置连通检查（失败抛 500 文本，前端展示）。 */
    @PostMapping({"/datasource/check"})
    public Result<Boolean> check(@RequestBody Map<String, Object> ds) {
        return Result.ok(datasourceService.check(ds));
    }

    @GetMapping({"/datasource/check/{ds_id}"})
    public Result<Boolean> checkById(@PathVariable("ds_id") long dsId) {
        return Result.ok(datasourceService.checkById(dsId));
    }

    @PostMapping({"/datasource/add"})
    public Result<Map<String, Object>> add(@RequestBody Map<String, Object> ds) {
        return Result.ok(datasourceService.add(ds));
    }

    @PostMapping({"/datasource/chooseTables/{id}"})
    public Result<Void> chooseTables(@PathVariable long id, @RequestBody List<Map<String, Object>> tables) {
        datasourceService.chooseTables(id, tables);
        return Result.ok();
    }

    @PostMapping({"/datasource/update"})
    public Result<Void> update(@RequestBody Map<String, Object> ds) {
        datasourceService.update(ds);
        return Result.ok();
    }

    @PostMapping({"/datasource/delete/{id}/{name}"})
    public Result<Void> delete(@PathVariable long id, @PathVariable String name) {
        datasourceService.delete(id, name);
        return Result.ok();
    }

    @PostMapping({"/datasource/getTables/{id}"})
    public Result<List<Map<String, Object>>> getTables(@PathVariable long id) {
        return Result.ok(datasourceService.getTables(id));
    }

    @PostMapping({"/datasource/getTablesByConf"})
    public Result<List<Map<String, Object>>> getTablesByConf(@RequestBody Map<String, Object> ds) {
        return Result.ok(datasourceService.getTablesByConf(ds));
    }

    @PostMapping({"/datasource/getSchemaByConf"})
    public Result<List<String>> getSchemaByConf(@RequestBody Map<String, Object> ds) {
        return Result.ok(datasourceService.getSchemaByConf(ds));
    }

    @PostMapping({"/datasource/getFields/{id}/{table_name}"})
    public Result<List<Map<String, Object>>> getFields(@PathVariable("id") long id,
                                                       @PathVariable("table_name") String tableName) {
        return Result.ok(datasourceService.getFields(id, tableName));
    }

    @PostMapping({"/datasource/syncFields/{ds_id}/{id}"})
    public Result<Void> syncFields(@PathVariable("ds_id") long dsId, @PathVariable long id) {
        datasourceService.syncFields(dsId, id);
        return Result.ok();
    }

    @PostMapping({"/datasource/tableList/{id}"})
    public Result<List<Map<String, Object>>> tableList(@PathVariable long id) {
        return Result.ok(datasourceService.tableList(id));
    }

    @PostMapping({"/datasource/fieldList/{ds_id}/{id}"})
    public Result<List<Map<String, Object>>> fieldList(@PathVariable("ds_id") long dsId, @PathVariable long id,
                                                       @RequestBody(required = false) Map<String, Object> field) {
        return Result.ok(datasourceService.fieldList(dsId, id, field == null ? Rows0.empty() : field));
    }

    @PostMapping({"/datasource/editTable"})
    public Result<Void> editTable(@RequestBody Map<String, Object> table) {
        datasourceService.editTable(table);
        return Result.ok();
    }

    @PostMapping({"/datasource/editField"})
    public Result<Void> editField(@RequestBody Map<String, Object> field) {
        datasourceService.editField(field);
        return Result.ok();
    }

    @PostMapping({"/datasource/previewData/{id}"})
    public Result<Map<String, Object>> previewData(@PathVariable long id, @RequestBody Map<String, Object> data) {
        return Result.ok(datasourceService.previewData(id, data));
    }

    @PostMapping({"/datasource/execSql/{id}"})
    public Result<Map<String, Object>> execSql(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.ok(datasourceService.execSql(id, body));
    }

    /** POST /datasource/fieldEnum/{ds_id}/{id}（SQLBot include_in_schema=False 的保留路由）。 */
    @PostMapping({"/datasource/fieldEnum/{ds_id}/{id}"})
    public Result<List<Object>> fieldEnum(@PathVariable("ds_id") long dsId, @PathVariable long id) {
        return Result.ok(datasourceService.fieldEnum(dsId, id));
    }

    /** GET /datasource/exportDsSchema/{id}：xlsx 字节流下载（id=0 为上传备注模板）。 */
    @GetMapping({"/datasource/exportDsSchema/{id}"})
    public ResponseEntity<ByteArrayResource> exportDsSchema(@PathVariable long id)
            throws UnsupportedEncodingException {
        DatasourceService.ExportFile file = datasourceService.exportDsSchema(id);
        String encoded = URLEncoder.encode(file.filename, "UTF-8").replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentLength(file.content.length)
                .body(new ByteArrayResource(file.content));
    }

    @PostMapping({"/datasource/uploadDsSchema/{id}"})
    public Result<Boolean> uploadDsSchema(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        return Result.ok(datasourceService.uploadDsSchema(id, file));
    }

    @PostMapping({"/datasource/parseExcel"})
    public Result<Map<String, Object>> parseExcel(@RequestParam("file") MultipartFile file) {
        return Result.ok(datasourceService.parseExcel(file));
    }

    @PostMapping({"/datasource/uploadExcel"})
    public Result<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file) {
        return Result.ok(datasourceService.uploadExcel(file));
    }

    @PostMapping({"/datasource/importToDb"})
    public Result<Map<String, Object>> importToDb(@RequestBody Map<String, Object> importRequest) {
        return Result.ok(datasourceService.importToDb(importRequest));
    }

    private static final class Rows0 {

        private Rows0() {
        }

        static Map<String, Object> empty() {
            return new java.util.LinkedHashMap<>();
        }
    }
}
