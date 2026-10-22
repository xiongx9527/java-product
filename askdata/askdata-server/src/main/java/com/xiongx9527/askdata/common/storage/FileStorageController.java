package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 通用文件下载端点（对齐 SQLBot 文件存储的访问端点）：凭 x-file-storage 的 url 流式取回文件。
 *
 * <p>安全：先用 {@link AskdataFileStorage#info(String)} 校验 url 归属本存储
 * （{@code getFileInfoByUrl} 对非本平台文件返回 null），未知 url 直接 404，避免任意文件读取。</p>
 */
@RestController
@RequestMapping("/api/file")
public class FileStorageController {

    private final AskdataFileStorage fileStorage;

    public FileStorageController(AskdataFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** 下载存储文件：{@code GET /api/file/download?url=<stored url>}。 */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("url") String url) {
        if (url == null || url.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        FileInfo info = fileStorage.info(url);
        if (info == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] bytes = fileStorage.read(url);
        String contentType = info.getContentType() != null && !info.getContentType().isEmpty()
                ? info.getContentType()
                : AskdataFileStorage.contentTypeOf(info.getOriginalFilename() != null
                        ? info.getOriginalFilename() : info.getFilename());
        String downloadName = info.getOriginalFilename() != null && !info.getOriginalFilename().isEmpty()
                ? info.getOriginalFilename() : info.getFilename();
        HttpHeaders headers = new HttpHeaders();
        try {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } catch (RuntimeException e) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        headers.setContentDispositionFormData("attachment", encodeFilename(downloadName));
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    private static String encodeFilename(String filename) {
        try {
            return URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return filename;
        }
    }
}
