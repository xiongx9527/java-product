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
 * <p>注册落库 {@link JdbcFileRecorder} 作为 {@link FileRecorder}，使凭 url 的反查/下载/删除在重启与
 * 多实例下均可用。x-file-storage 经 {@code @EnableFileStorage} 导入的自动装配亦会各建一个同名
 * {@code fileRecorder} 的默认 no-op Bean（其 {@code @ConditionalOnMissingBean} 因 @Import 时序不会
 * 退避），故此处用<b>独立 Bean 名 + {@link Primary}</b> 既避开同名冲突、又保证注入落到落库实现；
 * 自动装配那条默认 recorder 不会被注入（无实际作用），其启动告警可忽略。</p>
 */
@Configuration
@EnableFileStorage
public class FileStorageConfig {

    @Bean
    @Primary
    public FileRecorder askdataFileRecorder(FileStorageRecordRepository repository) {
        return new JdbcFileRecorder(repository);
    }
}
