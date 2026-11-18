package com.xiongx9527.askdata.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding 检索策略快照，对齐上游 {@code common/core/embedding_config.py} 的 10 项管理员可调参数。
 *
 * <p>缺省值与上游 {@code settings} 一致；参数经 sys_arg 的 {@code embedding.*} 键覆盖。
 * 不可变值对象：一次加载得到一个快照，向下传递使用，避免单次问数过程中参数漂移。</p>
 */
public final class EmbeddingConfig {

    /** 参数字段名（不含 {@code embedding.} 前缀），顺序与上游 EDITABLE_FIELDS 一致。 */
    public static final String[] FIELDS = {
            "enabled", "terminology_similarity", "terminology_top_count",
            "data_training_similarity", "data_training_top_count", "table_enabled",
            "table_count", "table_alpha", "keyword_enabled", "datasource_count"
    };

    private static final EmbeddingConfig DEFAULT = new EmbeddingConfig(
            true, 0.4D, 5, 0.4D, 5, true, 10, 0.4D, true, 10);

    private final boolean enabled;
    private final double terminologySimilarity;
    private final int terminologyTopCount;
    private final double dataTrainingSimilarity;
    private final int dataTrainingTopCount;
    private final boolean tableEnabled;
    private final int tableCount;
    private final double tableAlpha;
    private final boolean keywordEnabled;
    private final int datasourceCount;

    private EmbeddingConfig(boolean enabled, double terminologySimilarity, int terminologyTopCount,
                            double dataTrainingSimilarity, int dataTrainingTopCount, boolean tableEnabled,
                            int tableCount, double tableAlpha, boolean keywordEnabled, int datasourceCount) {
        this.enabled = enabled;
        this.terminologySimilarity = terminologySimilarity;
        this.terminologyTopCount = terminologyTopCount;
        this.dataTrainingSimilarity = dataTrainingSimilarity;
        this.dataTrainingTopCount = dataTrainingTopCount;
        this.tableEnabled = tableEnabled;
        this.tableCount = tableCount;
        this.tableAlpha = tableAlpha;
        this.keywordEnabled = keywordEnabled;
        this.datasourceCount = datasourceCount;
    }

    public static EmbeddingConfig defaults() {
        return DEFAULT;
    }

    public boolean isEnabled() { return enabled; }
    public double getTerminologySimilarity() { return terminologySimilarity; }
    public int getTerminologyTopCount() { return terminologyTopCount; }
    public double getDataTrainingSimilarity() { return dataTrainingSimilarity; }
    public int getDataTrainingTopCount() { return dataTrainingTopCount; }
    public boolean isTableEnabled() { return tableEnabled; }
    public int getTableCount() { return tableCount; }
    public double getTableAlpha() { return tableAlpha; }
    public boolean isKeywordEnabled() { return keywordEnabled; }
    public int getDatasourceCount() { return datasourceCount; }

    /** 返回一个字段被覆盖后的新快照；field 为不含前缀的字段名。非法值由调用方校验后再传入。 */
    public EmbeddingConfig with(String field, Object value) {
        if (field == null || value == null) {
            return this;
        }
        switch (field) {
            case "enabled": return new EmbeddingConfig((Boolean) value, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, tableAlpha,
                    keywordEnabled, datasourceCount);
            case "terminology_similarity": return new EmbeddingConfig(enabled, (Double) value, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, tableAlpha,
                    keywordEnabled, datasourceCount);
            case "terminology_top_count": return new EmbeddingConfig(enabled, terminologySimilarity, (Integer) value,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, tableAlpha,
                    keywordEnabled, datasourceCount);
            case "data_training_similarity": return new EmbeddingConfig(enabled, terminologySimilarity,
                    terminologyTopCount, (Double) value, dataTrainingTopCount, tableEnabled, tableCount,
                    tableAlpha, keywordEnabled, datasourceCount);
            case "data_training_top_count": return new EmbeddingConfig(enabled, terminologySimilarity,
                    terminologyTopCount, dataTrainingSimilarity, (Integer) value, tableEnabled, tableCount,
                    tableAlpha, keywordEnabled, datasourceCount);
            case "table_enabled": return new EmbeddingConfig(enabled, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, (Boolean) value, tableCount, tableAlpha,
                    keywordEnabled, datasourceCount);
            case "table_count": return new EmbeddingConfig(enabled, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, (Integer) value, tableAlpha,
                    keywordEnabled, datasourceCount);
            case "table_alpha": return new EmbeddingConfig(enabled, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, (Double) value,
                    keywordEnabled, datasourceCount);
            case "keyword_enabled": return new EmbeddingConfig(enabled, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, tableAlpha,
                    (Boolean) value, datasourceCount);
            case "datasource_count": return new EmbeddingConfig(enabled, terminologySimilarity, terminologyTopCount,
                    dataTrainingSimilarity, dataTrainingTopCount, tableEnabled, tableCount, tableAlpha,
                    keywordEnabled, (Integer) value);
            default: return this;
        }
    }

    /**
     * 严格解析保存值，对齐上游 {@code parse_parameter}：bool 只认 true/false 字符串或布尔；
     * int 必须为非负整数（datasource_count 允许 0 表示不预筛，其它必须 >0）；float 限 [0,1]。
     *
     * @throws IllegalArgumentException 值非法
     */
    public static Object parse(String field, Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null value for " + field);
        }
        boolean bool = "enabled".equals(field) || "table_enabled".equals(field) || "keyword_enabled".equals(field);
        boolean integer = "terminology_top_count".equals(field) || "data_training_top_count".equals(field)
                || "table_count".equals(field) || "datasource_count".equals(field);
        if (bool) {
            if (raw instanceof Boolean) {
                return raw;
            }
            String s = String.valueOf(raw).trim().toLowerCase();
            if ("true".equals(s)) {
                return Boolean.TRUE;
            }
            if ("false".equals(s)) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("invalid boolean for " + field + ": " + raw);
        }
        String s = String.valueOf(raw).trim();
        if (integer) {
            if (!s.matches("[0-9]+")) {
                throw new IllegalArgumentException("invalid integer for " + field + ": " + raw);
            }
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid integer for " + field + ": " + raw);
            }
            boolean allowZero = "datasource_count".equals(field);
            if (n < 0 || (!allowZero && n == 0)) {
                throw new IllegalArgumentException("invalid integer for " + field + ": " + raw);
            }
            return n;
        }
        double d;
        try {
            d = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid float for " + field + ": " + raw);
        }
        if (!Double.isFinite(d) || d < 0D || d > 1D) {
            throw new IllegalArgumentException("invalid float for " + field + ": " + raw);
        }
        return d;
    }

    /** 参数页分组的 ptype，供前端渲染控件。 */
    public static String ptype(String field) {
        if ("enabled".equals(field) || "table_enabled".equals(field) || "keyword_enabled".equals(field)) {
            return "bool";
        }
        if ("terminology_top_count".equals(field) || "data_training_top_count".equals(field)
                || "table_count".equals(field) || "datasource_count".equals(field)) {
            return "int";
        }
        return "float";
    }

    /** 默认值的字符串形态，供参数页在未落库时展示。 */
    public static String defaultValue(String field) {
        if ("enabled".equals(field)) return "true";
        if ("terminology_similarity".equals(field)) return "0.4";
        if ("terminology_top_count".equals(field)) return "5";
        if ("data_training_similarity".equals(field)) return "0.4";
        if ("data_training_top_count".equals(field)) return "5";
        if ("table_enabled".equals(field)) return "true";
        if ("table_count".equals(field)) return "10";
        if ("table_alpha".equals(field)) return "0.4";
        if ("keyword_enabled".equals(field)) return "true";
        if ("datasource_count".equals(field)) return "10";
        return "";
    }

    /** 供参数页使用的键值视图（不修改状态）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", enabled);
        map.put("terminology_similarity", terminologySimilarity);
        map.put("terminology_top_count", terminologyTopCount);
        map.put("data_training_similarity", dataTrainingSimilarity);
        map.put("data_training_top_count", dataTrainingTopCount);
        map.put("table_enabled", tableEnabled);
        map.put("table_count", tableCount);
        map.put("table_alpha", tableAlpha);
        map.put("keyword_enabled", keywordEnabled);
        map.put("datasource_count", datasourceCount);
        return map;
    }
}
