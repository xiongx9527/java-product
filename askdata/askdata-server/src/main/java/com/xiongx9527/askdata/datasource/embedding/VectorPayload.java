package com.xiongx9527.askdata.datasource.embedding;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向量 payload 构造。
 *
 * <p>写入向量时带上业务维度元数据（{@code ds_id} / {@code oid}），删除时即可按数据源
 * 一次清空，不必先查出全部表 id——即使进程在「删库」和「删向量」之间崩溃也不会留孤儿点。</p>
 */
public final class VectorPayload {

    private VectorPayload() {
    }

    /**
     * 数据源维度 payload：{@code ds_id} 必带，{@code oid} 查不到时省略（不阻断写入）。
     *
     * @param jdbc 元数据库
     * @param dsId 数据源 id
     */
    public static Map<String, Object> ofDatasource(JdbcTemplate jdbc, long dsId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ds_id", dsId);
        try {
            Long oid = jdbc.queryForObject("SELECT oid FROM core_datasource WHERE id = ?", Long.class, dsId);
            if (oid != null) {
                payload.put("oid", oid);
            }
        } catch (Exception e) {
            // oid 只是辅助维度：查不到仍按 ds_id 写入，按数据源删除不受影响
        }
        return payload;
    }
}
