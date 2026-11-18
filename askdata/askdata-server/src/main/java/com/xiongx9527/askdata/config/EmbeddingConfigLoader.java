package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 sys_arg 的 {@code embedding.*} 键加载一次 {@link EmbeddingConfig} 快照。
 *
 * <p>对齐上游 {@code load_embedding_config}：一次查询读全部覆盖值；历史重复键取 id 最大者；
 * 非法保存值被拒绝并保留缺省（不静默替换成默认值写入）；查询失败时降级为缺省配置，
 * 不影响问数主链路。</p>
 */
@Component
public class EmbeddingConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigLoader.class);

    private static final String PREFIX = "embedding.";

    private final SysArgRepository sysArgRepository;

    /**
     * A27 启动期缺省阈值：兼容既有 {@code askdata.vector.recall-threshold} 部署配置。
     * sys_arg 的 {@code embedding.*} 覆盖优先；单测手工构造时字段初始化值即缺省 0.4。
     */
    @Value("${askdata.vector.recall-threshold:0.4}")
    private double startupRecallThreshold = 0.4D;

    public EmbeddingConfigLoader(SysArgRepository sysArgRepository) {
        this.sysArgRepository = sysArgRepository;
    }

    public EmbeddingConfig load() {
        EmbeddingConfig base = EmbeddingConfig.defaults()
                .with("terminology_similarity", startupRecallThreshold)
                .with("data_training_similarity", startupRecallThreshold);
        if (sysArgRepository == null) {
            return base;
        }
        try {
            List<Map<String, Object>> rows = sysArgRepository.listByPrefix("embedding");
            if (rows == null || rows.isEmpty()) {
                return base;
            }
            // 重复 pkey 取 id 最大者（对齐上游 order_by(id) 后覆盖的确定性规则）
            Map<String, Map<String, Object>> chosen = new LinkedHashMap<String, Map<String, Object>>();
            for (Map<String, Object> row : rows) {
                String pkey = Rows.str(row, "pkey");
                if (pkey == null || !pkey.startsWith(PREFIX)) {
                    continue;
                }
                String field = pkey.substring(PREFIX.length());
                Map<String, Object> prev = chosen.get(field);
                if (prev == null || Rows.lng(row, "id", 0L) > Rows.lng(prev, "id", 0L)) {
                    chosen.put(field, row);
                }
            }
            EmbeddingConfig config = base;
            for (Map.Entry<String, Map<String, Object>> entry : chosen.entrySet()) {
                String field = entry.getKey();
                Object raw = entry.getValue().get("pval");
                try {
                    config = config.with(field, EmbeddingConfig.parse(field, raw));
                } catch (IllegalArgumentException e) {
                    log.warn("忽略非法的 embedding 参数 {}={}（保留缺省）: {}", field, raw, e.getMessage());
                }
            }
            return config;
        } catch (Exception e) {
            log.info("读取 embedding 参数失败（使用缺省策略）: {}", e.getMessage());
            return base;
        }
    }
}
