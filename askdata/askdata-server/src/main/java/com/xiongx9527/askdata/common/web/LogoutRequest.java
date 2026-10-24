package com.xiongx9527.askdata.common.web;

/**
 * 登出请求体（对齐 SQLBot {@code LogoutSchema}：{@code origin} 为 SSO 来源标识，0 表示本地）。
 */
public class LogoutRequest {

    private Long origin;

    public Long getOrigin() {
        return origin;
    }

    public void setOrigin(Long origin) {
        this.origin = origin;
    }
}
