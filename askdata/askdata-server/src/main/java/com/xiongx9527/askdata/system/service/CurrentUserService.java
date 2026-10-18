package com.xiongx9527.askdata.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 当前登录用户上下文与权限判定（移植 SQLBot apps/system/schemas/permission.py 语义）：
 * - isAdmin：{@code id==1 && account=='admin'}；
 * - ws_admin：isAdmin 或当前工作空间 weight > 0；
 * - 管理员专属接口非管理员调用时拒绝（HTTP 400 + 文本，前端直接展示）。
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    private final UserWsRepository userWsRepository;

    public CurrentUserService(UserRepository userRepository, UserWsRepository userWsRepository) {
        this.userRepository = userRepository;
        this.userWsRepository = userWsRepository;
    }

    public long uid() {
        // Sa-Token 拦截器已保证登录；此处仍可能抛 NotLoginException（由异常处理器转 401）
        return StpUtil.getLoginIdAsLong();
    }

    public Map<String, Object> currentUser() {
        return userRepository.findById(uid())
                .orElseThrow(() -> new BadRequestException("Login user does not exist"));
    }

    public boolean isAdmin(Map<String, Object> user) {
        return Rows.lng(user, "id", 0L) == 1L && "admin".equals(Rows.str(user, "account"));
    }

    public long oidOf(Map<String, Object> user) {
        return Rows.lng(user, "oid", 0L);
    }

    public int weightInCurrentWorkspace(Map<String, Object> user) {
        return userWsRepository.weightOf(Rows.lng(user, "id", 0L), oidOf(user));
    }

    public void requireAdmin() {
        if (!isAdmin(currentUser())) {
            throw new BadRequestException("This operation is only allowed for administrators");
        }
    }

    /** 对齐 SQLBot {@code role=['ws_admin']}：管理员或工作空间管理员。 */
    public void requireWsAdmin() {
        Map<String, Object> user = currentUser();
        if (isAdmin(user)) {
            return;
        }
        if (weightInCurrentWorkspace(user) == 0) {
            throw new BadRequestException("This operation is only allowed for workspace administrators");
        }
    }
}
