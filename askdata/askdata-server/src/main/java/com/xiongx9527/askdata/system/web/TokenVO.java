package com.xiongx9527.askdata.system.web;

/**
 * 登录响应体，与 SQLBot common/core/schemas.py Token 逐字对齐：
 * 前端 user store 消费 {@code res.access_token} 后写入 wsCache('user.token')。
 */
public class TokenVO {

    private String access_token;

    private String token_type = "bearer";

    /** 与 SQLBot 登录响应一致（初始密码禁用时返回 true——skeleton 期恒 false）。 */
    private Boolean need_change_pwd = false;

    public TokenVO() {
    }

    public TokenVO(String access_token) {
        this.access_token = access_token;
    }

    public String getAccess_token() {
        return access_token;
    }

    public void setAccess_token(String access_token) {
        this.access_token = access_token;
    }

    public String getToken_type() {
        return token_type;
    }

    public void setToken_type(String token_type) {
        this.token_type = token_type;
    }

    public Boolean getNeed_change_pwd() {
        return need_change_pwd;
    }

    public void setNeed_change_pwd(Boolean need_change_pwd) {
        this.need_change_pwd = need_change_pwd;
    }
}
