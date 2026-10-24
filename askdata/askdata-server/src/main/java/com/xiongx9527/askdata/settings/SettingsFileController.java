package com.xiongx9527.askdata.settings;

import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.Map;

/**
 * 系统文件下载（对齐 SQLBot apps/settings/api/base.py，prefix=/system）：
 * POST /system/download-fail-info 下载导入失败明细回执（_error.xlsx）。
 */
@RestController
@RequestMapping({"api/v1"})
public class SettingsFileController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Value("${askdata.excel.path:./data/excel}")
    private String excelPath;

    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    public SettingsFileController(com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this.messageResolver = messageResolver;
    }

    /**
     * 按文件名下载失败明细（body {@code {"file": "<name>"}}；对齐 SQLBot download_excel）：
     * 仅允许 {@code _error.xlsx} 后缀，防路径穿越（与 SQLBot 的白名单校验一致）。
     */
    @PostMapping({"/system/download-fail-info"})
    public ResponseEntity<Resource> downloadFailInfo(@RequestBody Map<String, Object> body) {
        String filename = body == null ? null : (body.get("file") == null ? null : String.valueOf(body.get("file")));
        if (filename == null || filename.isEmpty()) {
            throw new BadRequestException(messageResolver.get("settings.file.required"));
        }
        if (!filename.endsWith("_error.xlsx")) {
            throw new BadRequestException(messageResolver.get("settings.file.only.error.xlsx"));
        }
        File target = new File(excelPath, new File(filename).getName());
        if (!target.exists() || !target.isFile()) {
            throw new BadRequestException(messageResolver.get("settings.file.not.exists"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + target.getName() + "\"")
                .contentType(XLSX)
                .body(new FileSystemResource(target));
    }
}
