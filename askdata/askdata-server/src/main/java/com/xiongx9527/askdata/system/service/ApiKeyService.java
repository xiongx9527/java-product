package com.xiongx9527.askdata.system.service;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 模块（对齐 SQLBot apps/system/api/apikey.py）：当前用户最多 5 个；
 * 仅本人可启停/删除。access_key/secret_key 生成等价 Python {@code secrets.token_urlsafe}。
 */
@Service
public class ApiKeyService {
    @Autowired
    private MessageResolver messageResolver;

    private static final int MAX_KEYS_PER_USER = 5;

    private final ApiKeyRepository apiKeyRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, CurrentUserService currentUserService,
                         IdGenerator idGenerator) {
        this.apiKeyRepository = apiKeyRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
    }

    public List<Map<String, Object>> list() {
        long uid = currentUserService.uid();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : apiKeyRepository.listByUid(uid)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("access_key", Rows.str(row, "access_key"));
            item.put("secret_key", Rows.str(row, "secret_key"));
            item.put("status", Rows.bool(row, "status", true));
            item.put("create_time", Rows.lng(row, "create_time", 0L));
            result.add(item);
        }
        return result;
    }

    public long create() {
        long uid = currentUserService.uid();
        if (apiKeyRepository.countByUid(uid) >= MAX_KEYS_PER_USER) {
            throw new BadRequestException(messageResolver.get("apikey.max.limit"));
        }
        long id = idGenerator.nextId();
        apiKeyRepository.insert(id, tokenUrlSafe(16), tokenUrlSafe(32), System.currentTimeMillis(), uid);
        return id;
    }

    public void changeStatus(long id, boolean status) {
        long uid = currentUserService.uid();
        Map<String, Object> key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("apikey.not.found")));
        requireOwner(key, uid);
        if (Rows.bool(key, "status", true) == status) {
            return;
        }
        apiKeyRepository.updateStatus(id, status);
    }

    public void delete(long id) {
        long uid = currentUserService.uid();
        Map<String, Object> key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("apikey.not.found")));
        requireOwner(key, uid);
        apiKeyRepository.delete(id);
    }

    private void requireOwner(Map<String, Object> key, long uid) {
        if (Rows.lng(key, "uid", 0L) != uid) {
            throw new BadRequestException(messageResolver.get("apikey.no.permission"));
        }
    }

    /** 等价 Python {@code secrets.token_urlsafe(nbytes)}（base64url，去 padding）。 */
    private String tokenUrlSafe(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
