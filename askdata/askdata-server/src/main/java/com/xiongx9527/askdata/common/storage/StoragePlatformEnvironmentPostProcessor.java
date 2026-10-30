package com.xiongx9527.askdata.common.storage;

import com.xiongx9527.askdata.common.util.Lists;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对象存储平台守卫（A18/A19/A20）：在 Spring 环境就绪后、x-file-storage 绑定属性前，
 * 校验 {@code dromara.x-file-storage.default-platform} 指向的平台配置是否完整。
 *
 * <p>设计目标：<b>配置缺项不阻断启动</b>。选中的平台缺少必填项（或平台名未知）时，
 * 记录可读 WARN 并把 default-platform 覆盖回 {@code local}，保证问数、导出、嵌入助手等
 * 既有链路仍可用；配置完整时保持用户选择。</p>
 */
public class StoragePlatformEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StoragePlatformEnvironmentPostProcessor.class);

    /** 属性前缀。 */
    static final String PREFIX = "dromara.x-file-storage.";

    static final String DEFAULT_PLATFORM = PREFIX + "default-platform";

    /** 回退原因（供启动日志输出）。 */
    public static final String FALLBACK_REASON = "askdata.storage.platform-fallback";

    /** 各平台必填项（属性名相对于 {@code dromara.x-file-storage.<platform>[0]}）。 */
    private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("local", Lists.of());
        REQUIRED.put("minio", Lists.of("access-key", "secret-key", "end-point", "bucket-name"));
        REQUIRED.put("aliyun-oss", Lists.of("access-key", "secret-key", "end-point", "bucket-name"));
        REQUIRED.put("tencent-cos", Lists.of("secret-id", "secret-key", "region", "bucket-name"));
        // 通用 S3：endpoint 必填（自建/Ceph/其它 S3 网关需显式指定），region 缺省 us-east-1
        REQUIRED.put("amazon-s3", Lists.of("access-key", "secret-key", "end-point", "bucket-name"));
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String platform = environment.getProperty(DEFAULT_PLATFORM, "local");
        String normalized = platform == null ? "local" : platform.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "local".equals(normalized)) {
            return;
        }
        List<String> required = REQUIRED.get(normalized);
        if (required == null) {
            fallback(environment, normalized, "未知平台（可选：" + String.join("/", REQUIRED.keySet()) + "）");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            String value = environment.getProperty(PREFIX + normalized + "[0]." + key);
            if (value == null || value.trim().isEmpty()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            fallback(environment, normalized, "缺少必填配置 " + String.join(", ", missing));
        }
    }

    private void fallback(ConfigurableEnvironment environment, String platform, String reason) {
        log.warn("文件存储平台 {} 不可用（{}），已回退 local；如需启用请补全环境变量后重启",
                platform, reason);
        Map<String, Object> override = new LinkedHashMap<>();
        override.put(DEFAULT_PLATFORM, "local");
        // 该阶段 Logback 可能尚未初始化，原因写入属性，由 AskdataFileStorage 在启动日志中输出
        override.put(FALLBACK_REASON, platform + "：" + reason);
        environment.getPropertySources().addFirst(
                new MapPropertySource("askdataStoragePlatformFallback", override));
    }

    @Override
    public int getOrder() {
        // 在配置文件加载完成之后、其它 EnvironmentPostProcessor 之前执行
        return Ordered.LOWEST_PRECEDENCE;
    }
}
