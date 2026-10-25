package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 生成提示词模板装载器（验收 A9）。
 *
 * <p>直接移植 SQLBot {@code backend/templates/}：基础模板 {@code template.yaml}（796 行）
 * 与方言样例 {@code sql_examples/&lt;template_name&gt;.yaml}（13 个方言，1432 行）作为 classpath
 * 资源打包，由本组件按 {@link DatasourceType#getTemplateName()} 选择加载并做占位符替换，
 * 语义对齐 SQLBot {@code apps/template/template.py} 的 {@code _load_template_file} 缓存与
 * {@code apps/template/generate_sql/generator.py} 的两级取模板逻辑。</p>
 *
 * <ul>
 *   <li>基础模板：{@code template.sql.*}（system / generate_rules / query_limit / no_query_limit /
 *       multi_table_condition / process_check / generate_basic_info / user ...）；</li>
 *   <li>方言模板：{@code sql_examples/&lt;TemplateName&gt;.yaml}（quot_rule / limit_rule /
 *       other_rule / basic_example / example_engine / example_answer_N[_with_limit]）；</li>
 *   <li>缺失方言模板时回退 PostgreSQL（对齐 SQLBot {@code DB.get_db(default_if_none=True)}）。</li>
 * </ul>
 */
@Component
public class SqlPromptTemplates {

    private static final Logger log = LoggerFactory.getLogger(SqlPromptTemplates.class);

    private static final String BASE_TEMPLATE = "prompt/template.yaml";

    private static final String SQL_EXAMPLE_DIR = "prompt/sql_examples/";

    private static final String FALLBACK_TEMPLATE_NAME = "PostgreSQL";

    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    /** 启动即装载基础模板，缺文件直接失败（模板是问数链路的核心资产，不允许静默降级）。 */
    @PostConstruct
    void init() {
        base();
        log.info("SQL prompt templates loaded: base={} dialects cached on demand",
                BASE_TEMPLATE);
    }

    /** 基础模板的 {@code template.sql} 段（SQLBot {@code get_sql_template()}）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> baseSql() {
        Map<String, Object> root = (Map<String, Object>) base().get("template");
        Object sql = root.get("sql");
        if (!(sql instanceof Map)) {
            throw new IllegalStateException("prompt/template.yaml missing template.sql section");
        }
        return (Map<String, Object>) sql;
    }

    /** 基础模板整棵树的 {@code template} 段（图表提示词用）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> baseTemplate() {
        return (Map<String, Object>) base().get("template");
    }

    /**
     * 方言样例模板（SQLBot {@code get_sql_example_template(db_type)}，即方言 YAML 的
     * {@code template} 内层：quot_rule / limit_rule / other_rule / basic_example /
     * example_engine / example_answer_N[_with_limit]）。
     *
     * @param type 数据源类型；null 或未知/缺文件时回退 PostgreSQL
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dialectTemplate(DatasourceType type) {
        String name = type == null ? FALLBACK_TEMPLATE_NAME : type.getTemplateName();
        Map<String, Object> root = load(SQL_EXAMPLE_DIR + name + ".yaml", FALLBACK_TEMPLATE_NAME);
        Object inner = root.get("template");
        if (inner instanceof Map) {
            return (Map<String, Object>) inner;
        }
        return root;
    }

    /**
     * 生成 SQL 的 system 提示词：基础模板 {@code system} 段 + {@code generate_rules} 段。
     *
     * <p>SQLBot 把 system 与 rules 分成两条消息（llm.py:296-297），此处合并为一条 system
     * 消息（既保留全部规则内容，又不改变既有消息装配结构）。</p>
     *
     * @param type          数据源方言（决定 quot_rule/limit_rule/basic_example）
     * @param rowLimit      chat.limit_rows：true 走 query_limit（强制最外层 1000 行），
     *                      false 走 no_query_limit
     * @param sqlbotName    助手名（chat.sqlbot_name）
     */
    public String sqlSystemPrompt(DatasourceType type, boolean rowLimit, String sqlbotName) {
        Map<String, Object> base = baseSql();
        Map<String, Object> dialect = dialectTemplate(type);

        String processCheck = text(dialect.get("process_check"));
        if (processCheck.isEmpty()) {
            processCheck = text(base.get("process_check"));
        }
        String queryLimit = text(base.get(rowLimit ? "query_limit" : "no_query_limit"));
        String multiTable = text(base.get("multi_table_condition"));
        String otherRule = text(dialect.get("other_rule")).replace("{multi_table_condition}", multiTable);
        String baseSqlRules = text(dialect.get("quot_rule")) + queryLimit
                + text(dialect.get("limit_rule")) + otherRule;

        String system = text(base.get("system"))
                .replace("{process_check}", processCheck)
                .replace("{sqlbot_name}", sqlbotName);
        String rules = text(base.get("generate_rules"))
                .replace("{base_sql_rules}", baseSqlRules)
                .replace("{basic_sql_examples}", text(dialect.get("basic_example")))
                .replace("{example_engine}", text(dialect.get("example_engine")))
                .replace("{example_answer_1}", exampleAnswer(base, dialect, 1, rowLimit))
                .replace("{example_answer_2}", exampleAnswer(base, dialect, 2, rowLimit))
                .replace("{example_answer_3}", exampleAnswer(base, dialect, 3, rowLimit))
                .replace("{sqlbot_name}", sqlbotName);
        return unescapeBraces(system + "\n\n" + rules);
    }

    /**
     * Python str.format 的 {{ }} 转义还原：模板里的双花括号 JSON 示例在 SQLBot 侧渲染为单花括号，
     * 此处替换占位符后做同样的还原。
     */
    private static String unescapeBraces(String text) {
        return text.replace("{{", "{").replace("}}", "}");
    }

    /** 关键词提取提示词（A12 字段抽取/关键词步骤同样可复用）。 */
    public String extractKeywordsPrompt(String sqlbotName) {
        return text(baseSql().get("extract_keywords"))
                .replace("{sqlbot_name}", sqlbotName);
    }

    /** 重新生成前缀（SQLBot regenerate_hint）。 */
    public String regenerateHint() {
        return text(baseSql().get("regenerate_hint"));
    }

    /** 图表生成的 system + rules 提示词（基础模板 chart 段；默认简体中文）。 */
    public String chartSystemPrompt(String sqlbotName) {
        return chartSystemPrompt(sqlbotName, "简体中文");
    }

    /** 图表生成的 system + rules 提示词（基础模板 chart 段）。 */
    @SuppressWarnings("unchecked")
    public String chartSystemPrompt(String sqlbotName, String lang) {
        Map<String, Object> chart = (Map<String, Object>) baseTemplate().get("chart");
        if (chart == null) {
            return "";
        }
        String body = text(chart.get("system")).replace("{sqlbot_name}", sqlbotName) + "\n\n"
                + text(chart.get("generate_rules"));
        return unescapeBraces(body.replace("{lang}", lang));
    }

    private static String exampleAnswer(Map<String, Object> base, Map<String, Object> dialect,
                                        int index, boolean rowLimit) {
        String key = "example_answer_" + index + (rowLimit ? "_with_limit" : "");
        String value = text(dialect.get(key));
        if (value.isEmpty()) {
            value = text(dialect.get("example_answer_" + index));
        }
        return value;
    }

    private Map<String, Object> base() {
        return load(BASE_TEMPLATE, null);
    }

    /**
     * 装载并缓存 YAML 模板（对齐 SQLBot {@code @cache _load_template_file}）。
     *
     * @param fallbackName 缺失时回退的方言名（仅 sql_examples 用；null 表示必须存在）
     */
    private Map<String, Object> load(String path, String fallbackName) {
        Map<String, Object> cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            cached = read(path);
        } catch (RuntimeException e) {
            if (fallbackName == null) {
                throw e;
            }
            log.info("提示词模板 {} 缺失，回退 {}", path, fallbackName);
            cached = read(SQL_EXAMPLE_DIR + fallbackName + ".yaml");
        }
        cache.put(path, cached);
        return cached;
    }

    private static Map<String, Object> read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("prompt template not found on classpath: " + path);
        }
        try (InputStream in = resource.getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException("prompt template is not a yaml mapping: " + path);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            throw new IllegalStateException("read prompt template failed: " + path, e);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
