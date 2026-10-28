package com.xiongx9527.askdata.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import com.xiongx9527.askdata.common.debounce.DebounceBlockedException;
import com.xiongx9527.askdata.common.debounce.DebounceProperties;
import com.xiongx9527.askdata.common.web.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 异常输出对齐 SQLBot：非 2xx 时返回纯文本 detail（前端 request.ts 直接展示 response 文本）；
 * 401 触发前端清除 token 并回到登录页。
 *
 * <p><b>CORS</b>：所有错误响应（400/401/404/500）都带 {@code Access-Control-Allow-Origin}
 * 头（对齐 SQLBot {@code http_exception_handler}/{@code global_exception_handler} 的
 * {@code headers={"Access-Control-Allow-Origin": "*"}}）——否则浏览器端拿不到错误正文，
 * 跨域场景下前端只能看到「CORS 错误」而看不到真实原因。</p>
 *
 * <p><b>日志</b>：异常一律落日志（此前无日志，线上排障困难）。</p>
 */
@RestControllerAdvice(basePackages = "com.xiongx9527.askdata")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 401 来源标记（A13）：前端据此区分「会话失效」与「支线通道凭证失败」。 */
    static final String AUTH_FAILURE_HEADER = AuthFailureSignal.HEADER;

    static final String AUTH_FAILURE_SESSION = AuthFailureSignal.SESSION;

    static final String AUTH_FAILURE_CHANNEL = AuthFailureSignal.CHANNEL;

    /** 错误文案统一走消息包（A4 / F-15）；由 Spring MVC 按 Accept-Language 设定 locale。 */
    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    private final DebounceProperties debounceProperties;

    public ApiExceptionHandler(com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver,
                             DebounceProperties debounceProperties) {
        this.messageResolver = messageResolver;
        this.debounceProperties = debounceProperties;
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<String> badRequest(BadRequestException e) {
        log.info("业务拒绝: {}", e.getMessage());
        if (isMcpRequest()) {
            return detail(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 无效 token / 禁止访问：SQLBot MCP 客户端按 HTTP 403 + {@code {"detail":...}} 解析。
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<String> forbidden(ForbiddenException e) {
        log.info("禁止访问: {}", e.getMessage());
        // MCP 兼容层保持 {"detail":...}；Web/管理面返回纯文本错误体（与其它错误处理一致）
        return isMcpRequest() ? detail(HttpStatus.FORBIDDEN, e.getMessage())
                : error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<String> notLogin(NotLoginException e) {
        log.info("未登录访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin())
                .header(AUTH_FAILURE_HEADER, AUTH_FAILURE_SESSION)
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, AUTH_FAILURE_HEADER)
                .body(messageResolver.get("i18n_permission.authenticate_invalid",
                        java.util.Map.of("msg", String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler(AuthFailureException.class)
    public ResponseEntity<String> authFailure(AuthFailureException e) {
        // 支线通道（API Key / 嵌入助手）的鉴权失败：与 AuthChannelFilter 的 401 口径一致。
        log.info("支线通道鉴权失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin())
                .header(AUTH_FAILURE_HEADER, AUTH_FAILURE_CHANNEL)
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, AUTH_FAILURE_HEADER)
                .body(messageResolver.get("i18n_permission.authenticate_invalid",
                        java.util.Map.of("msg", String.valueOf(e.getMessage()))));
    }

    @ExceptionHandler(DebounceBlockedException.class)
    public ResponseEntity<Result<Void>> debounceBlocked(DebounceBlockedException e, HttpServletRequest request) {
        // 优先使用异常携带的「本次生效窗口」（来自 @Debounce(windowMs=...)），否则回退全局窗口
        long windowMs = e.getWindowMs() > 0L ? e.getWindowMs() : Math.max(1L, debounceProperties.getWindowMs());
        long retryAfter = Math.max(1L, (windowMs + 999L) / 1000L);
        String detailOverride = e.getDetailOverride();
        String detail = (detailOverride != null && !detailOverride.isEmpty())
                ? detailOverride
                : messageResolver.get("i18n_debounce.too_frequent",
                        java.util.Map.of("seconds", String.valueOf(retryAfter)));
        Result<Void> result = new Result<>();
        result.code(429);
        result.data(null);
        result.setMsg(detail);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin())
                .header("Retry-After", String.valueOf(retryAfter))
                .body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> serverError(Exception e) {
        log.error("未处理异常: " + e.getMessage(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, String.valueOf(e.getMessage()));
    }

    /**
     * 构造带 CORS 头的错误响应。
     *
     * @param status HTTP 状态码
     * @param detail 响应正文（纯文本）
     */
    private ResponseEntity<String> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin())
                .body(detail);
    }

    /** MCP 兼容层错误体：{@code {"detail": ...}}（镜像 SQLBot HTTPException）。 */
    private ResponseEntity<String> detail(HttpStatus status, String detail) {
        String body;
        try {
            body = JSON.writeValueAsString(java.util.Map.of("detail", detail == null ? "" : detail));
        } catch (Exception e) {
            body = "{\"detail\":\"\"}";
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static boolean isMcpRequest() {
        HttpServletRequest request = currentRequest();
        return request != null && request.getRequestURI() != null
                && request.getRequestURI().startsWith("/api/v1/mcp/");
    }

    /**
     * 当前请求的允许来源（回显 Origin；无 Origin 时用 {@code *}）。
     *
     * <p>回显 Origin 而非固定 {@code *}：带凭证的请求（{@code allowCredentials=true}）
     * 不允许使用通配符来源。</p>
     */
    private static String allowedOrigin() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "*";
        }
        String origin = request.getHeader("Origin");
        return origin == null || origin.isEmpty() ? "*" : origin;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        }
        return null;
    }
}
