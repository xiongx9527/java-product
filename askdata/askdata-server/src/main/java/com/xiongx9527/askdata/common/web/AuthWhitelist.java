package com.xiongx9527.askdata.common.web;

import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 公共路径白名单（对齐 SQLBot {@code common/utils/whitelist.py}；条目数以
 * {@link #DEFAULT_PATHS} 实际值为准，数量见 {@link #COMMENT_COUNT}）。
 *
 * <p>关键语义：白名单判定使用<strong>请求行真实路径</strong>（{@link HttpServletRequest#getRequestURI()}），
 * 而不是由 Host 头拼接出来的 URL 路径。SQLBot 侧对应 {@code request.scope.get("path")}：
 * Starlette 的 {@code URL(scope)} 会把未校验的 Host 头原始值拼进 URL，攻击者可用
 * {@code Host: evil.com/api/v1/mcp} 把受保护接口伪装成白名单前缀路径绕过认证。</p>
 *
 * <p>匹配语义与 SQLBot 逐字一致：</p>
 * <ol>
 *   <li>剥掉 {@code API_V1_STR}（默认 {@code /api/v1}）前缀；</li>
 *   <li>剥掉 context path 前缀；</li>
 *   <li>空路径归一为 {@code /}；</li>
 *   <li>先做<strong>精确</strong>成员判定（只有白名单里逐字出现的无通配符条目命中，
 *       因此 {@code /system/license/} 这类带尾斜杠的写法不命中——与 SQLBot 相同）；</li>
 *   <li>再对去掉尾斜杠的路径做通配正则匹配：仅含 {@code *} 的条目编译为正则，
 *       {@code .} 先转义为 {@code \.}、{@code *} 再替换为 {@code .*}，整体 {@code ^...$} 锚定；
 *       F-13：归一化剥掉全部尾随 {@code /}（原来只剥一个，{@code //path//} 不命中），
 *       多重斜杠后空段不合入避免误吞空段语义。</li>
 * </ol>
 */
@Component
public class AuthWhitelist {

    /** SQLBot {@code common/utils/whitelist.py} 的 wlist（实测 35 条，顺序与内容逐字一致；F-20 注释对齐）。 */
    public static final List<String> DEFAULT_PATHS;

    static {
        List<String> paths = new ArrayList<>();
        paths.add("/");
        paths.add("/docs");
        paths.add("/login/*");
        paths.add("*.ico");
        paths.add("*.html");
        paths.add("*.js");
        paths.add("*.css");
        paths.add("*.png");
        paths.add("*.jpg");
        paths.add("*.jpeg");
        paths.add("*.gif");
        paths.add("*.svg");
        paths.add("*.woff");
        paths.add("*.woff2");
        paths.add("*.ttf");
        paths.add("*.eot");
        paths.add("*.otf");
        paths.add("*.css.map");
        paths.add("/mcp*");
        paths.add("/system/license");
        paths.add("/system/config/key");
        paths.add("/images/*");
        paths.add("/sse");
        paths.add("/system/appearance/ui");
        paths.add("/system/appearance/picture/*");
        paths.add("/system/assistant/info/*");
        paths.add("/system/assistant/app/*");
        paths.add("/system/assistant/picture/*");
        paths.add("/system/assistant/validate/*");
        paths.add("/system/authentication/platform/status");
        paths.add("/system/authentication/login/*");
        paths.add("/system/authentication/sso/*");
        paths.add("/system/platform/sso/*");
        paths.add("/system/platform/client/*");
        paths.add("/system/parameter/login");
        DEFAULT_PATHS = Collections.unmodifiableList(paths);
    }

    /**
     * askdata 侧新增的 API 文档站点路径（Knife4j UI + OpenAPI 规范）。
     *
     * <p>不并入 {@link #DEFAULT_PATHS}：后者与 SQLBot {@code whitelist.py} 逐条等价（35 条），
     * 混入 Java 侧新增条目会破坏 parity 测试与“逐字一致”的语义。文档站点是 askdata 自建的
     * 对外能力，单独维护。静态资源（{@code /webjars/**.js|css|woff2}、{@code *.html}）本就命中
     * {@code DEFAULT_PATHS} 的扩展名规则，这里只补无扩展名的规范端点。</p>
     */
    public static final List<String> API_DOCS_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/doc.html",
            "/docs",
            "/openapi.json",
            "/v3/api-docs*",
            "/swagger-ui/*",
            "/swagger-resources/*"));

    /**
     * askdata 服务端出图（SSR 图表 PNG）访问端点。
     *
     * <p>不并入 {@link #DEFAULT_PATHS}：后者与 SQLBot {@code whitelist.py} 逐条等价（35 条）；
     * 也不并入 {@link #API_DOCS_PATHS}（语义是文档站点）。独立维护，保证 MCP / IM / 第三方
     * 凭 {@code /api/chart/image/{token}} 在 TTL 内可免登录访问，同时 {@link #DEFAULT_PATHS} 的
     * parity 测试不受影响。</p>
     */
    public static final List<String> CHART_IMAGE_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/api/chart/image",
            "/api/chart/image/*"));

    /**
     * 登录前前端启动阶段会读取的只读元数据端点（askdata 侧补充）。
     *
     * <p>前端 {@code utils/xpackPatch.ts} 在应用挂载时（含登录页）调用
     * {@code GET /system/license/version} 展示版本号；该端点若要求登录态会返回 401，
     * 而前端 401 拦截器会清除 {@code user.token} 并延时刷新——登录页启动探测的 401
     * 会在用户刚登录后把新 token 清掉，表现为随机被踢回登录页。</p>
     *
     * <p>不并入 {@link #DEFAULT_PATHS}：后者与 SQLBot {@code whitelist.py} 逐条等价（35 条），
     * 语义与 {@link #CHART_IMAGE_PATHS} 相同——单独的 askdata 公共端点清单。</p>
     */
    public static final List<String> PUBLIC_METADATA_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/system/license/version"));

    private final List<String> paths;

    /** 精确匹配集合（{@link #DEFAULT_PATHS} + {@link #API_DOCS_PATHS} 中的无通配条目）。 */
    private final Set<String> exactPaths;

    private final List<Pattern> compiledPatterns;

    /** SQLBot 的 {@code API_V1_STR}（= CONTEXT_PATH + "/api/v1"）。 */
    private final String apiV1Str;

    /** SQLBot 的 {@code CONTEXT_PATH}。 */
    private String contextPath;

    public AuthWhitelist(@Value("${askdata.api-v1-prefix:/api/v1}") String apiV1Str) {
        this.apiV1Str = apiV1Str == null ? "" : apiV1Str;
        this.contextPath = "";
        this.paths = DEFAULT_PATHS;
        List<String> effective = new ArrayList<>(DEFAULT_PATHS);
        effective.addAll(API_DOCS_PATHS);
        effective.addAll(CHART_IMAGE_PATHS);
        effective.addAll(PUBLIC_METADATA_PATHS);
        this.compiledPatterns = compile(effective);
        this.exactPaths = new HashSet<>(effective);
    }

    /** 带 context path 的实例（SQLBot 的 API_V1_STR = CONTEXT_PATH + "/api/v1"）。 */
    public static AuthWhitelist withContextPath(String apiV1Str, String contextPath) {
        AuthWhitelist whitelist = new AuthWhitelist(apiV1Str);
        whitelist.contextPath = contextPath == null ? "" : contextPath;
        return whitelist;
    }

    private static List<Pattern> compile(List<String> whitelist) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : whitelist) {
            if (pattern.contains("*")) {
                // 与 SQLBot 逐字一致：先转义 '.'，再把 '*' 展开为 '.*'，最后 ^...$ 锚定。
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                compiled.add(Pattern.compile("^" + regex + "$"));
            }
        }
        return compiled;
    }

    /**
     * 按请求行真实路径判定是否公共路径。
     *
     * @param request 当前请求（只读取 {@code getRequestURI()} 与 context path，不读 Host）
     */
    public boolean isWhitelisted(HttpServletRequest request) {
        return isWhitelistedPath(rawPath(request));
    }

    /**
     * 请求行真实路径：{@code requestURI} 去掉 context path。
     * 不使用任何由 Host 头派生的 URL（这是防 {@code Host: evil.com/api/v1/mcp} 绕过的关键）。
     */
    public String rawPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * 对给定路径做白名单判定。
     *
     * @param path        原始路径（可能带 {@code /api/v1} 前缀与 context path）
     * @param contextPath context path（为空表示无）
     */
    public boolean isWhitelistedPath(String path, String contextPath) {
        if (path == null) {
            return false;
        }
        if (!apiV1Str.isEmpty() && path.startsWith(apiV1Str)) {
            path = path.substring(apiV1Str.length());
        }
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (exactPaths.contains(path)) {
            return true;
        }
        String normalized = path;
        // F-13：剥掉全部尾随 '/'（原实现只剥一个，多重尾斜杠漏判）
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (Pattern pattern : compiledPatterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 用构造时配置的 context path 判定。 */
    public boolean isWhitelistedPath(String path) {
        return isWhitelistedPath(path, this.contextPath);
    }

    /** 供测试/诊断：当前生效的路径模式。 */
    public List<String> paths() {
        return paths;
    }
}
