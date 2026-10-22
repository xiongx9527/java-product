package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileStorageProperties;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.FileStorageServiceBuilder;
import org.dromara.x.file.storage.core.platform.LocalFileStorage;
import org.dromara.x.file.storage.spring.file.MultipartFileWrapperAdapter;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试辅助：构建指向临时目录的本地平台 {@link FileStorageService}（离线、无需 Spring）。
 * 与生产一致：注册进程内 FileRecorder 与 MultipartFile 适配器，使 url 反查/分文件上传可用。
 */
public final class FileStorageTestFactory {

    private FileStorageTestFactory() {
    }

    public static FileStorageService local(Path basePath) {
        FileStorageProperties props = new FileStorageProperties();
        props.setDefaultPlatform("local");
        FileStorageProperties.LocalConfig local = new FileStorageProperties.LocalConfig();
        local.setPlatform("local");
        local.setBasePath(basePath.toAbsolutePath().toString());
        local.setDomain("");
        List<LocalFileStorage> storages = FileStorageServiceBuilder.buildLocalFileStorage(List.of(local));
        return FileStorageServiceBuilder.create(props)
                .setFileRecorder(new InMemoryFileRecorder())
                .setDefaultTikaFactory()
                .setTikaContentTypeDetect()
                .addAllFileWrapperAdapter()
                .addFileWrapperAdapter(new MultipartFileWrapperAdapter())
                .addFileStorage(storages)
                .build();
    }

    /** 基于本地平台临时目录构造 {@link AskdataFileStorage}。 */
    public static AskdataFileStorage askdata(Path basePath) {
        return new AskdataFileStorage(local(basePath));
    }
}
