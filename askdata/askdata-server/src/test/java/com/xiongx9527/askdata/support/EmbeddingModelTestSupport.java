package com.xiongx9527.askdata.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 真实向量模型（TEI bge-m3）测试基建。
 *
 * <p>embedding 链路只有打到真模型才有意义：维度、归一化、批量返回条数都是被测行为。
 * 地址只从环境读取，不可达时 assume-skip：</p>
 *
 * <pre>
 * ASKDATA_IT_EMBEDDING_URL=http://127.0.0.1:9528 ASKDATA_IT_EMBEDDING_MODEL=bge-m3 mvn test
 * </pre>
 */
public final class EmbeddingModelTestSupport {

    private EmbeddingModelTestSupport() {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** OpenAI 兼容的 embedding 端点根地址（缺省本机 TEI 9528）。 */
    public static String url() {
        String base = env("ASKDATA_IT_EMBEDDING_URL", "http://127.0.0.1:9528");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 模型名（TEI 的 served model 名，缺省 bge-m3）。 */
    public static String model() {
        return env("ASKDATA_IT_EMBEDDING_MODEL", "bge-m3");
    }

    /** 真实模型是否可用（/v1/embeddings 返回 2xx）。 */
    public static boolean available() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url() + "/v1/embeddings").openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(("{\"model\":\"" + model() + "\",\"input\":[\"ping\"]}")
                        .getBytes(StandardCharsets.UTF_8));
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

    /** 不可达时跳过。 */
    public static void requireAvailable() {
        Assumptions.assumeTrue(available(),
                "向量模型不可达：" + url() + "（设置 ASKDATA_IT_EMBEDDING_URL 指向 TEI/Ollama 的 OpenAI 兼容端点）");
    }

    /**
     * 在真实元数据库里登记一个可用向量模型（model_type=1，enable/valid 均为真），
     * 供 {@code ChatModelRouter} 解析出真实端点。
     */
    public static long registerModel(JdbcTemplate jdbc) {
        long id = new IdGenerator().nextId();
        new AiModelRepository(jdbc, new IdGenerator()).insert(
                id, "", url(), 1, 0, "it-bge-m3", 1, model(), true, "[]", 1,
                System.currentTimeMillis(), true, 1, null);
        return id;
    }

    /** 供需要直接读 JSON 的调用方复用。 */
    public static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
