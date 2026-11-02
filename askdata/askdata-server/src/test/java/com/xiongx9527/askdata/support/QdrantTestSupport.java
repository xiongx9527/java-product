package com.xiongx9527.askdata.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.ai.vector.QdrantVectorStore;
import org.junit.jupiter.api.Assumptions;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 真实 Qdrant 集成测试基建：地址/密钥只从环境读取，不可达时整类 assume-skip。
 *
 * <p>不使用内存/桩实现——向量存储的行为（集合维度、has_id 过滤、删除语义）
 * 只有打真库才能验证。本地运行方式：</p>
 *
 * <pre>
 * ASKDATA_QDRANT_URL=http://127.0.0.1:6333 ASKDATA_QDRANT_API_KEY=... mvn test -Dtest=QdrantVectorStoreTest
 * </pre>
 */
public final class QdrantTestSupport {

    private static final String DEFAULT_URL = "http://127.0.0.1:6333";

    private QdrantTestSupport() {
    }

    /** 依次读系统属性、环境变量，最后回落默认值。 */
    private static String read(String property, String envName, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(envName);
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Qdrant REST 地址（缺省 http://127.0.0.1:6333）。 */
    public static String url() {
        return read("askdata.it.qdrant.url", "ASKDATA_QDRANT_URL", DEFAULT_URL);
    }

    /** Qdrant API Key（缺省空：本地无鉴权实例）。 */
    public static String apiKey() {
        return read("askdata.it.qdrant.api-key", "ASKDATA_QDRANT_API_KEY", "");
    }

    /** 每次运行独立的集合前缀，避免与既有集合/并行任务相互污染。 */
    public static String uniquePrefix() {
        return "askdata-it-" + Long.toHexString(System.nanoTime());
    }

    /** 真实后端是否可用（HTTP 2xx 且鉴权通过）。 */
    public static boolean available() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url() + "/collections").openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            String key = apiKey();
            if (!key.isEmpty()) {
                connection.setRequestProperty("api-key", key);
            }
            int code = connection.getResponseCode();
            try (InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (stream != null) {
                    stream.read();
                }
            }
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 后端不可达时跳过（并在报告里给出原因与配置方式）。 */
    public static void requireAvailable() {
        Assumptions.assumeTrue(available(),
                "Qdrant 不可达：" + url() + "（设置 ASKDATA_QDRANT_URL / ASKDATA_QDRANT_API_KEY 后重跑）");
    }

    /** 真实 Qdrant 客户端（集合前缀由调用方决定）。 */
    public static QdrantVectorStore store(String prefix) {
        return new QdrantVectorStore(url(), apiKey(), prefix, new ObjectMapper());
    }

    /** 删除本次运行创建的集合（不存在视为成功）。 */
    public static void dropAll(QdrantVectorStore store, String... objectTypes) {
        for (String objectType : objectTypes) {
            try {
                store.dropCollection(store.collectionFor(objectType));
            } catch (Exception ignored) {
                // 清理失败不影响断言结果
            }
        }
    }
}
