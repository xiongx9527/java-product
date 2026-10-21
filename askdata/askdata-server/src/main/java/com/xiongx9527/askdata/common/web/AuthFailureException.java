package com.xiongx9527.askdata.common.web;

/**
 * 鉴权通道失败异常（对齐 SQLBot 的 401 语义：{@code i18n_permission.authenticate_invalid}）。
 *
 * <p>由 {@link AuthChannelFilter} 捕获并转成 HTTP 401，响应头带
 * {@code Access-Control-Allow-Origin: *}（SQLBot 在每个鉴权失败响应上都加该头）。</p>
 */
public class AuthFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthFailureException(String message) {
        super(message);
    }

    public AuthFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
