package com.xiongx9527.askdata.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应信封全局兜底（对齐 SQLBot {@code common/core/response_middleware.py} 的 ResponseMiddleware）。
 *
 * <p><b>语义</b>：所有 JSON 响应体由本切面包装为 {@code {"code":0,"data":<原始>,"msg":null}}；
 * 已是该结构的响应<b>不二次包装</b>；流式（{@code text/event-stream}）与文件下载
 * （{@code Content-Disposition: attachment}）响应<b>不包装</b>。</p>
 *
 * <p>本切面是<b>兜底</b>：既有 Controller 已显式返回 {@code Result.ok(...)} 的路径不变
 * （被识别为已包装结构而跳过），未显式包装的路径由本切面补齐信封。</p>
 */
@RestControllerAdvice(basePackages = "com.xiongx9527.askdata")
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ResponseEnvelopeAdvice.class);

    private final ObjectMapper objectMapper;

    public ResponseEnvelopeAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) {
            return body;
        }
        // 非 JSON 响应不包装（流式 text/event-stream、文件下载等）
        if (selectedContentType != null && !isJson(selectedContentType)) {
            return body;
        }
        if (isFileDownload(request, response)) {
            return body;
        }
        // 已经是 {code,data,msg} 结构 → 不二次包装
        if (isAlreadyWrapped(body)) {
            return body;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", 0);
        envelope.put("data", body);
        envelope.put("msg", null);
        return envelope;
    }

    /** 响应是否为 JSON 类型。 */
    private static boolean isJson(MediaType contentType) {
        return contentType.includes(MediaType.APPLICATION_JSON)
                || "application/problem+json".equalsIgnoreCase(contentType.toString())
                || contentType.getSubtype().toLowerCase().endsWith("+json");
    }

    /** 是否为文件下载响应（{@code Content-Disposition: attachment}）。 */
    private static boolean isFileDownload(ServerHttpRequest request, ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse) {
            jakarta.servlet.http.HttpServletResponse servletResponse =
                    ((ServletServerHttpResponse) response).getServletResponse();
            String disposition = servletResponse.getHeader("Content-Disposition");
            if (disposition != null && disposition.toLowerCase().contains("attachment")) {
                return true;
            }
        }
        if (request instanceof ServletServerHttpRequest) {
            jakarta.servlet.http.HttpServletRequest servletRequest =
                    ((ServletServerHttpRequest) request).getServletRequest();
            String accept = servletRequest.getHeader("Accept");
            if (accept != null && accept.toLowerCase().contains("text/event-stream")) {
                return true;
            }
        }
        return false;
    }

    /** 响应体是否已是 {@code {code,data,msg}} 结构。 */
    static boolean isAlreadyWrapped(Object body) {
        if (!(body instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) body;
        return map.containsKey("code") && map.containsKey("data") && map.containsKey("msg");
    }
}
