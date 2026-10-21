package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 问数知识增强链路（A2，对齐 SQLBot {@code apps/chat/task/llm.py} 的
 * {@code extract_keywords / filter_terminology_template / filter_training_template /
 * filter_custom_prompts} 四步）：
 *
 * <ol>
 *   <li><b>关键词提取（extract_keywords）</b>：问题命中术语库的业务词即为提取出的关键词
 *       （askdata 无 LLM 提取步时的等价口径，词法/向量召回本身即「实体识别」）；</li>
 *   <li><b>术语过滤（filter_terms）</b>：命中术语组（主词 + 同义词 + 释义）组装为术语模板，
 *       释义与同义词扩展进入 SQL 生成提示词；</li>
 *   <li><b>SQL 示例过滤（filter_sql_example）</b>：{@code data_training} 训练样例召回；</li>
 *   <li><b>自定义提示词过滤（filter_custom_prompt）</b>：{@code custom_prompt} 按
 *       {@code GENERATE_SQL} 类型与数据源作用域召回。</li>
 * </ol>
 *
 * <p><b>降级（验收关键）</b>：未配置 embedding 模型时术语召回退化为词法匹配
 * （{@link TerminologyRecallService}），自定义提示词/训练样例查询失败一律返回空，
 * 本类任何一步都不向问数主链路抛异常。</p>
 */
@Service
public class ChatKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(ChatKnowledgeService.class);

    /** 同义词扩展上限（对齐 SQLBot {@code expand_with_terminology} 的 max_keywords=10）。 */
    public static final int MAX_EXPANDED_KEYWORDS = 10;

    private final TerminologyRecallService terminologyRecallService;

    private final SchemaContextBuilder schemaContextBuilder;

    private final JdbcTemplate jdbc;

    public ChatKnowledgeService(TerminologyRecallService terminologyRecallService,
                                SchemaContextBuilder schemaContextBuilder, JdbcTemplate jdbc) {
        this.terminologyRecallService = terminologyRecallService;
        this.schemaContextBuilder = schemaContextBuilder;
        this.jdbc = jdbc;
    }

    /**
     * 知识上下文：一次提问的关键词、术语模板、SQL 示例与自定义提示词。
     */
    public static final class KnowledgeContext {

        /** 提取到的关键词（命中术语库的业务词，逗号分隔；未命中为空串）。 */
        private final String extractedKeywords;

        /** 同义词扩展后的关键词（提取词 + 同义词，逗号分隔，上限 {@value #MAX_EXPANDED_KEYWORDS}）。 */
        private final String expandedKeywords;

        /** 术语模板文本（XML 形态；无命中为空串），进入 SQL 生成提示词。 */
        private final String terminologyText;

        /** 命中的术语组（{@code [{words:[...], description:"..."}]}），供日志记录。 */
        private final List<Map<String, Object>> termGroups;

        /** 召回的 SQL 示例文本。 */
        private final List<String> sqlExamples;

        /** 召回的自定义提示词文本。 */
        private final List<String> customPrompts;

        KnowledgeContext(String extractedKeywords, String expandedKeywords, String terminologyText,
                         List<Map<String, Object>> termGroups, List<String> sqlExamples,
                         List<String> customPrompts) {
            this.extractedKeywords = extractedKeywords;
            this.expandedKeywords = expandedKeywords;
            this.terminologyText = terminologyText;
            this.termGroups = termGroups;
            this.sqlExamples = sqlExamples;
            this.customPrompts = customPrompts;
        }

        public String getExtractedKeywords() {
            return extractedKeywords;
        }

        public String getExpandedKeywords() {
            return expandedKeywords;
        }

        public String getTerminologyText() {
            return terminologyText;
        }

        public List<Map<String, Object>> getTermGroups() {
            return termGroups;
        }

        public List<String> getSqlExamples() {
            return sqlExamples;
        }

        public List<String> getCustomPrompts() {
            return customPrompts;
        }

        /** 是否有术语命中（extracted_keywords 非空）。 */
        public boolean hasTerms() {
            return !extractedKeywords.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 链路
    // ------------------------------------------------------------------

    /**
     * 执行知识增强四步，任何一步失败都降级为空结果（不抛异常）。
     *
     * @param question 用户问题
     * @param oid      工作空间 id
     * @param dsId     数据源 id（可空；非空时按 specific_ds/datasource_ids 过滤术语与提示词）
     */
    public KnowledgeContext prepare(String question, long oid, Long dsId) {
        List<Map<String, Object>> groups = recallTerms(question, oid, dsId);
        List<String> matched = matchedWords(groups, question);
        String extracted = String.join(",", matched);
        String expanded = String.join(",", expandKeywords(matched, groups));
        String terminologyText = terminologyTemplate(groups);
        List<String> sqlExamples = trainingSamples(dsId, question);
        List<String> customPrompts = customPrompts(oid, dsId);
        return new KnowledgeContext(extracted, expanded, terminologyText, groups, sqlExamples, customPrompts);
    }

    /** 术语召回（向量 + 词法；向量不可用时仅词法，不报错）。 */
    private List<Map<String, Object>> recallTerms(String question, long oid, Long dsId) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return terminologyRecallService.recallGroups(question, oid, dsId, null);
        } catch (Exception e) {
            log.info("术语召回失败（降级为无术语）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 训练样例召回（data_training 缺失或失败时返回空，不报错）。 */
    private List<String> trainingSamples(Long dsId, String question) {
        if (dsId == null) {
            return new ArrayList<>();
        }
        try {
            List<String> samples = schemaContextBuilder.trainingSamples(dsId, question);
            return samples == null ? new ArrayList<>() : samples;
        } catch (Exception e) {
            log.info("SQL 示例召回失败（降级为空）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 自定义提示词召回（对齐 SQLBot xpack {@code find_custom_prompts} 的社区等价口径）：
     * {@code type = 'GENERATE_SQL'} + oid 匹配 + 数据源作用域
     * （{@code specific_ds=false} 或 {@code datasource_ids} 含该数据源）。
     */
    private List<String> customPrompts(long oid, Long dsId) {
        try {
            if (dsId == null) {
                return jdbc.queryForList(
                        "SELECT prompt FROM custom_prompt WHERE oid = ? AND type = 'GENERATE_SQL' "
                                + "AND (specific_ds = FALSE OR specific_ds IS NULL) ORDER BY create_time",
                        String.class, oid);
            }
            return jdbc.queryForList(
                    "SELECT prompt FROM custom_prompt WHERE oid = ? AND type = 'GENERATE_SQL' "
                            + "AND (specific_ds = FALSE OR specific_ds IS NULL OR ("
                            + "INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') "
                            + "|| ',', ',' || ? || ',') > 0)) ORDER BY create_time",
                    String.class, oid, String.valueOf(dsId));
        } catch (Exception e) {
            // custom_prompt 表缺失/列不一致时降级为空，不影响主链路
            log.info("自定义提示词召回失败（降级为空）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // 关键词提取与同义词扩展（对齐 SQLBot extract_keywords + expand_with_terminology）
    // ------------------------------------------------------------------

    /**
     * 提取关键词：命中术语组中<b>在问题里真实出现</b>的词（大小写不敏感），
     * 按组序去重（对齐 SQLBot 提取结果为逗号分隔关键词串的形态）。
     */
    static List<String> matchedWords(List<Map<String, Object>> groups, String question) {
        List<String> matched = new ArrayList<>();
        if (groups == null || groups.isEmpty() || question == null) {
            return matched;
        }
        String q = question.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> group : groups) {
            for (String word : wordsOf(group)) {
                String lower = word.toLowerCase(Locale.ROOT);
                if (!lower.isEmpty() && q.contains(lower) && seen.add(lower)) {
                    matched.add(word);
                }
            }
        }
        return matched;
    }

    /**
     * 同义词扩展（对齐 SQLBot {@code expand_with_terminology}）：以提取词为种子，
     * 补入命中术语组的其余词（主词与同义词），上限 {@value #MAX_EXPANDED_KEYWORDS} 个。
     */
    static List<String> expandKeywords(List<String> matched, List<Map<String, Object>> groups) {
        List<String> expanded = new ArrayList<>(matched);
        Set<String> seen = new LinkedHashSet<>();
        for (String word : matched) {
            seen.add(word.toLowerCase(Locale.ROOT));
        }
        if (groups != null) {
            for (Map<String, Object> group : groups) {
                for (String word : wordsOf(group)) {
                    if (expanded.size() >= MAX_EXPANDED_KEYWORDS) {
                        return expanded;
                    }
                    if (seen.add(word.toLowerCase(Locale.ROOT))) {
                        expanded.add(word);
                    }
                }
            }
        }
        return expanded.size() > MAX_EXPANDED_KEYWORDS
                ? new ArrayList<>(expanded.subList(0, MAX_EXPANDED_KEYWORDS)) : expanded;
    }

    @SuppressWarnings("unchecked")
    private static List<String> wordsOf(Map<String, Object> group) {
        Object words = group == null ? null : group.get("words");
        if (!(words instanceof List)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) words) {
            if (item != null && !String.valueOf(item).trim().isEmpty()) {
                result.add(String.valueOf(item).trim());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 术语模板（对齐 SQLBot to_xml_string + get_base_terminology_template）
    // ------------------------------------------------------------------

    /**
     * 术语模板文本：XML 形态（{@code <terminologies><terminology><words><word>…</word></words>
     * <description>…</description></terminology></terminologies>），与 SQLBot
     * {@code get_terminology_template} 的产出同构；无命中返回空串。
     */
    static String terminologyTemplate(List<Map<String, Object>> groups) {
        if (groups == null || groups.isEmpty()) {
            return "";
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<terminologies>");
        for (Map<String, Object> group : groups) {
            xml.append("<terminology>");
            xml.append("<words>");
            for (String word : wordsOf(group)) {
                xml.append("<word>").append(escape(word)).append("</word>");
            }
            xml.append("</words>");
            String description = Rows.str(group, "description");
            xml.append("<description>")
                    .append(description == null ? "" : escape(description))
                    .append("</description>");
            xml.append("</terminology>");
        }
        xml.append("</terminologies>");
        return xml.toString();
    }

    /** XML 特殊字符转义（对齐 SQLBot dict_to_xml 的转义集合）。 */
    static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
