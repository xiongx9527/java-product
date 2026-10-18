package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.service.ParameterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统参数（对齐 SQLBot apps/system/api/parameter.py）：
 * GET/POST /system/parameter 仅管理员；login/chat 分组登录即可。
 */
@RestController
@RequestMapping({"api/v1"})
public class ParameterController {

    private final ParameterService parameterService;

    public ParameterController(ParameterService parameterService) {
        this.parameterService = parameterService;
    }

    @GetMapping({"/system/parameter"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(parameterService.list());
    }

    @GetMapping({"/system/parameter/login"})
    public Result<List<Map<String, Object>>> login() {
        return Result.ok(parameterService.group("login"));
    }

    @GetMapping({"/system/parameter/chat"})
    public Result<List<Map<String, Object>>> chat() {
        return Result.ok(parameterService.group("chat"));
    }

    @PostMapping({"/system/parameter"})
    public Result<Void> save(@RequestParam(value = "data", required = false) String data,
                             @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        List<String> fileNames = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && file.getOriginalFilename() != null) {
                    fileNames.add(file.getOriginalFilename());
                }
            }
        }
        parameterService.rejectUploads(fileNames);
        parameterService.save(data);
        return Result.ok();
    }
}
