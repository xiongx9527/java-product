package com.xiongx9527.askdata.training;

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
 * SQL 示例训练（对齐 SQLBot apps/data_training/api/data_training.py，prefix=/system/data-training）：
 * 分页/增删改/启停/导出/模板/Excel 导入。
 */
@RestController
@RequestMapping({"api/v1"})
public class TrainingController {

    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @GetMapping({"/system/data-training/page/{current_page}/{page_size}"})
    public Result<PagerResult<Map<String, Object>>> pager(@PathVariable("current_page") int currentPage,
                                                         @PathVariable("page_size") int pageSize,
                                                         @RequestParam(required = false) String question,
                                                         @RequestParam(required = false) List<Long> ds_list,
                                                         @RequestParam(required = false) List<Long> adv_list) {
        return Result.ok(trainingService.pager(currentPage, pageSize, question, ds_list, adv_list));
    }

    /** create_or_update（对齐 SQLBot PUT ""，body 带 id 为更新）。 */
    @PutMapping({"/system/data-training"})
    public Result<Long> createOrUpdate(@RequestBody Map<String, Object> info) {
        return Result.ok(trainingService.createOrUpdate(info));
    }

    @DeleteMapping({"/system/data-training"})
    public Result<Void> delete(@RequestBody List<Long> idList) {
        trainingService.delete(idList);
        return Result.ok();
    }

    @GetMapping({"/system/data-training/{id}/enable/{enabled}"})
    public Result<Void> enable(@PathVariable long id, @PathVariable boolean enabled) {
        trainingService.enable(id, enabled);
        return Result.ok();
    }

    @GetMapping({"/system/data-training/{id}"})
    public Result<Map<String, Object>> editor(@PathVariable long id) {
        return Result.ok(trainingService.editor(id));
    }

    @GetMapping({"/system/data-training/export"})
    public void export(HttpServletResponse response,
                       @RequestParam(required = false) String question,
                       @RequestParam(required = false) List<Long> ds_list,
                       @RequestParam(required = false) List<Long> adv_list) throws IOException {
        trainingService.export(response, question, ds_list, adv_list);
    }

    @GetMapping({"/system/data-training/template"})
    public void template(HttpServletResponse response) throws IOException {
        trainingService.template(response);
    }

    @PostMapping({"/system/data-training/uploadExcel"})
    public Result<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        return Result.ok(trainingService.uploadExcel(file.getBytes(), filename == null ? "" : filename));
    }
}
