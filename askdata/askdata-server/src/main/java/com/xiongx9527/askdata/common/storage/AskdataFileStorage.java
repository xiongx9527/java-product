package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * askdata 通用文件存储入口（对齐 SQLBot 文件存储）：对 x-file-storage {@link FileStorageService}
 * 做薄封装，统一 save / read / exists / delete / 取元信息，并内置扩展名与大小校验。
 *
 * <p>平台由 {@code dromara.x-file-storage.*} 决定（默认本地、离线可用；可配置切 OSS/MinIO 等），
 * 业务侧不感知平台差异。上传/导出/服务端出图（SSR 图表 PNG）等均经此类落地。</p>
 *
 * <p>降级语义：{@link FileStorageService} 未装配时，{@link #enabled()} 为 false，
 * 读写方法抛 {@link StorageException}，由调用方决定是否降级（不阻断主链路）。</p>
 */
@Component
public class AskdataFileStorage {

    private static final Logger log = LoggerFactory.getLogger(AskdataFileStorage.class);

    /** 常见允许的图片扩展名。 */
    public static final Set<String> IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "gif", "svg", "webp", "bmp");
    /** 常见允许的表格/文档扩展名。 */
    public static final Set<String> DOC_EXT =
            Set.of("xls", "xlsx", "csv", "json", "txt", "pdf");

    private final FileStorageService fileStorageService;

    public AskdataFileStorage(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 存储是否可用（FileStorageService 已装配）。 */
    public boolean enabled() {
        return fileStorageService != null;
    }

    private FileStorageService svc() {
        if (fileStorageService == null) {
            throw new StorageException("文件存储未启用（FileStorageService 未装配）");
        }
        return fileStorageService;
    }

    /** 保存上传文件到指定 path（如 "chart"、"assistant"、"excel"），返回存储信息。 */
    public StoredFile save(MultipartFile file, String path) {
        try {
            FileInfo info = svc().of(file)
                    .setPath(path)
                    .setOriginalFilename(file.getOriginalFilename())
                    .upload();
            return toStored(info);
        } catch (Exception e) {
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    /** 保存字节内容（服务端出图/导出等），filename 需含扩展名。 */
    public StoredFile save(byte[] content, String filename, String path, String contentType) {
        try {
            FileInfo info = svc().of(content, filename)
                    .setPath(path)
                    .setContentType(contentType)
                    .upload();
            return toStored(info);
        } catch (Exception e) {
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    /** 保存输入流内容，filename 需含扩展名。 */
    public StoredFile save(InputStream in, String filename, String path, String contentType) {
        try {
            FileInfo info = svc().of(in, filename)
                    .setPath(path)
                    .setContentType(contentType)
                    .upload();
            return toStored(info);
        } catch (Exception e) {
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    /** 按 url 读回文件字节。 */
    public byte[] read(String url) {
        try {
            return svc().download(url).bytes();
        } catch (Exception e) {
            throw new StorageException("文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 按 url 取文件元信息；非本存储的文件返回 null。 */
    public FileInfo info(String url) {
        if (url == null || url.isEmpty() || !enabled()) {
            return null;
        }
        try {
            return svc().getFileInfoByUrl(url);
        } catch (Exception e) {
            log.debug("getFileInfoByUrl 失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean exists(String url) {
        if (url == null || url.isEmpty() || !enabled()) {
            return false;
        }
        try {
            return svc().exists(url);
        } catch (Exception e) {
            log.debug("exists 失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(String url) {
        if (url == null || url.isEmpty() || !enabled()) {
            return false;
        }
        try {
            return svc().delete(url);
        } catch (Exception e) {
            log.warn("文件删除失败: {}", e.getMessage());
            return false;
        }
    }

    /** 由文件名推断 Content-Type（未知回落 application/octet-stream）。 */
    public static String contentTypeOf(String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            case "json" -> "application/json";
            case "txt", "csv" -> "text/plain";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }

    /** 取纯扩展名（小写、无点）；无扩展名返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 校验扩展名在白名单内，否则抛 {@link StorageException}。 */
    public static void requireExtension(String filename, Set<String> allowed) {
        String ext = extensionOf(filename);
        if (ext.isEmpty() || !allowed.contains(ext)) {
            throw new StorageException("不支持的文件类型: " + filename
                    + "，仅允许 " + Arrays.toString(allowed.toArray()));
        }
    }

    /** 校验大小不超过 max，否则抛 {@link StorageException}。 */
    public static void requireSize(long size, long max) {
        if (size > max) {
            throw new StorageException("文件过大: " + size + " 字节，上限 " + max + " 字节");
        }
    }

    private StoredFile toStored(FileInfo info) {
        return new StoredFile(info.getUrl(), info.getFilename(), info.getOriginalFilename(),
                info.getContentType(), info.getSize(), info.getPlatform());
    }

    /** 文件存储异常（调用方据此降级，不阻断主链路）。 */
    public static class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
