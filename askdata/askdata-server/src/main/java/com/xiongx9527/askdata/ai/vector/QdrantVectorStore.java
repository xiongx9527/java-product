package com.xiongx9527.askdata.ai.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 向量存储（REST）。
 *
 * <p>用 REST 而非官方 gRPC 客户端：项目运行在 Java 8 / Spring Boot 2.7 上，官方客户端需要更新的
 * 运行时；REST 接口足够覆盖本项目的五种操作（建集合 / 删集合 / 写入 / 删除 / 检索），且不引入新依赖。
 * HTTP 层用 Spring {@link RestTemplate}，超时、错误流与 JSON 编解码都交给框架。</p>
 *
 * <p><b>不回落本地</b>：向量只写在线库，连接失败直接抛错由调用方记录，不会退回 DB JSON 列——
 * 否则会出现「配置了在线库却实际存在本地」的隐性路径，切换维度或迁移时会静默丢召回。</p>
 */
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private static final int CONNECT_TIMEOUT_MS = 3000;

    private static final int READ_TIMEOUT_MS = 15000;

    private final String baseUrl;

    private final String apiKey;

    private final String collectionPrefix;

    private final ObjectMapper objectMapper;

    /** HTTP 客户端：交给 Spring 处理连接复用、超时、错误流与 JSON 编解码，不再手写 InputStream/OutputStream。 */
    private final RestTemplate restTemplate;

    public QdrantVectorStore(String baseUrl, String apiKey, String collectionPrefix, ObjectMapper objectMapper) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.collectionPrefix = collectionPrefix == null ? "" : collectionPrefix;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String collectionPrefix() {
        return collectionPrefix;
    }

    @Override
    public boolean health() {
        try {
            Response response = request("GET", "/collections", null);
            return response.code >= 200 && response.code < 300;
        } catch (Exception e) {
            log.info("Qdrant 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 确保集合存在且维度一致：不存在则创建；维度不同则<b>抛错</b>，不删除集合。
     *
     * <p>维度变化意味着集合内全部向量作废，这必须是运维可见的显式动作（重建接口），
     * 不能由一次写入隐式触发——否则改错一次 embedding 模型维度就会静默清空整个集合。</p>
     */
    @Override
    public void ensureCollection(String collection, int dimension) {
        try {
            Response existing = request("GET", "/collections/" + collection, null);
            if (existing.code == 200) {
                int current = existingSize(existing.body);
                if (current == dimension) {
                    return;
                }
                log.error("Qdrant 集合 {} 维度不一致：现有 {}，本次写入 {}；"
                        + "已拒绝写入且未删除集合，请执行向量索引重建", collection, current, dimension);
                throw new DimensionMismatchException("集合 " + collection + " 维度不一致（现有 " + current
                        + "，需 " + dimension + "）：请执行向量索引重建");
            } else if (existing.code != 404) {
                throw new IOException("Qdrant 查询集合失败 HTTP " + existing.code);
            }
            createCollection(collection, dimension);
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 集合准备失败 " + collection + ": " + e.getMessage(), e);
        }
    }

    /** 删除集合（不存在视为成功）；只由显式重建调用。 */
    @Override
    public void dropCollection(String collection) {
        try {
            Response deleted = request("DELETE", "/collections/" + collection, null);
            if (deleted.code >= 300 && deleted.code != 404) {
                throw new IOException("Qdrant 删除集合失败 HTTP " + deleted.code + ": " + deleted.body);
            }
            if (deleted.code == 200) {
                log.warn("Qdrant 集合 {} 已删除（显式重建，旧向量作废）", collection);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 删除集合失败 " + collection + ": " + e.getMessage(), e);
        }
    }

    /**
     * 读取集合现有维度：集合不存在（404）或后端不可用时返回 {@code null}。
     *
     * <p>只读探测，不建集合、不删数据——用于「模型维度 vs 向量存储」联合校验，
     * 让运维在校验阶段就发现「模型维度与集合维度不一致，写入必然被拒」。</p>
     */
    @Override
    public Integer collectionDimension(String collection) {
        try {
            Response existing = request("GET", "/collections/" + collection, null);
            if (existing.code != 200) {
                return null;
            }
            int size = existingSize(existing.body);
            return size > 0 ? size : null;
        } catch (IOException e) {
            log.info("Qdrant 读取集合 {} 维度失败: {}", collection, e.getMessage());
            return null;
        }
    }

    private void createCollection(String collection, int dimension) throws IOException {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", dimension);
        vectors.put("distance", "Cosine");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectors);
        Response created = request("PUT", "/collections/" + collection, body);
        if (created.code >= 300) {
            throw new IOException("Qdrant 创建集合失败 HTTP " + created.code + ": " + created.body);
        }
    }

    @Override
    public void upsert(String collection, long id, double[] vector) {
        upsert(collection, id, vector, null);
    }

    /** 写入并附带 payload：payload 是删除时的唯一依据，缺省不写该字段。 */
    @Override
    public void upsert(String collection, long id, double[] vector, Map<String, Object> payload) {
        if (vector == null) {
            return;
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("vector", toList(vector));
        if (payload != null && !payload.isEmpty()) {
            point.put("payload", payload);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);
        try {
            Response response = request("PUT", "/collections/" + collection + "/points?wait=true", body);
            if (response.code >= 300) {
                throw new IOException("Qdrant 写入失败 HTTP " + response.code + ": " + response.body);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 写入失败 " + collection + "/" + id + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String collection, long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        ids.add(id);
        body.put("points", ids);
        try {
            request("POST", "/collections/" + collection + "/points/delete?wait=true", body);
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 删除失败 " + collection + "/" + id + ": " + e.getMessage(), e);
        }
    }

    /** 批量删除：一次请求提交全部 id（Qdrant {@code points/delete} 原生支持 id 数组）。 */
    @Override
    public void deleteBatch(String collection, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", new ArrayList<>(ids));
        try {
            Response response = request("POST", "/collections/" + collection + "/points/delete?wait=true", body);
            if (response.code >= 300 && response.code != 404) {
                log.warn("Qdrant 批量删除失败 {}（{} 个 id）HTTP {}: {}",
                        collection, ids.size(), response.code, response.body);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 批量删除失败 " + collection + "（" + ids.size()
                    + " 个 id）: " + e.getMessage(), e);
        }
    }

    /** 按 payload 精确匹配删除（Qdrant {@code points/delete} 的 filter 形式）。 */
    @Override
    public void deleteByPayload(String collection, String key, Object value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("value", value);
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("key", key);
        condition.put("match", match);
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(condition);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", must);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", filter);
        try {
            Response response = request("POST", "/collections/" + collection + "/points/delete?wait=true", body);
            if (response.code >= 300 && response.code != 404) {
                log.warn("Qdrant 按 payload 删除失败 {}（{}={}）HTTP {}: {}",
                        collection, key, value, response.code, response.body);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 按 payload 删除失败 " + collection + "（" + key + "="
                    + value + "）: " + e.getMessage(), e);
        }
    }

    @Override
    public List<IdScore> query(String collection, double[] vector, int topK, List<Long> candidateIds) {
        List<IdScore> hits = new ArrayList<>();
        if (vector == null) {
            return hits;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toList(vector));
        body.put("limit", topK);
        body.put("with_payload", false);
        if (candidateIds != null && !candidateIds.isEmpty()) {
            Map<String, Object> hasId = new LinkedHashMap<>();
            hasId.put("has_id", candidateIds);
            List<Map<String, Object>> must = new ArrayList<>();
            must.add(hasId);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("must", must);
            body.put("filter", filter);
        }
        try {
            Response response = request("POST", "/collections/" + collection + "/points/search", body);
            if (response.code >= 300) {
                throw new IOException("Qdrant 检索失败 HTTP " + response.code);
            }
            JsonNode result = objectMapper.readTree(response.body).get("result");
            if (result == null || !result.isArray()) {
                return hits;
            }
            for (JsonNode item : result) {
                if (!item.has("id")) {
                    continue;
                }
                hits.add(new IdScore(item.get("id").asLong(),
                        item.has("score") ? item.get("score").asDouble() : 0D));
            }
            return hits;
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 检索失败 " + collection + ": " + e.getMessage(), e);
        }
    }

    private int existingSize(String body) {
        try {
            JsonNode size = objectMapper.readTree(body).path("result").path("config").path("params")
                    .path("vectors").path("size");
            return size.isMissingNode() ? -1 : size.asInt();
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<Double> toList(double[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (double v : vector) {
            values.add(v);
        }
        return values;
    }

    private Response request(String method, String path, Map<String, Object> body) throws IOException {
        if (baseUrl.isEmpty()) {
            throw new IOException("未配置 askdata.vector-store.qdrant.url");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (!apiKey.isEmpty()) {
            headers.set("api-key", apiKey);
        }
        HttpEntity<?> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + path, HttpMethod.resolve(method), entity, String.class);
            return new Response(response.getStatusCodeValue(),
                    response.getBody() == null ? "" : response.getBody());
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx 不抛给调用方：调用方按状态码区分「集合不存在(404)」与真实错误
            return new Response(e.getRawStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static final class Response {

        final int code;

        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
