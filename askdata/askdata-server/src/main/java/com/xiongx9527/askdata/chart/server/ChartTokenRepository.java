package com.xiongx9527.askdata.chart.server;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 图表图片 token 映射的落库访问层。
 *
 * <p>多实例部署下，生成图片的实例与接收 {@code /api/chart/image/{token}} 请求的实例往往不同，
 * token → 文件 url 的映射必须共享；落库后重启与跨实例均可解析（文件本体在对象存储）。</p>
 */
@Repository
public class ChartTokenRepository {

    private static final RowMapper<Entry> MAPPER = (rs, rowNum) -> new Entry(
            rs.getString("token"), rs.getString("file_url"), rs.getLong("expire_at"));

    private final JdbcTemplate jdbc;

    public ChartTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入映射（token 为 UUID，不重复）。 */
    public void save(String token, String url, long expireAt) {
        jdbc.update("INSERT INTO chart_image_token (token, file_url, expire_at, create_time) "
                + "VALUES (?, ?, ?, ?)", token, url, expireAt, System.currentTimeMillis());
    }

    /** 按 token 查询。 */
    public Optional<Entry> find(String token) {
        return jdbc.query("SELECT token, file_url, expire_at FROM chart_image_token WHERE token = ?",
                MAPPER, token).stream().findFirst();
    }

    /** 删除单条映射。 */
    public boolean delete(String token) {
        return jdbc.update("DELETE FROM chart_image_token WHERE token = ?", token) > 0;
    }

    /** 已过期的映射（最多 limit 条，供清理任务分批删除）。 */
    public List<Entry> findExpired(long now, int limit) {
        return jdbc.query("SELECT token, file_url, expire_at FROM chart_image_token "
                + "WHERE expire_at < ? ORDER BY expire_at LIMIT ?", MAPPER, now, limit);
    }

    /** token 映射条目。 */
    public static class Entry {

        private final String token;

        private final String url;

        private final long expireAt;

        public Entry(String token, String url, long expireAt) {
            this.token = token;
            this.url = url;
            this.expireAt = expireAt;
        }

        public String token() {
            return token;
        }

        public String url() {
            return url;
        }

        public long expireAt() {
            return expireAt;
        }
    }
}
