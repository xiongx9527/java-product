package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件存储记录 {@code file_storage} 表 JDBC 访问层（x-file-storage FileRecorder 的持久化底座）。
 *
 * <p>落库后凭 url 反查 FileInfo 不再依赖进程内存，重启与多实例均可用（本地/OSS 等平台通用）。</p>
 */
@Repository
public class FileStorageRecordRepository {

    private static final String COLUMNS = "id, url, platform, path, filename, original_filename, "
            + "content_type, size, create_time";

    private final JdbcTemplate jdbc;

    private final com.xiongx9527.askdata.common.util.IdGenerator idGenerator;

    public FileStorageRecordRepository(JdbcTemplate jdbc,
                                       com.xiongx9527.askdata.common.util.IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** 插入记录；id 缺省时由雪花生成并回填到 FileInfo（delete 流以 url 反查，不依赖 id）。 */
    public void insert(FileInfo info) {
        if (info.getId() == null || info.getId().isEmpty()) {
            info.setId(String.valueOf(idGenerator.nextId()));
        }
        jdbc.update("INSERT INTO file_storage (id, url, platform, path, filename, original_filename, "
                        + "content_type, size, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Long.parseLong(info.getId()), info.getUrl(), info.getPlatform(), info.getPath(),
                info.getFilename(), info.getOriginalFilename(), info.getContentType(),
                info.getSize(), System.currentTimeMillis());
    }

    /** 按 url 更新元信息。 */
    public void update(FileInfo info) {
        jdbc.update("UPDATE file_storage SET platform = ?, path = ?, filename = ?, original_filename = ?, "
                        + "content_type = ?, size = ? WHERE url = ?",
                info.getPlatform(), info.getPath(), info.getFilename(), info.getOriginalFilename(),
                info.getContentType(), info.getSize(), info.getUrl());
    }

    public Optional<FileInfo> findByUrl(String url) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM file_storage WHERE url = ?", url);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toFileInfo(rows.get(0)));
    }

    public Optional<FileInfo> findById(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + COLUMNS + " FROM file_storage WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toFileInfo(rows.get(0)));
    }

    public boolean deleteByUrl(String url) {
        return jdbc.update("DELETE FROM file_storage WHERE url = ?", url) > 0;
    }

    public boolean deleteById(String id) {
        return jdbc.update("DELETE FROM file_storage WHERE id = ?", id) > 0;
    }

    private static FileInfo toFileInfo(Map<String, Object> row) {
        FileInfo info = new FileInfo();
        info.setId(row.get("id") == null ? null : String.valueOf(row.get("id")));
        info.setUrl((String) row.get("url"));
        info.setPlatform((String) row.get("platform"));
        info.setPath((String) row.get("path"));
        info.setFilename((String) row.get("filename"));
        info.setOriginalFilename((String) row.get("original_filename"));
        info.setContentType((String) row.get("content_type"));
        Object size = row.get("size");
        info.setSize(size == null ? null : ((Number) size).longValue());
        return info;
    }
}
