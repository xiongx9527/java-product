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

import com.xiongx9527.askdata.common.i18n.I18n;

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

    /** 平台回退原因（由 StoragePlatformEnvironmentPostProcessor 写入；未回退时为空）。 */
    @org.springframework.beans.factory.annotation.Value("${askdata.storage.platform-fallback:}")
    private String fallbackReason;

    public AskdataFileStorage(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
        // A18/A19/A20：启动即暴露实际生效的平台，便于部署核对（缺项回退由 StoragePlatformEnvironmentPostProcessor 完成）
        log.info("文件存储平台: {}", fileStorageService == null ? "disabled"
                : String.valueOf(fileStorageService.getDefaultPlatform()));
    }

    /** 平台回退提示：字段注入完成后输出，保证运维能看到回退原因。 */
    @jakarta.annotation.PostConstruct
    void logFallback() {
        if (fallbackReason != null && !fallbackReason.isEmpty()) {
            log.warn("文件存储平台配置不可用（{}），已回退 local；如需启用请补全环境变量后重启", fallbackReason);
        }
    }

    /** 存储是否可用（FileStorageService 已装配）。 */
    public boolean enabled() {
        return fileStorageService != null;
    }

    /** 当前生效的平台标识（local / minio / aliyun-oss / tencent-cos / amazon-s3 / disabled）。 */
    public String platform() {
        return fileStorageService == null ? "disabled"
                : String.valueOf(fileStorageService.getDefaultPlatform());
    }

    private FileStorageService svc() {
        if (fileStorageService == null) {
            throw new StorageException(I18n.msg("storage.not.enabled"));
        }
        return fileStorageService;
    }

    /** 保存上传文件到指定 path（如 "chart"、"assistant"、"excel"），返回存储信息。 */
    public StoredFile save(MultipartFile file, String path) {
        return save(file, file.getOriginalFilename(), path);
    }

    /** 保存上传文件并显式指定原始文件名（用于剥离 "名字,标志" 等约定后缀，保证扩展名正确）。 */
    public StoredFile save(MultipartFile file, String originalFilename, String path) {
        try {
            FileInfo info = svc().of(file)
                    .setPath(path)
                    .setOriginalFilename(originalFilename)
                    .upload();
            return toStored(info);
        } catch (Exception e) {
            throw new StorageException(I18n.msg("storage.save.failed") + ": " + e.getMessage(), e);
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
            throw new StorageException(I18n.msg("storage.save.failed") + ": " + e.getMessage(), e);
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
            throw new StorageException(I18n.msg("storage.save.failed") + ": " + e.getMessage(), e);
        }
    }

    /** 文件记录仓储（按路径+文件名反查用；未装配时跨实例回源不可用）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileStorageRecordRepository recordRepository;

    /**
     * 按存储路径 + 存储文件名读回字节（业务接口只传文件名的跨实例回源路径）。
     *
     * @return 文件字节；不存在或存储不可用时返回 null（由调用方决定降级）
     */
    public byte[] readByName(String path, String filename) {
        org.dromara.x.file.storage.core.FileInfo info = infoByName(path, filename);
        if (info == null) {
            return null;
        }
        try {
            return read(info.getUrl());
        } catch (Exception e) {
            log.debug("按文件名读取失败 {} / {}: {}", path, filename, e.getMessage());
            return null;
        }
    }

    /**
     * 按存储路径 + 文件名取元信息；不存在时返回 null。
     *
     * <p>先按存储名（{@code filename}，上传接口返回的引用）查，未命中再按展示名
     * （{@code original_filename}，如失败明细 {@code xxx_error.xlsx}）查——两者都可能是调用方持有的名字。</p>
     */
    public org.dromara.x.file.storage.core.FileInfo infoByName(String path, String filename) {
        if (!enabled() || recordRepository == null || filename == null || filename.isEmpty()) {
            return null;
        }
        try {
            java.util.Optional<org.dromara.x.file.storage.core.FileInfo> found =
                    recordRepository.findByPathAndFilename(path, filename);
            if (found.isEmpty()) {
                found = recordRepository.findByPathAndOriginalFilename(path, filename);
            }
            return found.orElse(null);
        } catch (Exception e) {
            log.debug("按文件名查询失败 {} / {}: {}", path, filename, e.getMessage());
            return null;
        }
    }

    /** 按 url 读回文件字节。 */
    public byte[] read(String url) {
        try {
            return svc().download(url).bytes();
        } catch (Exception e) {
            throw new StorageException(I18n.msg("storage.read.failed") + ": " + e.getMessage(), e);
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
            throw new StorageException(I18n.msg("storage.unsupported.type") + ": " + filename
                    + "，仅允许 " + Arrays.toString(allowed.toArray()));
        }
    }

    /** 校验大小不超过 max，否则抛 {@link StorageException}。 */
    public static void requireSize(long size, long max) {
        if (size > max) {
            throw new StorageException(I18n.msg("storage.file.too.large", Long.toString(size), Long.toString(max)));
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
