package com.xiongx9527.askdata.datasource.controller;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.datasource.service.TableRelationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 表关系端点（对齐 SQLBot apps/datasource/api/table_relation.py）：
 * save/get 数据源的表关系图。
 */
@RestController
@RequestMapping({"api/v1"})
public class TableRelationController {

    private final TableRelationService tableRelationService;

    public TableRelationController(TableRelationService tableRelationService) {
        this.tableRelationService = tableRelationService;
    }

    @PostMapping({"/table_relation/save/{ds_id}"})
    public Result<Void> save(@PathVariable("ds_id") long dsId, @RequestBody List<Map<String, Object>> relation) {
        tableRelationService.requireExists(dsId);
        tableRelationService.save(dsId, relation);
        return Result.ok();
    }

    @PostMapping({"/table_relation/get/{ds_id}"})
    public Result<List<Object>> get(@PathVariable("ds_id") long dsId) {
        tableRelationService.requireExists(dsId);
        return Result.ok(tableRelationService.get(dsId));
    }
}
