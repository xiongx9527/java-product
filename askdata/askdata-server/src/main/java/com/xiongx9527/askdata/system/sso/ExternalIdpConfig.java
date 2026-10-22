package com.xiongx9527.askdata.system.sso;

import java.util.HashMap;
import java.util.Map;

/** 外部 IdP 连接配置（A7；由配置中心/属性绑定注入）。 */
public class ExternalIdpConfig {

    private IdpType type;
    private String displayName;
    private boolean enabled;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authorizeUrl;
    private String tokenUrl;
    private String userInfoUrl;
    private String logoutUrl;
    private Map<String, String> extra = new HashMap<>();

    public IdpType getType() {
        return type;
    }

    public void setType(IdpType type) {
        this.type = type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public void setAuthorizeUrl(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getUserInfoUrl() {
        return userInfoUrl;
    }

    public void setUserInfoUrl(String userInfoUrl) {
        this.userInfoUrl = userInfoUrl;
    }

    public String getLogoutUrl() {
        return logoutUrl;
    }

    public void setLogoutUrl(String logoutUrl) {
        this.logoutUrl = logoutUrl;
    }

    public Map<String, String> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra;
    }

    public String getExtra(String key) {
        return extra == null ? null : extra.get(key);
    }

    /** 取 extra 配置（去空白；缺失或空串返回 null，便于调用方按「未配置」处理）。 */
    public String extra(String key) {
        String value = getExtra(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
