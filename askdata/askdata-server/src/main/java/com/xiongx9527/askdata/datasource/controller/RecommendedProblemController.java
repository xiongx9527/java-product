package com.xiongx9527.askdata.datasource.controller;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.datasource.service.RecommendedProblemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源推荐问题端点（对齐 SQLBot apps/datasource/api/recommended_problem.py）。
 */
@RestController
@RequestMapping({"api/v1"})
public class RecommendedProblemController {

    private final RecommendedProblemService recommendedProblemService;

    public RecommendedProblemController(RecommendedProblemService recommendedProblemService) {
        this.recommendedProblemService = recommendedProblemService;
    }

    @GetMapping({"/recommended_problem/get_datasource_recommended/{ds_id}"})
    public Result<List<Map<String, Object>>> list(@PathVariable("ds_id") long dsId) {
        return Result.ok(recommendedProblemService.list(dsId));
    }

    @GetMapping({"/recommended_problem/get_datasource_recommended_base/{ds_id}"})
    public Result<Map<String, Object>> base(@PathVariable("ds_id") long dsId) {
        return Result.ok(recommendedProblemService.base(dsId));
    }

    @PostMapping({"/recommended_problem/save_recommended_problem"})
    public Result<Void> save(@RequestBody Map<String, Object> dataInfo) {
        recommendedProblemService.save(dataInfo);
        return Result.ok();
    }
}
