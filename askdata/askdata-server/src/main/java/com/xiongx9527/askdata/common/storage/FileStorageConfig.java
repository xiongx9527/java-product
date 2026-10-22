package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用文件存储（对齐 SQLBot 文件存储）：启用 x-file-storage 自动装配。
 *
 * <p>存储平台由 {@code dromara.x-file-storage.*} 配置驱动（默认本地平台、离线可用；
 * 生产可切换 aliyun-oss / minio / tencent-cos 等）。装配出的
 * {@link org.dromara.x.file.storage.core.FileStorageService} 由 {@link AskdataFileStorage}
 * 包装成 askdata 统一的文件存取入口。</p>
 *
 * <p>默认注册进程内 {@link InMemoryFileRecorder}，使凭 url 的反查/下载/删除可用；
 * 集群部署可提供自己的 {@link FileRecorder} Bean（落库）覆盖本默认实现。</p>
 */
@Configuration
@EnableFileStorage
public class FileStorageConfig {

    @Bean
    @ConditionalOnMissingBean(FileRecorder.class)
    public FileRecorder fileRecorder() {
        return new InMemoryFileRecorder();
    }
}
