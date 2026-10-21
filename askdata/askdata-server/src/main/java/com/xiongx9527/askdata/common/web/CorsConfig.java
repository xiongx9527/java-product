package com.xiongx9527.askdata.common.web;

import com.xiongx9527.askdata.embedded.AssistantRepository;
import com.xiongx9527.askdata.common.util.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CORS 配置（对齐 SQLBot {@code main.py} 的 CORSMiddleware + {@code init_dynamic_cors}）。
 *
 * <p><b>语义</b>：</p>
 * <ul>
 *   <li>允许来源 = 配置项 {@code askdata.cors.origins}（等价 SQLBot 的
 *       {@code BACKEND_CORS_ORIGINS}，逗号分隔）∪ 运行时动态加入的助手域名；</li>
 *   <li>{@code allowCredentials=true}，方法与请求头全放开（{@code *}）；</li>
 *   <li>助手应用的 {@code domain} 字段在运行时动态加入允许来源
 *       （对齐 SQLBot {@code init_dynamic_cors}：{@code domain} 可为逗号/分号分隔的多个域名）；</li>
 *   <li><b>错误响应也带 CORS 头</b>：由 {@link ApiExceptionHandler} 统一补
 *       {@code Access-Control-Allow-Origin}（对齐 SQLBot 的
 *       {@code http_exception_handler}/{@code global_exception_handler}）。</li>
 * </ul>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** 配置的允许来源（{@code askdata.cors.origins}；等价 SQLBot BACKEND_CORS_ORIGINS）。 */
    private final List<String> configuredOrigins;

    /** 运行时动态加入的来源（助手域名）。 */
    private final Set<String> dynamicOrigins = Collections.synchronizedSet(new LinkedHashSet<String>());

    private final AssistantRepository assistantRepository;

    public CorsConfig(@Value("${askdata.cors.origins:}") String origins,
                      AssistantRepository assistantRepository) {
        this.configuredOrigins = parseOrigins(origins);
        this.assistantRepository = assistantRepository;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allOrigins().toArray(new String[0]))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 全部允许来源（配置 + 动态）。
     *
     * <p>用 {@code allowedOriginPatterns} 而非 {@code allowedOrigins}：Spring Boot 2.6+ 在
     * {@code allowCredentials=true} 时禁止 {@code allowedOrigins("*")}，而 pattern 形式
     * 既能表达「全放开」又能表达精确域名。</p>
     */
    public List<String> allOrigins() {
        List<String> origins = new ArrayList<>(configuredOrigins);
        synchronized (dynamicOrigins) {
            origins.addAll(dynamicOrigins);
        }
        if (origins.isEmpty()) {
            // 未配置时放开全部（开发态便利；生产应显式配置）
            origins.add("*");
        }
        return origins;
    }

    /**
     * 运行时动态加入允许来源（助手域名）。
     *
     * @param origins 待加入的来源（可为 null/空）
     */
    public void addDynamicOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String origin : origins) {
            String normalized = normalize(origin);
            if (normalized != null && dynamicOrigins.add(normalized)) {
                changed = true;
            }
        }
        if (changed) {
            log.info("已动态加入 CORS 允许来源：{}", dynamicOrigins);
        }
    }

    /**
     * 从助手应用加载动态域名（对齐 SQLBot {@code init_dynamic_cors}）。
     *
     * <p>助手的 {@code domain} 字段可为逗号/分号分隔的多个域名（对齐 SQLBot
     * {@code get_domain_list}）。加载失败不抛错（只记日志）。</p>
     */
    public void loadAssistantDomains() {
        try {
            List<Map<String, Object>> assistants = assistantRepository.listAllDomains();
            List<String> domains = new ArrayList<>();
            for (Map<String, Object> assistant : assistants) {
                domains.addAll(splitDomains(Rows.str(assistant, "domain")));
            }
            addDynamicOrigins(domains);
        } catch (Exception e) {
            log.info("加载助手动态 CORS 域名失败（忽略）: {}", e.getMessage());
        }
    }

    /** 逗号/分号分隔的域名拆分（对齐 SQLBot {@code get_domain_list}）。 */
    static List<String> splitDomains(String domain) {
        List<String> domains = new ArrayList<>();
        if (domain == null || domain.isEmpty()) {
            return domains;
        }
        for (String item : domain.split("[,;]")) {
            String cleaned = item.trim();
            while (cleaned.endsWith("/")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            if (!cleaned.isEmpty()) {
                domains.add(cleaned);
            }
        }
        return domains;
    }

    /** 解析配置项（逗号/分号分隔；去空白与尾部斜杠）。 */
    static List<String> parseOrigins(String origins) {
        List<String> result = new ArrayList<>();
        if (origins == null || origins.trim().isEmpty()) {
            return result;
        }
        for (String item : origins.split("[,;]")) {
            String normalized = normalize(item);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** 规范化来源（去空白与尾部斜杠；空值返回 null）。 */
    private static String normalize(String origin) {
        if (origin == null) {
            return null;
        }
        String cleaned = origin.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
