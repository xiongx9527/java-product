package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件存储记录器（x-file-storage {@link FileRecorder}）的进程内默认实现：记录 url/id -> FileInfo，
 * 使 {@code getFileInfoByUrl} / {@code exists(url)} / {@code download(url)} / {@code delete(url)}
 * 等凭 url 的操作可用（否则默认 {@code DefaultFileRecorder} 为 no-op，url 无法反查）。
 *
 * <p>本地单实例部署开箱即用；<b>多实例 / 集群</b>（如切 OSS/MinIO）应改用落库的 FileRecorder
 * （实现同一接口并注册为 Bean 覆盖本默认实现），以保证各实例都能凭 url 反查到 FileInfo。</p>
 *
 * <p>本地平台不分片上传，{@link #saveFilePart} / {@link #deleteFilePartByUploadId} 为 no-op。</p>
 */
public class InMemoryFileRecorder implements FileRecorder {

    private final Map<String, FileInfo> byUrl = new ConcurrentHashMap<>();
    private final Map<String, FileInfo> byId = new ConcurrentHashMap<>();

    @Override
    public boolean save(FileInfo fileInfo) {
        if (fileInfo == null) {
            return false;
        }
        // 记录器负责分配主键 id（delete(url) 凭 id 反查删除）；缺省则生成一个
        if (fileInfo.getId() == null || fileInfo.getId().isEmpty()) {
            fileInfo.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        byId.put(fileInfo.getId(), fileInfo);
        if (fileInfo.getUrl() != null) {
            byUrl.put(fileInfo.getUrl(), fileInfo);
        }
        return true;
    }

    @Override
    public void update(FileInfo fileInfo) {
        save(fileInfo);
    }

    @Override
    public FileInfo getByUrl(String url) {
        return url == null ? null : byUrl.get(url);
    }

    @Override
    public boolean delete(String key) {
        // x-file-storage 删除流以 fileInfo.getUrl() 调用本方法（并非 id），故按 url/id 双向删除
        FileInfo byUrlRemoved = byUrl.remove(key);
        FileInfo byIdRemoved = byId.remove(key);
        if (byUrlRemoved != null && byUrlRemoved.getId() != null) {
            byId.remove(byUrlRemoved.getId());
        }
        if (byIdRemoved != null && byIdRemoved.getUrl() != null) {
            byUrl.remove(byIdRemoved.getUrl());
        }
        return byUrlRemoved != null || byIdRemoved != null;
    }

    @Override
    public void saveFilePart(FilePartInfo filePartInfo) {
        // 本地平台不分片，no-op
    }

    @Override
    public void deleteFilePartByUploadId(String uploadId) {
        // 本地平台不分片，no-op
    }

    public int size() {
        return byUrl.size();
    }
}
