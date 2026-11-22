package com.xiongx9527.askdata.datasource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动期数据源配置迁移：把存量 configuration 从旧 AES-ECB 形态迁到 AES-256-GCM envelope
 * （对齐 SQLBot configuration_migration.py / 6dcad176）。
 *
 * <p>分页 CAS 更新，可中断续跑、幂等：</p>
 * <ul>
 *   <li>{@code gcm1:} envelope：已是新格式；keyId 为 active 则跳过，否则用新密钥重加密；</li>
 *   <li>含 {@code :} 但非 gcm1：旧 app-secret ECB 密文 → 解密为 JSON → 重新加密；</li>
 *   <li>其余：前端 ECB 密文或明文 JSON → {@link DatasourceCrypto#decodeConfiguration} → 重新加密。</li>
 * </ul>
 *
 * <p>用 {@code @Order(20)} 在口令迁移（10）之后、向量回填之前跑。</p>
 */
@Component
public class DatasourceConfigurationMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConfigurationMigrationRunner.class);

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DatasourceConfigurationMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Order(20)
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        DatasourceKeyring ring = DatasourceCrypto.keyring();
        if (ring == null) {
            log.warn("数据源密钥环未初始化，跳过配置迁移");
            return;
        }
        int migrated = 0;
        Long lastId = null;
        while (true) {
            List<Map<String, Object>> rows = lastId == null
                    ? jdbc.queryForList("SELECT id, configuration FROM core_datasource "
                            + "WHERE configuration IS NOT NULL AND configuration <> '' ORDER BY id LIMIT " + BATCH_SIZE)
                    : jdbc.queryForList("SELECT id, configuration FROM core_datasource "
                            + "WHERE configuration IS NOT NULL AND configuration <> '' AND id > ? "
                            + "ORDER BY id LIMIT " + BATCH_SIZE, lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                String stored = String.valueOf(row.get("configuration"));
                try {
                    String plain;
                    boolean needsRewrite;
                    if (DatasourceKeyring.isEnvelope(stored)) {
                        // 已是 gcm1：尝试解密认证；能解且 keyId==active 则跳过
                        String keyId = stored.split(":", 3)[1];
                        if (ring.activeId().equals(keyId)) {
                            // 验证可解（认证失败会抛错，让运维发现密钥问题）
                            ring.decrypt(stored);
                            continue;
                        }
                        plain = ring.decrypt(stored);
                        needsRewrite = true;
                    } else {
                        plain = legacyPlain(stored);
                        needsRewrite = true;
                    }
                    // 校验是 JSON 对象，避免把乱码写回
                    Object parsed = mapper.readValue(plain, Object.class);
                    if (!(parsed instanceof Map)) {
                        log.warn("数据源配置非 JSON 对象，跳过迁移: id={}", id);
                        continue;
                    }
                    if (needsRewrite) {
                        String next = ring.encrypt(plain);
                        jdbc.update("UPDATE core_datasource SET configuration = ? WHERE id = ? AND configuration = ?",
                                next, id, stored);
                        migrated++;
                    }
                } catch (Exception e) {
                    log.warn("数据源配置迁移失败，保留原值待下次重试: id={}, reason={}", id, e.getMessage());
                }
            }
            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
        }
        if (migrated > 0) {
            log.info("数据源配置迁移完成：{} 条 AES-ECB → AES-256-GCM", migrated);
        }
    }

    /** legacy 密文/明文 → 明文 JSON（复用 DatasourceCrypto 的旧形态解码，但不依赖 keyring）。 */
    private String legacyPlain(String stored) {
        String plain = DatasourceCrypto.crypto().decryptWithAppSecret(stored);
        if (plain != null && (plain.trim().startsWith("{") || plain.trim().startsWith("["))) {
            return plain;
        }
        plain = com.xiongx9527.askdata.system.auth.CryptoUtil.decryptSqlbot(stored);
        if (plain != null) {
            return plain;
        }
        return stored;
    }
}
