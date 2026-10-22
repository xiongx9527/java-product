package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 小助手图片文件存储（logo/float_icon；对齐 SQLBot SQLBotFileUtils 的保存/校验语义）：
 * 仅允许 jpg/jpeg/png/svg，大小 ≤ 10MB。物理字节经通用文件存储 {@link AskdataFileStorage}
 * （x-file-storage）落地，{@link #save} 返回存储 url 作为 fileId 供后续 {@link #load} 取回。
 *
 * <p>{@code originalName} 允许带 {@code ,<flag>} 后缀（对齐前端 SetUi 的 {@code 文件名,标志} 约定），
 * 扩展名以第一个点之前的纯扩展名段为准；落库前剥离该后缀以保证扩展名正确。</p>
 *
 * <p>兼容历史数据：早期版本直接落本地磁盘、fileId 为纯文件名；{@link #load} 对非 url 的旧 fileId
 * 回退读取旧目录，避免升级后历史图片失效。</p>
 */
@Component
public class AssistantFileStorage {
    @Autowired
    private MessageResolver messageResolver;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "svg");

    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final AskdataFileStorage fileStorage;

    private final String legacyDir;

    public AssistantFileStorage(AskdataFileStorage fileStorage,
                                @Value("${askdata.assistant-file-path:./data/assistant}") String dir) {
        this.fileStorage = fileStorage;
        this.legacyDir = dir;
    }

    /**
     * 保存上传文件，返回 fileId（存储 url）。
     */
    public String save(MultipartFile file, String originalName) throws IOException {
        String ext = extensionOf(originalName);
        if (ext.isEmpty()) {
            throw new IllegalArgumentException(messageResolver.get("assistant.file.format"));
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException(messageResolver.get("assistant.file.size"));
        }
        // 剥离 ",<flag>" 后缀，保证 x-file-storage 以正确扩展名落库
        String cleanName = baseName(originalName);
        return fileStorage.save(file, cleanName, "assistant").url();
    }

    /** 读取文件（不存在返回 null）。新 fileId 为存储 url（经通用存储读回）；旧 fileId 为纯文件名，回退读旧目录。 */
    public Resource load(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return null;
        }
        try {
            return new ByteArrayResource(fileStorage.read(fileId));
        } catch (RuntimeException ignored) {
            // 非通用存储中的 url（历史纯文件名），回退旧目录
        }
        try {
            return loadLegacy(fileId);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** 旧目录读取（历史纯文件名 fileId；防路径穿越：仅允许纯文件名）。 */
    private Resource loadLegacy(String fileId) throws MalformedURLException {
        String safe = new File(fileId).getName();
        if (!safe.equals(fileId)) {
            return null;
        }
        Path path = Paths.get(legacyDir).resolve(safe);
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

    /** 去掉 ",<flag>" 标志后缀（前端 SetUi 约定：文件名,标志）。 */
    private static String baseName(String filename) {
        if (filename == null) {
            return "";
        }
        int comma = filename.indexOf(',');
        return comma > 0 ? filename.substring(0, comma) : filename;
    }

    private static String extensionOf(String filename) {
        String name = baseName(filename);
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
