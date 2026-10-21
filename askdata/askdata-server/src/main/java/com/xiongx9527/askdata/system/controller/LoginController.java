package com.xiongx9527.askdata.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.repository.UserRepository;
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
 * - POST /login/change-pwd：account/pwd/new_pwd 同样 AES 混淆。
 * - POST /login/logout：Sa-Token 注销当前 token。
 * 错误结构与 SQLBot 一致：HTTP 400 + 文本 detail；文案迁到消息包（i18n，A4）。
 */
@RestController
@RequestMapping({"api/v1"})
public class LoginController {

    private final UserRepository userRepository;

    private final MessageResolver messages;

    private final AuditRecorder auditRecorder;

    public LoginController(UserRepository userRepository, MessageResolver messages, AuditRecorder auditRecorder) {
        this.userRepository = userRepository;
        this.messages = messages;
        this.auditRecorder = auditRecorder;
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

            StpUtil.login(toLong(user.get("id")));
            auditRecorder.recordLogin(account, AuditOperationType.LOGIN, "success", null,
                    System.currentTimeMillis() - start);
            return Result.ok(new TokenVO(StpUtil.getTokenValue()));
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
            userRepository.updatePassword(toLong(userRow.get().get("id")), CryptoUtil.md5Hex(newPwd));
            auditRecorder.recordLogin(account, AuditOperationType.UPDATE_PWD, "success", null,
                    System.currentTimeMillis() - start);
            return Result.ok(null);
        } catch (Throwable t) {
            auditRecorder.recordLogin(account, AuditOperationType.UPDATE_PWD, "failed", t.getMessage(),
                    System.currentTimeMillis() - start);
            throw t;
        }
    }

    @PostMapping({"/login/logout"})
    public Result<Void> logout() {
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
