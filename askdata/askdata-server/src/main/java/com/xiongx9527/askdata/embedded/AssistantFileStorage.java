package com.xiongx9527.askdata.embedded;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 小助手图片文件存储（logo/float_icon；对齐 SQLBot SQLBotFileUtils 的保存/校验语义）：
 * 落盘 {@code <dir>/<fileId>.<ext>}，fileId 为随机 UUID；仅允许 jpg/jpeg/png/svg，大小 ≤ 10MB。
 */
@Component
public class AssistantFileStorage {

    private static final String[] ALLOWED_EXTENSIONS = {"jpg", "jpeg", "png", "svg"};

    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final String dir;

    public AssistantFileStorage(@org.springframework.beans.factory.annotation.Value("${askdata.assistant-file-path:./data/assistant}")
                                String dir) {
        this.dir = dir;
    }

    /**
     * 保存上传文件，返回 fileId（含扩展名）。
     * originalName 允许带 {@code ,<flag>} 后缀（对齐前端 SetUi 的 {@code 文件名,标志} 约定），
     * 扩展名以第一个点之后的纯扩展名段为准。
     */
    public String save(MultipartFile file, String originalName) throws IOException {
        String ext = extensionOf(originalName);
        if (ext.isEmpty()) {
            throw new IllegalArgumentException("Only support .jpg/.jpeg/.png/.svg");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("File size exceeds limit (10M)");
        }
        String fileId = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        File target = new File(dir, fileId);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create assistant file directory: " + parent);
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return fileId;
    }

    /** 读取文件（不存在返回 null）。 */
    public Resource load(String fileId) throws MalformedURLException {
        if (fileId == null || fileId.isEmpty()) {
            return null;
        }
        // 防路径穿越：仅允许纯文件名
        String safe = new File(fileId).getName();
        if (!safe.equals(fileId)) {
            return null;
        }
        Path path = Paths.get(dir).resolve(safe);
        Resource resource = new UrlResource(path.toUri());
        return resource.exists() && resource.isReadable() ? resource : null;
    }

    /** 按 fileId 推断 Content-Type（对齐 SQLBot picture 端点）。 */
    public static String contentTypeOf(String fileId) {
        if (fileId == null) {
            return "image/jpeg";
        }
        String lower = fileId.toLowerCase();
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        // 去掉 ",<flag>" 标志后缀（前端 SetUi 约定：文件名,标志）
        int comma = filename.indexOf(',');
        String name = comma > 0 ? filename.substring(0, comma) : filename;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        for (String allowed : ALLOWED_EXTENSIONS) {
            if (allowed.equals(ext)) {
                return ext;
            }
        }
        return "";
    }
}
