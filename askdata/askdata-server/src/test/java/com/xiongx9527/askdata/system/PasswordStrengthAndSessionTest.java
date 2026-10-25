package com.xiongx9527.askdata.system;

import com.xiongx9527.askdata.common.web.AuthFailureException;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A22：会话与口令——每请求复查用户 status/oid；口令强度要求大小写、数字与特殊字符且长度 8-20。
 */
class PasswordStrengthAndSessionTest {

    // ----------------------------------------------------------------
    // 口令强度
    // ----------------------------------------------------------------

    @Test
    void strongPasswordAccepted() {
        assertTrue(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Abcdef1!"));
        assertTrue(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Passw0rd#"));
        // 20 位（含特殊字符）仍在范围内
        assertTrue(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Aa1!" + "x".repeat(16)));
    }

    @Test
    void weakPasswordRejected() {
        // 长度不足 8
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Ab1!"));
        // 超过 20
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Aa1!" + "x".repeat(20)));
        // 缺小写
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("ABCDEF1!"));
        // 缺大写
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("abcdef1!"));
        // 缺数字
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Abcdefg!"));
        // 缺特殊字符
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat("Abcdef12"));
        // null
        assertFalse(com.xiongx9527.askdata.system.controller.LoginController.isValidPwdFormat(null));
    }

    // ----------------------------------------------------------------
    // 每请求复查 status / oid
    // ----------------------------------------------------------------

    private CurrentUserService serviceWith(Map<String, Object> user) {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        Mockito.when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        CurrentUserService service = new CurrentUserService(userRepository,
                Mockito.mock(UserWsRepository.class));
        com.xiongx9527.askdata.common.i18n.MessageResolver resolver = Mockito.mock(
                com.xiongx9527.askdata.common.i18n.MessageResolver.class);
        Mockito.when(resolver.get(Mockito.any(String.class), Mockito.any(Map.class)))
                .thenReturn("disabled");
        ReflectionTestUtils.setField(service, "messageResolver", resolver);
        // 主通道（Sa-Token）用户视图：装 AuthContext 以避免单测依赖 Sa-Token 上下文
        com.xiongx9527.askdata.common.web.AuthContext context =
                new com.xiongx9527.askdata.common.web.AuthContext();
        context.setChannel(com.xiongx9527.askdata.common.web.AuthContext.Channel.BEARER);
        context.setCurrentUser(user);
        com.xiongx9527.askdata.common.web.AuthContext.set(context);
        return service;
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.xiongx9527.askdata.common.web.AuthContext.clear();
    }

    private static Map<String, Object> user(long status, long oid) {
        Map<String, Object> user = new HashMap<>();
        user.put("id", 1L);
        user.put("account", "alice");
        user.put("status", status);
        user.put("oid", oid);
        return user;
    }

    @Test
    void activeUserPasses() {
        assertDoesNotThrow(() -> serviceWith(user(1L, 3L)).requireActiveSession());
    }

    @Test
    void disabledUserIsRejected() {
        CurrentUserService service = serviceWith(user(0L, 3L));
        AuthFailureException ex = assertThrows(AuthFailureException.class,
                service::requireActiveSession);
        assertEquals("disabled", ex.getMessage());
    }

    @Test
    void userWithoutWorkspaceIsRejected() {
        CurrentUserService service = serviceWith(user(1L, 0L));
        assertThrows(AuthFailureException.class, service::requireActiveSession);
    }

    @Test
    void innerAssistantSynthesizedUserSkipsCheck() {
        Map<String, Object> synthesized = user(1L, 0L);
        synthesized.put("account", CurrentUserService.INNER_ASSISTANT_ACCOUNT);
        assertDoesNotThrow(() -> serviceWith(synthesized).requireActiveSession());
    }
}
