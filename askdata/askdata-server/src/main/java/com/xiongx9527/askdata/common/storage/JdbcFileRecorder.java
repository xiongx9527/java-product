package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;

/**
 * 落库 {@link FileRecorder}：把文件元数据写入 {@code file_storage} 表，使 x-file-storage 凭 url 的
 * 反查/下载/删除在<b>重启与多实例</b>下均可用（替代自动装配默认的 no-op recorder 与进程内实现）。
 *
 * <p>x-file-storage 的删除流以 {@code fileInfo.getUrl()} 调用 {@link #delete(String)}，故按 url 删除为主、
 * 数字 id 为辅。本地平台不分片上传，{@link #saveFilePart}/{@link #deleteFilePartByUploadId} 为 no-op。</p>
 */
public class JdbcFileRecorder implements FileRecorder {

    private final FileStorageRecordRepository repository;

    public JdbcFileRecorder(FileStorageRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean save(FileInfo fileInfo) {
        if (fileInfo == null) {
            return false;
        }
        repository.insert(fileInfo);
        return true;
    }

    @Override
    public void update(FileInfo fileInfo) {
        if (fileInfo != null) {
            repository.update(fileInfo);
        }
    }

    @Override
    public FileInfo getByUrl(String url) {
        return url == null ? null : repository.findByUrl(url).orElse(null);
    }

    @Override
    public boolean delete(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (repository.deleteByUrl(key)) {
            return true;
        }
        return key.matches("\\d+") && repository.deleteById(key);
    }

    @Override
    public void saveFilePart(FilePartInfo filePartInfo) {
        // 本地平台不分片，no-op
    }

    @Override
    public void deleteFilePartByUploadId(String uploadId) {
        // 本地平台不分片，no-op
    }
}
