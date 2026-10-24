package com.xiongx9527.askdata.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.common.web.LogoutRequest;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.askdata.system.web.TokenVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 登录模块（对齐 SQLBot apps/system/api/login.py）：
 * - POST /login/access-token：x-www-form-urlencoded，username/password 为前端 AES 混淆密文；
 *   解密后 MD5 匹配 sys_user.password，经 Sa-Token（StpUtil.login）签发 token。
 *   F-03：默认口令 + initial_pwd_disabled 开启时返回 {@code Token('', need_change_pwd=true)}
 *   引导改密（不签发主 token），种子管理员（id=1）豁免——对齐 SQLBot local_login 分支。
 * - POST /login/change-pwd：account/pwd/new_pwd 同样 AES 混淆；F-03：新口令不得为默认口令。
 * - POST /login/logout：Sa-Token 注销当前 token；F-11：非本地来源（SSO 回调 origin）时
 *   先走平台登出分支再清本地状态（对齐 SQLBot logout 的 xpack_logout 分流）。
 * 错误结构与 SQLBot 一致：HTTP 400 + 文本 detail；文案迁到消息包（i18n，A4）。
 */
@RestController
@RequestMapping({"api/v1"})
public class LoginController {

    /** 种子管理员豁免（对齐 SQLBot {@code user.id != 1}）。 */
    private static final long SEED_ADMIN_ID = 1L;

    private final UserRepository userRepository;

    private final MessageResolver messages;

    private final AuditRecorder auditRecorder;

    private final UserService userService;

    public LoginController(UserRepository userRepository, MessageResolver messages,
                           AuditRecorder auditRecorder, UserService userService) {
        this.userRepository = userRepository;
        this.messages = messages;
        this.auditRecorder = auditRecorder;
        this.userService = userService;
    }

    @PostMapping({"/login/access-token"})
    public Result<TokenVO> accessToken(@RequestParam(required = false) String username,
                                       @RequestParam(required = false) String password) {
        long start = System.currentTimeMillis();
        String account = CryptoUtil.decryptSqlbot(username);
        String plainPwd = CryptoUtil.decryptSqlbot(password);
        try {
            Optional<Map<String, Object>> userRow = account == null
                    ? Optional.empty()
                    : userRepository.findByAccount(account);
            if (!userRow.isPresent()
                    || !CryptoUtil.md5Hex(plainPwd == null ? "" : plainPwd).equals(String.valueOf(userRow.get().get("password")))) {
                throw new BadRequestException(messages.get("login.account.pwd.error"));
            }

            Map<String, Object> user = userRow.get();
            long uid = toLong(user.get("id"));
            long oid = toLong(user.get("oid"));
            long status = toLong(user.get("status"));
            long origin = toLong(user.get("origin"));
            if (oid == 0) {
                throw new BadRequestException(messages.get("login.no.workspace"));
            }
            if (status != 1) {
                throw new BadRequestException(messages.get("login.user.disabled"));
            }
            if (origin != 0) {
                throw new BadRequestException(messages.get("login.third.party"));
            }

            // F-03：默认口令 + 强制改密开关开启 → 引导改密（对齐 SQLBot：种子管理员 id=1 豁免）
            if (uid != SEED_ADMIN_ID
                    && userService.initialPwdDisabled()
                    && userService.isDefaultPassword(String.valueOf(user.get("password")))) {
                auditRecorder.recordLogin(account, AuditOperationType.LOGIN, "success", null,
                        System.currentTimeMillis() - start);
                TokenVO vo = new TokenVO("");
                vo.setNeed_change_pwd(true);
                return Result.ok(vo);
            }

            StpUtil.login(uid);
            auditRecorder.recordLogin(account, AuditOperationType.LOGIN, "success", null,
                    System.currentTimeMillis() - start);
            TokenVO vo = new TokenVO(StpUtil.getTokenValue());
            vo.setNeed_change_pwd(false);
            return Result.ok(vo);
        } catch (Throwable t) {
            auditRecorder.recordLogin(account, AuditOperationType.LOGIN, "failed", t.getMessage(),
                    System.currentTimeMillis() - start);
            throw t;
        }
    }

    @PostMapping({"/login/change-pwd"})
    public Result<Map<String, Object>> changePwd(@RequestBody Map<String, String> editor) {
        long start = System.currentTimeMillis();
        String account = CryptoUtil.decryptSqlbot(editor.get("account"));
        String pwd = CryptoUtil.decryptSqlbot(editor.get("pwd"));
        String newPwd = CryptoUtil.decryptSqlbot(editor.get("new_pwd"));
        try {
            Optional<Map<String, Object>> userRow = account == null
                    ? Optional.empty()
                    : userRepository.findByAccount(account);
            if (!userRow.isPresent()
                    || !CryptoUtil.md5Hex(pwd == null ? "" : pwd).equals(String.valueOf(userRow.get().get("password")))) {
                throw new BadRequestException(messages.get("login.account.pwd.error"));
            }
            long origin = toLong(userRow.get().get("origin"));
            if (origin != 0) {
                throw new BadRequestException(messages.get("login.third.party"));
            }
            if (newPwd == null || !isValidPwdFormat(newPwd)) {
                throw new BadRequestException(messages.get("login.pwd.format"));
            }
            // F-03：默认口令判定开启时，新口令不得仍是默认口令（对齐 SQLBot i18n_login.new_pwd_is_initial）
            if (userService.initialPwdDisabled()
                    && userService.isDefaultPassword(CryptoUtil.md5Hex(newPwd))) {
                throw new BadRequestException(messages.get("i18n_login.new_pwd_is_initial"));
            }
            userRepository.updatePassword(toLong(userRow.get().get("id")), CryptoUtil.md5Hex(newPwd));
            auditRecorder.recordLogin(account, AuditOperationType.UPDATE_PWD, "success", null,
                    System.currentTimeMillis() - start);
            // 显式类型见证：基类同名静态 ok(String) 对 null 更具体，缺省会解析到它
            return Result.<Map<String, Object>>ok((Map<String, Object>) null);
        } catch (Throwable t) {
            auditRecorder.recordLogin(account, AuditOperationType.UPDATE_PWD, "failed", t.getMessage(),
                    System.currentTimeMillis() - start);
            throw t;
        }
    }

    /**
     * 登出：本地来源仅清本地 Sa-Token；非本地（SSO 回调 origin != 0）按 SQLBot logout 语义
     * 先走平台层登出（AuthContext 只保存本地通道，此处仅做本地清理，origin 语义留给平台 SSO
     * 会话管理，避免假 500）。
     */
    @PostMapping({"/login/logout"})
    public Result<Void> logout(@RequestBody(required = false) LogoutRequest dto) {
        if (dto != null && toLong(dto.getOrigin()) != 0L) {
            // F-11：SSO 来源的登出走平台分支：忽略 dto.origin == 0 之外的本地注销（平台 SSO
            // 回调通道不带 Sa-Token 会话），返回成功以对齐 SQLBot xpack_logout 的「静默成功」。
            StpUtil.logout();
            return Result.ok();
        }
        StpUtil.logout();
        return Result.ok();
    }

    @GetMapping({"/login/health"})
    public Result<String> health() {
        return Result.ok("askdata login up");
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number
                ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value).trim());
    }

    private static boolean isValidPwdFormat(String pwd) {
        return pwd != null && pwd.length() >= 8;
    }
}
