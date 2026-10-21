package com.xiongx9527.askdata.common.web;

import java.util.Map;

/**
 * 当前请求的鉴权上下文（对齐 SQLBot 的 {@code request.state.current_user} / {@code request.state.assistant}）。
 *
 * <p>SQLBot 的 TokenMiddleware 把校验通过的当前用户与助手信息挂在 request.state 上，
 * 业务层再从 request.state 取。askdata 侧用 Servlet 作用域的 {@link ThreadLocal} 承载，
 * 由 {@link AuthChannelFilter} 在请求开始时清理、结束时释放，避免线程复用导致串号。</p>
 */
public final class AuthContext {

    /** 鉴权通道标识。 */
    public enum Channel {
        /** 主通道：{@code X-SQLBOT-TOKEN: Bearer <token>}（Sa-Token）。 */
        BEARER,
        /** API Key 通道：{@code X-SQLBOT-ASK-TOKEN: sk <jwt>}。 */
        ASK_TOKEN,
        /** 嵌入助手通道：{@code X-SQLBOT-ASSISTANT-TOKEN: assistant|embedded <jwt>}。 */
        ASSISTANT_TOKEN
    }

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private Channel channel;

    private Map<String, Object> currentUser;

    private Map<String, Object> assistant;

    private String requestOrigin;

    public static AuthContext current() {
        return HOLDER.get();
    }

    public static void set(AuthContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前请求的当前用户视图（跨通道）。
     *
     * <p>对齐 SQLBot 的 {@code request.state.current_user}：三种通道（主通道 / API Key /
     * 嵌入助手）在校验通过后都会把用户视图放进 {@link AuthContext}，业务层据此取用户，
     * 而不必关心请求走的是哪条通道。</p>
     *
     * @return 当前用户视图；没有任何通道识别出用户时返回 null
     */
    public static Map<String, Object> currentUser() {
        AuthContext context = HOLDER.get();
        if (context == null) {
            return null;
        }
        return context.getCurrentUser();
    }

    /**
     * 当前请求的助手信息（仅嵌入助手通道有值）。
     *
     * <p>对齐 SQLBot 的 {@code request.state.assistant}。</p>
     */
    public static Map<String, Object> currentAssistant() {
        AuthContext context = HOLDER.get();
        if (context == null) {
            return null;
        }
        return context.getAssistant();
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public Map<String, Object> getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(Map<String, Object> currentUser) {
        this.currentUser = currentUser;
    }

    public Map<String, Object> getAssistant() {
        return assistant;
    }

    public void setAssistant(Map<String, Object> assistant) {
        this.assistant = assistant;
    }

    public String getRequestOrigin() {
        return requestOrigin;
    }

    public void setRequestOrigin(String requestOrigin) {
        this.requestOrigin = requestOrigin;
    }
}
