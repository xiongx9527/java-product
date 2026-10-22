package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 通用文件存储（对齐 SQLBot 文件存储）：启用 x-file-storage 自动装配。
 *
 * <p>存储平台由 {@code dromara.x-file-storage.*} 配置驱动（默认本地平台、离线可用；
 * 生产可切换 aliyun-oss / minio / tencent-cos 等）。装配出的
 * {@link org.dromara.x.file.storage.core.FileStorageService} 由 {@link AskdataFileStorage}
 * 包装成 askdata 统一的文件存取入口。</p>
 *
 * <p>注册进程内 {@link InMemoryFileRecorder} 作为 {@link FileRecorder}，使凭 url 的反查/下载/删除可用
 * （x-file-storage 自动装配自带的默认 FileRecorder 为 no-op，凭 url 无法反查）。以独立 Bean 名 +
 * {@link Primary} 覆盖自动装配默认实现，避免同名 Bean 冲突；集群部署需落库 FileRecorder 时改为自定义
 * 并移除本 Bean。</p>
 */
@Configuration
@EnableFileStorage
public class FileStorageConfig {

    @Bean
    @Primary
    public FileRecorder askdataFileRecorder() {
        return new InMemoryFileRecorder();
    }
}
