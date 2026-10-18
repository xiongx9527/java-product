package com.xiongx9527.askdata.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.web.UserInfoVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 用户/系统底座 — /user（对齐 SQLBot apps/system/api/user.py 的 info 端点）。
 * 鉴权由 Sa-Token 拦截器承担；管理员判定移植 {@code id==1 && account=='admin'}。
 */
@RestController
@RequestMapping({"api/v1"})
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping({"/user/info"})
    public Result<UserInfoVO> info() {
        long uid = toLong(StpUtil.getLoginId());
        long exp = System.currentTimeMillis() / 1000L + StpUtil.getTokenTimeout();
        long now = System.currentTimeMillis() / 1000L;
        return Result.ok(toUserInfo(userRepository.findById(uid), exp, now));
    }

    private UserInfoVO toUserInfo(Optional<Map<String, Object>> row, long exp, long now) {
        if (!row.isPresent()) {
            throw new BadRequestException("Login user does not exist");
        }
        Map<String, Object> user = row.get();
        long id = toLong(user.get("id"));
        String account = String.valueOf(user.get("account"));
        long oid = toLong(user.get("oid"));
        UserInfoVO vo = new UserInfoVO();
        vo.setId(id);
        vo.setAccount(account);
        vo.setName(String.valueOf(user.get("name")));
        vo.setEmail(String.valueOf(user.get("email")));
        vo.setOid(oid);
        String language = user.get("language") == null ? "zh-CN" : String.valueOf(user.get("language"));
        vo.setLanguage(language);
        vo.setStatus(toLong(user.get("status")));
        vo.setOrigin(toLong(user.get("origin")));
        vo.setWeight(userRepository.weightOf(id, oid));
        vo.setExp(exp);
        vo.setTime(now);
        vo.setIsAdmin(id == 1L && "admin".equals(account));
        return vo;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number
                ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value).trim());
    }
}
