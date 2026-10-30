package com.xiongx9527.askdata.common.storage;

/**
 * 文件存储结果（x-file-storage {@code FileInfo} 的 askdata 侧投影）。
 */
public class StoredFile {

    private final String url;

    private final String filename;

    private final String originalFilename;

    private final String contentType;

    private final Long size;

    private final String platform;

    /**
     * @param url              可直接回访的访问地址（{@link AskdataFileStorage#read(String)} 凭此取回）
     * @param filename         存储文件名（含扩展名，随机/规范化）
     * @param originalFilename 原始文件名
     * @param contentType      MIME 类型
     * @param size             字节数
     * @param platform         存储平台标识（local / aliyun-oss / minio ...）
     */
    public StoredFile(String url, String filename, String originalFilename,
                      String contentType, Long size, String platform) {
        this.url = url;
        this.filename = filename;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.size = size;
        this.platform = platform;
    }

    public String url() {
        return url;
    }

    public String filename() {
        return filename;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public String contentType() {
        return contentType;
    }

    public Long size() {
        return size;
    }

    public String platform() {
        return platform;
    }
}
