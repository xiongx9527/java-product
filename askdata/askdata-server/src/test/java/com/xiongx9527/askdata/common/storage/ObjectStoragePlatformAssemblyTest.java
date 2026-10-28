package com.xiongx9527.askdata.common.storage;

import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.FileStorageServiceBuilder;
import org.dromara.x.file.storage.core.platform.AliyunOssFileStorage;
import org.dromara.x.file.storage.core.platform.AmazonS3FileStorage;
import org.dromara.x.file.storage.core.platform.MinioFileStorage;
import org.dromara.x.file.storage.core.platform.TencentCosFileStorage;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A19/A20：对象存储平台装配。
 *
 * <p>只校验 x-file-storage 依据配置构建出对应平台实现（客户端为懒连接，不发起网络请求）；
 * 真实 MinIO 读写由 e2e/probe-storage.mjs 在本地实例上验证（A18）。</p>
 */
class ObjectStoragePlatformAssemblyTest {

    private FileStorageService service(Map<String, Object> properties) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("test", properties));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        SpringFileStorageProperties props = binder
                .bind("dromara.x-file-storage", Bindable.of(SpringFileStorageProperties.class))
                .orElseGet(SpringFileStorageProperties::new);
        return new FileStorageServiceBuilder(props.toFileStorageProperties()).build();
    }

    private Map<String, Object> base(String platform) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dromara.x-file-storage.default-platform", platform);
        map.put("dromara.x-file-storage.local[0].platform", "local");
        map.put("dromara.x-file-storage.local[0].enable-storage", "true");
        map.put("dromara.x-file-storage.local[0].base-path", "./target/test-files");
        return map;
    }

    @Test
    @DisplayName("MinIO 配置可装配为 MinioFileStorage")
    void assemblesMinio() {
        Map<String, Object> map = base("minio");
        map.put("dromara.x-file-storage.minio[0].platform", "minio");
        map.put("dromara.x-file-storage.minio[0].enable-storage", "true");
        map.put("dromara.x-file-storage.minio[0].access-key", "ak");
        map.put("dromara.x-file-storage.minio[0].secret-key", "sk");
        map.put("dromara.x-file-storage.minio[0].end-point", "http://127.0.0.1:9000");
        map.put("dromara.x-file-storage.minio[0].bucket-name", "askdata");
        assertInstanceOf(MinioFileStorage.class, service(map).getFileStorage("minio"));
    }

    @Test
    @DisplayName("阿里云 OSS 配置可装配为 AliyunOssFileStorage")
    void assemblesAliyunOss() {
        Map<String, Object> map = base("aliyun-oss");
        map.put("dromara.x-file-storage.aliyun-oss[0].platform", "aliyun-oss");
        map.put("dromara.x-file-storage.aliyun-oss[0].enable-storage", "true");
        map.put("dromara.x-file-storage.aliyun-oss[0].access-key", "ak");
        map.put("dromara.x-file-storage.aliyun-oss[0].secret-key", "sk");
        map.put("dromara.x-file-storage.aliyun-oss[0].end-point", "oss-cn-hangzhou.aliyuncs.com");
        map.put("dromara.x-file-storage.aliyun-oss[0].bucket-name", "askdata");
        assertInstanceOf(AliyunOssFileStorage.class, service(map).getFileStorage("aliyun-oss"));
    }

    @Test
    @DisplayName("通用 S3 配置可装配为 AmazonS3FileStorage")
    void assemblesAmazonS3() {
        Map<String, Object> map = base("amazon-s3");
        map.put("dromara.x-file-storage.amazon-s3[0].platform", "amazon-s3");
        map.put("dromara.x-file-storage.amazon-s3[0].enable-storage", "true");
        map.put("dromara.x-file-storage.amazon-s3[0].access-key", "ak");
        map.put("dromara.x-file-storage.amazon-s3[0].secret-key", "sk");
        map.put("dromara.x-file-storage.amazon-s3[0].region", "us-east-1");
        map.put("dromara.x-file-storage.amazon-s3[0].end-point", "http://127.0.0.1:9000");
        map.put("dromara.x-file-storage.amazon-s3[0].bucket-name", "askdata");
        assertInstanceOf(AmazonS3FileStorage.class, service(map).getFileStorage("amazon-s3"));
    }

    @Test
    @DisplayName("腾讯云 COS 配置可装配为 TencentCosFileStorage")
    void assemblesTencentCos() {
        Map<String, Object> map = base("tencent-cos");
        map.put("dromara.x-file-storage.tencent-cos[0].platform", "tencent-cos");
        map.put("dromara.x-file-storage.tencent-cos[0].enable-storage", "true");
        map.put("dromara.x-file-storage.tencent-cos[0].secret-id", "id");
        map.put("dromara.x-file-storage.tencent-cos[0].secret-key", "key");
        map.put("dromara.x-file-storage.tencent-cos[0].region", "ap-guangzhou");
        map.put("dromara.x-file-storage.tencent-cos[0].bucket-name", "askdata-1250000000");
        assertInstanceOf(TencentCosFileStorage.class, service(map).getFileStorage("tencent-cos"));
    }
}
