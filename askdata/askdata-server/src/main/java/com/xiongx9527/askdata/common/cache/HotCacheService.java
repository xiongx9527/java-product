package com.xiongx9527.askdata.common.cache;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.embedded.AssistantRepository;
import com.xiongx9527.askdata.system.repository.ApiKeyRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 热点数据缓存（对应 SQLBot 审计 F-10 的 {@code get_user_info} / {@code get_api_key} /
 * {@code assistant info} 三类缓存）。
 *
 * <p>后端由 {@code askdata.cache.type} 决定（memory / redis / none），本类只负责
 * 「读缓存 → 未命中回落数据库 → 归一化 → 写缓存」，不感知具体后端。</p>
 *
 * <p><b>为什么要在缓存边界归一化 key</b>：JDBC 的 {@code queryForList} 返回
 * {@code LinkedCaseInsensitiveMap}（key 大写且忽略大小写），但经缓存（尤其 Redis 的 JSON
 * 往返）后会退化为普通 {@code LinkedHashMap}，此时 {@code get("password")} 之类的小写直取
 * 会静默返回 null。故统一归一为小写 key，使「缓存命中」与「直查数据库」两条路径的取值
 * 语义完全一致（同时也修掉既有直查路径上大小写依赖导致��取值问题）。</p>
 *
 * <p><b>失效</b>：写在各 Repository 的写方法上（{@code @CacheEvict}），
 * 覆盖所有服务层的变更路径，避免逐个调用点漏失效。</p>
 */
@Service
public class HotCacheService {

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AssistantRepository assistantRepository;

    public HotCacheService(UserRepository userRepository,
                            ApiKeyRepository apiKeyRepository,
                            AssistantRepository assistantRepository) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.assistantRepository = assistantRepository;
    }

    /** 用户信息（按 id）。 */
    @Cacheable(cacheNames = CacheNames.USER_INFO, key = "#id", unless = "#result == null")
    public Map<String, Object> userById(long id) {
        return userRepository.findById(id).map(Rows::lowerCaseKeys).orElse(null);
    }

    /** 用户信息（按 account）。 */
    @Cacheable(cacheNames = CacheNames.USER_INFO + ":by-account", key = "#account", unless = "#result == null")
    public Map<String, Object> userByAccount(String account) {
        return userRepository.findByAccount(account).map(Rows::lowerCaseKeys).orElse(null);
    }

    /** API Key 信息（按 access_key）。 */
    @Cacheable(cacheNames = CacheNames.API_KEY_INFO, key = "#accessKey", unless = "#result == null")
    public Map<String, Object> apiKeyByAccessKey(String accessKey) {
        return apiKeyRepository.findByAccessKey(accessKey).map(Rows::lowerCaseKeys).orElse(null);
    }

    /** 助手信息（按 id）。 */
    @Cacheable(cacheNames = CacheNames.ASSISTANT_INFO, key = "#id", unless = "#result == null")
    public Map<String, Object> assistantById(long id) {
        return assistantRepository.findById(id).map(Rows::lowerCaseKeys).orElse(null);
    }
}
