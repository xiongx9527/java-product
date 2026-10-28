package com.xiongx9527.askdata.common.web;

/**
 * 401 来源标记（A13）：前端据此决定是否清除登录态。
 *
 * <p>应用存在多条鉴权通道：主账号会话（Sa-Token）、API Key、嵌入助手 token。它们的失败都返回
 * 401，但只有<b>主账号会话失效</b>才应该让前端清除 {@code user.token} 并跳转登录页；API Key /
 * 嵌入助手的 401 属于调用方凭证问题，不得影响浏览器里的主账号登录态。</p>
 *
 * <p>因此后端在 401 响应上附加 {@code X-Askdata-Auth-Failure} 头，前端读取后分流处理。
 * 为兼容未带该头的老响应，前端把「缺失该头」视为会话失效（保持既有行为）。</p>
 */
public final class AuthFailureSignal {

    /** 401 响应头名。 */
    public static final String HEADER = "X-Askdata-Auth-Failure";

    /** 主账号会话失效：前端应清除 token 并跳转登录。 */
    public static final String SESSION = "session";

    /** 支线通道（API Key / 嵌入助手）鉴权失败：前端只提示，不清除主账号登录态。 */
    public static final String CHANNEL = "channel";

    private AuthFailureSignal() {
    }
}
