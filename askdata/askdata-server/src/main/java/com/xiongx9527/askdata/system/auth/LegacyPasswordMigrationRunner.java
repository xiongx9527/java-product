package com.xiongx9527.askdata.system.auth;

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
 * 启动期口令迁移：把遗留 MD5 包装为加盐 Argon2id（对齐 SQLBot alembic 075 / 8b4097f2）。
 *
 * <p>不可逆迁移（不回退到 MD5）：legacy MD5 → {@code WRAPPED_MD5 + Argon2id(MD5)}。
 * 已包装 / 原生 Argon2id 行自动跳过，因此可中断续跑、每次启动幂等。</p>
 *
 * <p>用 {@link ApplicationReadyEvent}：此时 Flyway 已完成（迁移脚本需在业务表就绪后执行）。
 * 分页 CAS 更新（{@code WHERE id=? AND password=?}），每批提交，避免长事务锁表；
 * 并发重置口令时 CAS 失败的行保持不变，下次启动再处理。</p>
 */
@Component
public class LegacyPasswordMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPasswordMigrationRunner.class);

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;

    public LegacyPasswordMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 先于向量回填等重量级启动任务执行
    @Order(10)
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        int migrated = 0;
        Long lastId = null;
        while (true) {
            List<Map<String, Object>> rows = lastId == null
                    ? jdbc.queryForList("SELECT id, password FROM sys_user ORDER BY id LIMIT " + BATCH_SIZE)
                    : jdbc.queryForList("SELECT id, password FROM sys_user WHERE id > ? ORDER BY id LIMIT " + BATCH_SIZE,
                            lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                String stored = row.get("password") == null ? null : String.valueOf(row.get("password"));
                if (PasswordHasher.isLegacyPassword(stored)) {
                    String replacement;
                    try {
                        replacement = PasswordHasher.wrapLegacyPassword(stored);
                    } catch (RuntimeException e) {
                        log.warn("口令迁移跳过非法 MD5: id={}", id);
                        continue;
                    }
                    // CAS：仅当库中仍是原值时才更新，避免覆盖并发重置
                    int updated = jdbc.update(
                            "UPDATE sys_user SET password = ? WHERE id = ? AND password = ?",
                            replacement, id, stored);
                    migrated += updated;
                }
            }
            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
        }
        if (migrated > 0) {
            log.info("遗留口令迁移完成：包装 {} 条 MD5 为 Argon2id", migrated);
        }
    }
}
