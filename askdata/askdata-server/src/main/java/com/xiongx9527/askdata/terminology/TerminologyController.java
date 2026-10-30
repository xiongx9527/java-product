package com.xiongx9527.askdata.terminology;

import com.xiongx9527.askdata.common.web.PagerResult;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语库（对齐 SQLBot apps/terminology/api/terminology.py，prefix=/system/terminology）：
 * 分页/增删改/启停/导出/模板/Excel 导入。
 */
@RestController
@RequestMapping({"api/v1"})
public class TerminologyController {

    private final TerminologyService terminologyService;

    public TerminologyController(TerminologyService terminologyService) {
        this.terminologyService = terminologyService;
    }

    @GetMapping({"/system/terminology/page/{current_page}/{page_size}"})
    public Result<PagerResult<Map<String, Object>>> pager(@PathVariable("current_page") int currentPage,
                                                          @PathVariable("page_size") int pageSize,
                                                          @RequestParam(required = false) String word,
                                                          @RequestParam(required = false) List<Long> ds_list,
                                                          @RequestParam(required = false) List<Long> adv_list) {
        return Result.ok(terminologyService.pager(currentPage, pageSize, word, ds_list, adv_list));
    }

    /** create_or_update（对齐 SQLBot PUT ""，body 带 id 为更新）。 */
    @PutMapping({"/system/terminology"})
    public Result<Long> createOrUpdate(@RequestBody Map<String, Object> info) {
        return Result.ok(terminologyService.createOrUpdate(info));
    }

    @DeleteMapping({"/system/terminology"})
    public Result<Void> delete(@RequestBody List<Long> idList) {
        terminologyService.delete(idList);
        return Result.ok();
    }

    @GetMapping({"/system/terminology/{id}/enable/{enabled}"})
    public Result<Void> enable(@PathVariable long id, @PathVariable boolean enabled) {
        terminologyService.enable(id, enabled);
        return Result.ok();
    }

    @GetMapping({"/system/terminology/{id}"})
    public Result<Map<String, Object>> editor(@PathVariable long id) {
        return Result.ok(terminologyService.editor(id));
    }

    @GetMapping({"/system/terminology/export"})
    public void export(HttpServletResponse response,
                       @RequestParam(required = false) String word,
                       @RequestParam(required = false) List<Long> ds_list,
                       @RequestParam(required = false) List<Long> adv_list) throws IOException {
        terminologyService.export(response, word, ds_list, adv_list);
    }

    @GetMapping({"/system/terminology/template"})
    public void template(HttpServletResponse response) throws IOException {
        terminologyService.template(response);
    }

    @PostMapping({"/system/terminology/uploadExcel"})
    public Result<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        return Result.ok(terminologyService.uploadExcel(file.getBytes(), filename == null ? "" : filename));
    }
}
