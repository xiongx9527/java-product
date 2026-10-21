package com.xiongx9527.askdata.system.platform;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 企业平台用户同步服务（对齐 SQLBot xpack {@code sqlbot_xpack/platform/user_api.py} 的
 * {@code POST /system/platform/user/sync}）。
 *
 * <p>请求体（前端 {@code views/system/user/SyncUserDing.vue} 逐字一致）：</p>
 * <pre>
 * {"user_list":[{"id":"<平台用户id>","name":"...","email":"..."}], "origin":6, "cover":false}
 * </pre>
 *
 * <p>语义：把企业 IM（企业微信/钉钉/飞书）的用户批量同步为本地 {@code sys_user}，
 * 并维护 {@code sys_user_platform} 绑定（uid / origin / platform_uid）。
 * <b>幂等</b>：同一 (origin, platform_uid) 重复同步不会产生重复用户。</p>
 */
@Service
public class PlatformSyncService {

    private final PlatformRepository platformRepository;

    private final UserRepository userRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    public PlatformSyncService(PlatformRepository platformRepository,
                               UserRepository userRepository,
                               CurrentUserService currentUserService,
                               IdGenerator idGenerator) {
        this.platformRepository = platformRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
    }

    /**
     * 批量同步企业平台用户。
     *
     * @param body {@code {user_list:[{id,name,email}], origin, cover}}
     * @return {@code {total, created, updated, skipped}}
     */
    @Transactional
    public Map<String, Object> sync(Map<String, Object> body) {
        currentUserService.requireAdmin();
        int origin = intOf(body.get("origin"), 0);
        boolean cover = boolOf(body.get("cover"), false);
        List<Map<String, Object>> userList = Rows.mapList(body.get("user_list"));
        long oid = currentUserService.oidOf(currentUserService.currentUser());

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> platformUser : userList) {
            String platformUid = Rows.str(platformUser, "id");
            if (platformUid == null || platformUid.trim().isEmpty()) {
                skipped++;
                continue;
            }
            platformUid = platformUid.trim();
            String account = Rows.str(platformUser, "name");
            String email = Rows.str(platformUser, "email");
            if (account == null || account.trim().isEmpty()) {
                account = platformUid;
            }

            Optional<Map<String, Object>> binding = platformRepository.findBinding(origin, platformUid);
            Map<String, Object> localUser = null;
            if (binding.isPresent()) {
                localUser = userRepository.findById(Rows.lng(binding.get(), "uid", 0L)).orElse(null);
            }
            if (localUser == null) {
                localUser = userRepository.findByAccount(account).orElse(null);
            }

            if (localUser == null) {
                long uid = idGenerator.nextId();
                userRepository.insert(uid, account, account, CryptoUtil.md5Hex(""), email, oid, 1,
                        System.currentTimeMillis(), "zh-CN", origin, null);
                localUser = userRepository.findById(uid).orElse(null);
                platformRepository.upsertBinding(idGenerator.nextId(), uid, origin, platformUid);
                created++;
            } else {
                long uid = Rows.lng(localUser, "id", 0L);
                if (cover) {
                    userRepository.updateProfile(uid, account, email, Rows.intg(localUser, "status", 1),
                            origin, Rows.str(localUser, "system_variables"));
                }
                platformRepository.upsertBinding(idGenerator.nextId(), uid, origin, platformUid);
                updated++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(localUser, "id", 0L));
            item.put("account", account);
            item.put("email", email);
            item.put("platform_uid", platformUid);
            item.put("origin", origin);
            results.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", userList.size());
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("users", results);
        return result;
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return "true".equalsIgnoreCase(text) || "1".equals(text);
            }
        }
        return fallback;
    }
}
