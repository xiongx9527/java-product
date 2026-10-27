package com.xiongx9527.askdata.system.platform;

import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A32：{@code POST /system/platform/user/sync} 幂等同步企业 IM 用户。
 *
 * <p>回归点：同一 (origin, platform_uid) 重复同步不得产生重复本地用户；响应必须带
 * {@code total/created/updated/skipped}（前端 SyncUserDing.vue 的汇总提示）。</p>
 */
class PlatformSyncServiceTest {

    private PlatformRepository platformRepository;
    private UserRepository userRepository;
    private CurrentUserService currentUserService;
    private PlatformSyncService service;

    /** 内存 sys_user：account → row。 */
    private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    /** 内存 sys_user_platform：origin|platform_uid → uid。 */
    private final Map<String, Long> bindings = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000L);

    @BeforeEach
    void setUp() {
        platformRepository = mock(PlatformRepository.class);
        userRepository = mock(UserRepository.class);
        currentUserService = mock(CurrentUserService.class);
        service = new PlatformSyncService(platformRepository, userRepository,
                currentUserService, new IdGenerator());

        when(currentUserService.currentUser()).thenReturn(java.util.Collections.singletonMap("id", 1L));
        when(currentUserService.oidOf(org.mockito.ArgumentMatchers.<Map<String, Object>>any())).thenReturn(7L);

        when(platformRepository.findBinding(anyInt(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, Integer.class) + "|" + invocation.getArgument(1, String.class);
            Long uid = bindings.get(key);
            return uid == null ? Optional.empty()
                    : Optional.of(java.util.Collections.singletonMap("uid", uid));
        });
        when(userRepository.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0, Long.class);
            return users.values().stream().filter(u -> ((Number) u.get("id")).longValue() == id).findFirst();
        });
        when(userRepository.findByAccount(anyString())).thenAnswer(invocation -> {
            String account = invocation.getArgument(0, String.class);
            return Optional.ofNullable(users.get(account));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            long id = invocation.getArgument(0, Long.class);
            String account = invocation.getArgument(1, String.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("account", account);
            row.put("name", invocation.getArgument(2, String.class));
            row.put("email", invocation.getArgument(4, String.class));
            row.put("oid", invocation.getArgument(5, Long.class));
            row.put("status", invocation.getArgument(6, Integer.class));
            row.put("origin", invocation.getArgument(9, Integer.class));
            users.put(account, row);
            return null;
        }).when(userRepository).insert(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.doAnswer(invocation -> {
            bindings.put(invocation.getArgument(2, Integer.class) + "|"
                    + invocation.getArgument(3, String.class), invocation.getArgument(1, Long.class));
            return null;
        }).when(platformRepository).upsertBinding(anyLong(), anyLong(), anyInt(), anyString());
    }

    private Map<String, Object> body(String origin, boolean cover, Object... idNameEmail) {
        List<Map<String, Object>> userList = new ArrayList<>();
        for (int i = 0; i < idNameEmail.length; i += 3) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", idNameEmail[i]);
            u.put("name", idNameEmail[i + 1]);
            u.put("email", idNameEmail[i + 2]);
            userList.add(u);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_list", userList);
        body.put("origin", origin);
        body.put("cover", cover);
        return body;
    }

    @Test
    void nonAdminCannotSync() {
        org.mockito.Mockito.doThrow(new com.xiongx9527.askdata.common.web.BadRequestException("perm.admin.only"))
                .when(currentUserService).requireAdmin();
        assertThrows(com.xiongx9527.askdata.common.web.BadRequestException.class,
                () -> service.sync(body("6", false, "u1", "张三", "z@a.com")));
        verify(userRepository, never()).insert(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void firstSyncCreatesUsersAndBindings() {
        Map<String, Object> result = service.sync(body("6", false,
                "u1", "张三", "z@a.com", "u2", "李四", "l@a.com"));

        assertEquals(2, result.get("total"));
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("updated"));
        assertEquals(0, result.get("skipped"));
        assertEquals(2, users.size());
        assertEquals(2, bindings.size());
        verify(userRepository, times(2)).insert(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
        verify(platformRepository, times(2)).upsertBinding(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void repeatedSyncIsIdempotent() {
        Map<String, Object> payload = body("7", false, "u1", "张三", "z@a.com");
        service.sync(payload);
        Map<String, Object> second = service.sync(payload);

        assertEquals(1, second.get("total"));
        assertEquals(0, second.get("created"));
        assertEquals(1, second.get("updated"));
        assertEquals(1, users.size(), "重复同步不得产生重复用户");
        verify(userRepository, times(1)).insert(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void coverUpdatesProfilePlainSyncDoesNot() {
        service.sync(body("6", false, "u1", "张三", "z@a.com"));
        service.sync(body("6", true, "u1", "张三丰", "zs@a.com"));
        verify(userRepository, times(1)).updateProfile(anyLong(), eq("张三丰"), eq("zs@a.com"),
                anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());

        service.sync(body("6", false, "u1", "王五", "w@a.com"));
        verify(userRepository, times(1)).updateProfile(anyLong(), anyString(), anyString(),
                anyInt(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void missingOriginIsRejected() {
        Map<String, Object> payload = body("6", false, "u1", "张三", "z@a.com");
        payload.remove("origin");

        com.xiongx9527.askdata.common.web.BadRequestException error = assertThrows(
                com.xiongx9527.askdata.common.web.BadRequestException.class, () -> service.sync(payload));
        assertTrue(error.getMessage().contains("origin"), "错误信息需点明 origin: " + error.getMessage());
        assertTrue(users.isEmpty(), "origin 缺失时不得落库");
    }

    @Test
    void zeroOriginIsRejected() {
        com.xiongx9527.askdata.common.web.BadRequestException error = assertThrows(
                com.xiongx9527.askdata.common.web.BadRequestException.class,
                () -> service.sync(body("0", false, "u1", "张三", "z@a.com")));
        assertTrue(error.getMessage().contains("origin"), "错误信息需点明 origin: " + error.getMessage());
        assertTrue(users.isEmpty(), "origin=0 时不得落库");

        assertThrows(com.xiongx9527.askdata.common.web.BadRequestException.class,
                () -> service.sync(body(" ", false, "u1", "张三", "z@a.com")));
        assertTrue(users.isEmpty(), "origin 空白时不得落库");
    }

    @Test
    void syncedUserNeverGetsEmptyPasswordHash() {
        service.sync(body("6", false, "u1", "张三", "z@a.com"));

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(userRepository).insert(anyLong(), anyString(), anyString(), password.capture(), anyString(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());

        String hash = password.getValue();
        assertTrue(hash != null && !hash.trim().isEmpty(), "同步用户密码哈希不得为空");
        assertEquals(false, CryptoUtil.md5Hex("").equals(hash), "同步用户不得写入 md5(\"\")");
    }

    @Test
    void blankPlatformUidIsSkipped() {
        Map<String, Object> result = service.sync(body("6", false, "  ", "无名", null));
        assertEquals(1, result.get("total"));
        assertEquals(0, result.get("created"));
        assertEquals(1, result.get("skipped"));
        assertTrue(users.isEmpty());
    }
}
