package com.xiongx9527.askdata.common.web;

/**
 * 身份/令牌无效等禁止访问场景：HTTP 403 + {@code {"detail":...}}
 * （对齐 SQLBot {@code get_user} 在 token 无法反查用户时的 403 语义）。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String detail) {
        super(detail);
    }
}
