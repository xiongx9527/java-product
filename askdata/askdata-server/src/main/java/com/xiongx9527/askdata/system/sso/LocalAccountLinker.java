package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.system.service.UserService;

/**
 * 外部身份 → 本地账号开通/绑定（A7）：复用 UserService.provisionExternalUser 自动开通，并返回本地 uid。
 */
public class LocalAccountLinker {

    private final UserService userService;
    private final long defaultWorkspaceId;

    public LocalAccountLinker(UserService userService, long defaultWorkspaceId) {
        this.userService = userService;
        this.defaultWorkspaceId = defaultWorkspaceId;
    }

    public long link(ExternalIdentity identity) {
        String account = identity.getAccount();
        if (account == null || account.isEmpty()) {
            account = identity.getExternalId();
        }
        return userService.provisionExternalUser(account, identity.getName(), identity.getEmail());
    }
}
