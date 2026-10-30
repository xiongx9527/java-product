package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.Maps;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.AuthFailureException;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.ForbiddenException;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 当前登录用户上下文与权限判定（移植 SQLBot apps/system/schemas/permission.py 语义）：
 * - isAdmin（A24）：按<b>角色</b>判定——用户在默认工作空间（种子数据 id=1）持有管理员角色
 *   （{@code sys_user_ws.weight > 0}）即为系统管理员，不再依赖 {@code id==1 && account=='admin'}
 *   的身份硬编码；账号改名/迁移后管理员身份仍由角色数据决定；
 * - ws_admin：isAdmin 或当前工作空间 weight > 0；
 * - 管理员专属接口非管理员调用时拒绝（HTTP 400 + 文本，前端直接展示）。
 *
 * <p><b>跨通道</b>：SQLBot 把校验通过的当前用户挂在 {@code request.state.current_user}，
 * 业务层统一从该处取用户，因此 API Key（{@code sk}）与嵌入助手（{@code assistant} /
 * {@code embedded}）两条支线通道的请求同样能访问业务接口。askdata 侧对应的载体是
 * {@link AuthContext}（由 {@code AuthChannelFilter} 填充）；主通道（Sa-Token）没有
 * 支线 token，此时仍走 Sa-Token 登录态。</p>
 */
@Service
public class CurrentUserService {
    @Autowired
    private MessageResolver messageResolver;

    private final UserRepository userRepository;

    private final UserWsRepository userWsRepository;

    public CurrentUserService(UserRepository userRepository, UserWsRepository userWsRepository) {
        this.userRepository = userRepository;
        this.userWsRepository = userWsRepository;
    }

    public long uid() {
        // 支线通道（API Key / 嵌入助手）：用户已在 AuthChannelFilter 校验并放入 AuthContext。
        AuthContext context = AuthContext.current();
        if (context != null && context.getCurrentUser() != null) {
            return Rows.lng(context.getCurrentUser(), "id", 0L);
        }
        // 主通道：Sa-Token 拦截器已保证登录；此处仍可能抛 NotLoginException（由异常处理器转 401）
        return StpUtil.getLoginIdAsLong();
    }

    public Map<String, Object> currentUser() {
        // 支线通道：直接用鉴权时确定好的当前用户（对齐 SQLBot request.state.current_user）。
        //  - sk / embedded 通道：鉴权时取的是 sys_user 整行（含 system_variables，行权限依赖该列）；
        //  - assistant 通道：SQLBot 用的是合成用户 sqlbot-inner-assistant（无库行），保持原样。
        AuthContext context = AuthContext.current();
        if (context != null && context.getCurrentUser() != null) {
            return context.getCurrentUser();
        }
        return userRepository.findById(uid())
                .orElseThrow(() -> new BadRequestException(messageResolver.get("user.login.not.exists")));
    }

    /**
     * A22：每请求复查用户 {@code status} / {@code oid}（对齐 SQLBot
     * {@code TokenMiddleware.validateToken}：每个请求都从库里取当前用户并校验）。
     *
     * <p>效果：账号被禁用、或被解除工作空间绑定后<b>立即</b>失效，而不是等 token 自然过期。
     * 助手通道（{@code sqlbot-inner-assistant} 合成用户，无库行）不适用该校验，按 SQLBot
     * 的 {@code validateAssistant} 语义跳过。</p>
     *
     * @throws AuthFailureException 账号已禁用或未关联工作空间（HTTP 401）
     */
    /** 助手通道合成用户账号名（SQLBot {@code sqlbot-inner-assistant}，无 sys_user 行）。 */
    public static final String INNER_ASSISTANT_ACCOUNT = "sqlbot-inner-assistant";

    /** SQLBot mcp_assistant 合成用户账号名（id=-1，无 sys_user 行）。 */
    public static final String MCP_ASSISTANT_ACCOUNT = "sqlbot-mcp-assistant";

    public void requireActiveSession() {
        Map<String, Object> user = currentUser();
        // 助手通道是合成用户（无 sys_user 行），无 status/oid 可校验
        if (INNER_ASSISTANT_ACCOUNT.equals(Rows.str(user, "account"))
                || MCP_ASSISTANT_ACCOUNT.equals(Rows.str(user, "account"))) {
            return;
        }
        if (Rows.lng(user, "status", 0L) != 1L) {
            throw new AuthFailureException(messageResolver.get("i18n_login.user_disable",
                    Maps.of("msg", "")));
        }
        if (oidOf(user) == 0L) {
            throw new AuthFailureException(messageResolver.get("i18n_login.no_associated_ws",
                    Maps.of("msg", "")));
        }
    }

    /** 系统默认工作空间 id（种子数据）：该工作空间的管理员角色即系统管理员角色。 */
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    /**
     * A24：管理员判定按角色而非身份硬编码。
     *
     * <p>角色数据来自 {@code sys_user_ws.weight}（0=普通成员，>0=管理员）；系统管理员即
     * 在<b>默认工作空间</b>持有管理员角色的用户。种子管理员（weight=100）仍为管理员，
     * 而普通工作空间管理员不会因此获得系统管理员权限（保持原有权限边界不扩大）。</p>
     */
    public boolean isAdmin(Map<String, Object> user) {
        long uid = Rows.lng(user, "id", 0L);
        if (uid <= 0L) {
            return false;
        }
        return userWsRepository.weightOf(uid, DEFAULT_WORKSPACE_ID) > 0;
    }

    public long oidOf(Map<String, Object> user) {
        return Rows.lng(user, "oid", 0L);
    }

    public int weightInCurrentWorkspace(Map<String, Object> user) {
        return userWsRepository.weightOf(Rows.lng(user, "id", 0L), oidOf(user));
    }

    public void requireAdmin() {
        if (!isAdmin(currentUser())) {
            // A4/A13：越权访问是权限语义，必须 403（此前抛 400 会与参数错误混淆）
            throw new ForbiddenException(messageResolver.get("perm.admin.only"));
        }
    }

    /** 对齐 SQLBot {@code role=['ws_admin']}：管理员或工作空间管理员。 */
    public void requireWsAdmin() {
        Map<String, Object> user = currentUser();
        if (isAdmin(user)) {
            return;
        }
        if (weightInCurrentWorkspace(user) == 0) {
            throw new ForbiddenException(messageResolver.get("perm.ws.admin.only"));
        }
    }
}
