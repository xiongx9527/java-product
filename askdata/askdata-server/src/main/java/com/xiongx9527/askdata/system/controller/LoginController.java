package com.xiongx9527.askdata.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
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
 * 错误结构与 SQLBot 一致：HTTP 400 + 文本 detail。
 */
@RestController
@RequestMapping({"api/v1"})
public class LoginController {

    /** SQLBot 登录失败文案（i18n_login.account_pwd_error，缺省英文）。 */
    private static final String ACCOUNT_PWD_ERROR = "Account or password error";

    private final UserRepository userRepository;

    public LoginController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostMapping({"/login/access-token"})
    public Result<TokenVO> accessToken(@RequestParam(required = false) String username,
                                       @RequestParam(required = false) String password) {
        String account = CryptoUtil.decryptSqlbot(username);
        String plainPwd = CryptoUtil.decryptSqlbot(password);
        Optional<Map<String, Object>> userRow = account == null
                ? Optional.empty()
                : userRepository.findByAccount(account);
        if (!userRow.isPresent()
                || !CryptoUtil.md5Hex(plainPwd == null ? "" : plainPwd).equals(String.valueOf(userRow.get().get("password")))) {
            throw new BadRequestException(ACCOUNT_PWD_ERROR);
        }

        Map<String, Object> user = userRow.get();
        long oid = toLong(user.get("oid"));
        long status = toLong(user.get("status"));
        long origin = toLong(user.get("origin"));
        if (oid == 0) {
            throw new BadRequestException("Login role does not have a workspace, please contact the administrator");
        }
        if (status != 1) {
            throw new BadRequestException("User has been disabled, please contact the administrator");
        }
        if (origin != 0) {
            throw new BadRequestException("Local account is not allowed to log in from a third-party platform, please contact the administrator");
        }

        // D10：Sa-Token 签发登录态（loginId = 用户 id），token 由前端以 X-SQLBOT-TOKEN: Bearer 回传
        StpUtil.login(toLong(user.get("id")));
        return Result.ok(new TokenVO(StpUtil.getTokenValue()));
    }

    @PostMapping({"/login/change-pwd"})
    public Result<Map<String, Object>> changePwd(@RequestBody Map<String, String> editor) {
        String account = CryptoUtil.decryptSqlbot(editor.get("account"));
        String pwd = CryptoUtil.decryptSqlbot(editor.get("pwd"));
        String newPwd = CryptoUtil.decryptSqlbot(editor.get("new_pwd"));
        Optional<Map<String, Object>> userRow = account == null
                ? Optional.empty()
                : userRepository.findByAccount(account);
        if (!userRow.isPresent()
                || !CryptoUtil.md5Hex(pwd == null ? "" : pwd).equals(String.valueOf(userRow.get().get("password")))) {
            throw new BadRequestException(ACCOUNT_PWD_ERROR);
        }
        long origin = toLong(userRow.get().get("origin"));
        if (origin != 0) {
            throw new BadRequestException("Local account is not allowed to log in from a third-party platform, please contact the administrator");
        }
        if (newPwd == null || !isValidPwdFormat(newPwd)) {
            throw new BadRequestException("The password format is incorrect");
        }
        userRepository.updatePassword(toLong(userRow.get().get("id")), CryptoUtil.md5Hex(newPwd));
        return Result.ok(null);
    }

    @PostMapping({"/login/logout"})
    public Result<Void> logout() {
        // Sa-Token 注销当前请求携带的登录态
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
        // 与 SQLBot check_pwd_format 的最低要求一致：至少 8 位
        return pwd != null && pwd.length() >= 8;
    }
}
