package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 兼容回退实现：沿用现有「DB embedding JSON 列 + 应用内余弦」。
 *
 * <p>在 {@code askdata.vector-store.type=none} 或在线库不可达时启用，保留既有行为；
 * 任何失败仅记日志、不抛异常（问数主链路必须可用）。</p>
 *
 * <p>集合名约定 {@code {prefix}-{object-type}}，由此反推表名（object-type 即表名）：
 * terminology / data_training / core_table / core_datasource。</p>
 */
public class DbVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(DbVectorStore.class);

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    private final String collectionPrefix;

    public DbVectorStore(JdbcTemplate jdbc, ObjectMapper objectMapper, String collectionPrefix) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.collectionPrefix = collectionPrefix == null ? "" : collectionPrefix;
    }

    @Override
    public String collectionPrefix() {
        return collectionPrefix;
    }

    @Override
    public boolean isOnline() {
        return false;
    }

    @Override
    public boolean health() {
        return true;
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        // DB 表已存在，无需创建集合
    }

    @Override
    public void upsert(String collection, long id, double[] vector) {
        if (vector == null) {
            return;
        }
        try {
            String table = tableOf(collection);
            jdbc.update("UPDATE " + table + " SET " + columnOf(table) + " = ? WHERE id = ?",
                    toJson(vector), id);
        } catch (Exception e) {
            log.info("DB 向量写入失败 {}/{}: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public void delete(String collection, long id) {
        try {
            String table = tableOf(collection);
            jdbc.update("UPDATE " + table + " SET " + columnOf(table) + " = NULL WHERE id = ?", id);
        } catch (Exception e) {
            log.info("DB 向量删除失败 {}/{}: {}", collection, id, e.getMessage());
        }
    }

    @Override
    public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
        List<IdScore> hits = new ArrayList<>();
        if (vector == null) {
            return hits;
        }
        try {
            String table = tableOf(collection);
            String column = columnOf(table);
            StringBuilder sql = new StringBuilder("SELECT id, ").append(column).append(" FROM ").append(table);
            List<Object> args = new ArrayList<>();
            if (candidateIds != null && !candidateIds.isEmpty()) {
                sql.append(" WHERE id IN (");
                for (int i = 0; i < candidateIds.size(); i++) {
                    sql.append(i > 0 ? ",?" : "?");
                }
                sql.append(")");
                args.addAll(candidateIds);
            } else {
                sql.append(" WHERE ").append(column).append(" IS NOT NULL");
            }
            for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
                double[] stored = parseVector(str(row, column));
                if (stored == null || stored.length != vector.length) {
                    continue;
                }
                hits.add(new IdScore(lng(row, "id", 0L), cosine(vector, stored)));
            }
        } catch (Exception e) {
            log.info("DB 向量查询失败（降级为空）: {}", e.getMessage());
            return new ArrayList<>();
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (topK > 0 && hits.size() > topK) {
            hits = new ArrayList<>(hits.subList(0, topK));
        }
        return hits;
    }

    private String tableOf(String collection) {
        if (!collectionPrefix.isEmpty() && collection.startsWith(collectionPrefix + "-")) {
            return collection.substring(collectionPrefix.length() + 1);
        }
        return collection;
    }

    private static String columnOf(String table) {
        return "core_table".equals(table) ? "table_embedding" : "embedding";
    }

    private String toJson(double[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    private double[] parseVector(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray() || node.isEmpty()) {
                return null;
            }
            double[] vector = new double[node.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = node.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    private static double cosine(double[] a, double[] b) {
        return com.xiongx9527.askdata.chat.service.ChatModelClient.cosine(a, b);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static long lng(Map<String, Object> row, String key, long dflt) {
        Object v = row.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return dflt;
        }
    }
}
