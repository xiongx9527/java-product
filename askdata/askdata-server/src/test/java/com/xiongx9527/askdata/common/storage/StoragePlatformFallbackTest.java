package com.xiongx9527.askdata.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A18/A19/A20：对象存储平台配置守卫。
 *
 * <p>覆盖：完整配置保持用户选择；缺必填项或平台名未知时回退 local，避免启动/主链路失败。</p>
 */
class StoragePlatformFallbackTest {

    private final StoragePlatformEnvironmentPostProcessor processor = new StoragePlatformEnvironmentPostProcessor();

    private MockEnvironment env(String platform) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("dromara.x-file-storage.default-platform", platform);
        return environment;
    }

    private String apply(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("dromara.x-file-storage.default-platform");
    }

    @Test
    @DisplayName("默认 local：不做任何覆盖")
    void keepsLocalByDefault() {
        assertEquals("local", apply(env("local")));
    }

    @Test
    @DisplayName("MinIO 配置完整：保持 minio")
    void keepsMinioWhenConfigured() {
        MockEnvironment environment = env("minio");
        environment.setProperty("dromara.x-file-storage.minio[0].access-key", "ak");
        environment.setProperty("dromara.x-file-storage.minio[0].secret-key", "sk");
        environment.setProperty("dromara.x-file-storage.minio[0].end-point", "http://127.0.0.1:9000");
        environment.setProperty("dromara.x-file-storage.minio[0].bucket-name", "askdata");
        assertEquals("minio", apply(environment));
    }

    @Test
    @DisplayName("MinIO 缺少凭据：回退 local")
    void fallsBackToLocalWhenMinioIncomplete() {
        MockEnvironment environment = env("minio");
        environment.setProperty("dromara.x-file-storage.minio[0].end-point", "http://127.0.0.1:9000");
        environment.setProperty("dromara.x-file-storage.minio[0].bucket-name", "askdata");
        assertEquals("local", apply(environment));
    }

    @Test
    @DisplayName("阿里云 OSS 缺少 endpoint：回退 local")
    void fallsBackToLocalWhenOssIncomplete() {
        MockEnvironment environment = env("aliyun-oss");
        environment.setProperty("dromara.x-file-storage.aliyun-oss[0].access-key", "ak");
        environment.setProperty("dromara.x-file-storage.aliyun-oss[0].secret-key", "sk");
        environment.setProperty("dromara.x-file-storage.aliyun-oss[0].bucket-name", "askdata");
        assertEquals("local", apply(environment));
    }

    @Test
    @DisplayName("腾讯云 COS 配置完整：保持 tencent-cos")
    void keepsTencentCosWhenConfigured() {
        MockEnvironment environment = env("tencent-cos");
        environment.setProperty("dromara.x-file-storage.tencent-cos[0].secret-id", "id");
        environment.setProperty("dromara.x-file-storage.tencent-cos[0].secret-key", "key");
        environment.setProperty("dromara.x-file-storage.tencent-cos[0].region", "ap-guangzhou");
        environment.setProperty("dromara.x-file-storage.tencent-cos[0].bucket-name", "askdata-1250000000");
        assertEquals("tencent-cos", apply(environment));
    }

    @Test
    @DisplayName("通用 S3 缺少 endpoint：回退 local")
    void fallsBackToLocalWhenS3Incomplete() {
        MockEnvironment environment = env("amazon-s3");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].access-key", "ak");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].secret-key", "sk");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].bucket-name", "askdata");
        assertEquals("local", apply(environment));
    }

    @Test
    @DisplayName("通用 S3 配置完整：保持 amazon-s3")
    void keepsAmazonS3WhenConfigured() {
        MockEnvironment environment = env("amazon-s3");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].access-key", "ak");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].secret-key", "sk");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].end-point", "http://127.0.0.1:9000");
        environment.setProperty("dromara.x-file-storage.amazon-s3[0].bucket-name", "askdata");
        assertEquals("amazon-s3", apply(environment));
    }

    @Test
    @DisplayName("未知平台名：回退 local")
    void fallsBackOnUnknownPlatform() {
        assertEquals("local", apply(env("swift")));
    }
}
