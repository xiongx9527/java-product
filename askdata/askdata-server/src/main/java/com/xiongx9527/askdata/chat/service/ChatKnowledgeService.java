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
import java.util.Optional;
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

    /** LLM 实体抽取单次返回实体上限（避免模型输出噪声膨胀关键词）。 */
    private static final int MAX_LLM_ENTITIES = 30;

    /** LLM 业务实体抽取系统提示词（对齐 SQLBot extract_keywords：只抽实体、忽略时间/动作/修饰词）。 */

    private final TerminologyRecallService terminologyRecallService;

    private final SchemaContextBuilder schemaContextBuilder;

    private final JdbcTemplate jdbc;

    private final ChatModelRouter modelRouter;

    private final ChatModelClient modelClient;

    public ChatKnowledgeService(TerminologyRecallService terminologyRecallService,
                                SchemaContextBuilder schemaContextBuilder, JdbcTemplate jdbc,
                                ChatModelRouter modelRouter, ChatModelClient modelClient) {
        this.terminologyRecallService = terminologyRecallService;
        this.schemaContextBuilder = schemaContextBuilder;
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.modelClient = modelClient;
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

        /** 术语召回是否因无 embedding 模型而降级为词法匹配（验收 A3）。 */
        private final boolean terminologyDegraded;

        /** SQL 示例是否因 data_training 表缺失而不可用（验收 A3）。 */
        private final boolean sqlExampleDegraded;

        /** 关键词提取是否走了 LLM 实体抽取（false 表示退化为词法命中，用于可观测，见 A2/2.1）。 */
        private final boolean keywordLlmUsed;

        KnowledgeContext(String extractedKeywords, String expandedKeywords, String terminologyText,
                         List<Map<String, Object>> termGroups, List<String> sqlExamples,
                         List<String> customPrompts, boolean terminologyDegraded,
                         boolean sqlExampleDegraded, boolean keywordLlmUsed) {
            this.extractedKeywords = extractedKeywords;
            this.expandedKeywords = expandedKeywords;
            this.terminologyText = terminologyText;
            this.termGroups = termGroups;
            this.sqlExamples = sqlExamples;
            this.customPrompts = customPrompts;
            this.terminologyDegraded = terminologyDegraded;
            this.sqlExampleDegraded = sqlExampleDegraded;
            this.keywordLlmUsed = keywordLlmUsed;
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

        /** 术语召回是否因无 embedding 模型而降级（验收 A3）。 */
        public boolean isTerminologyDegraded() {
            return terminologyDegraded;
        }

        /** SQL 示例是否因 data_training 表缺失而不可用（验收 A3）。 */
        public boolean isSqlExampleDegraded() {
            return sqlExampleDegraded;
        }

        /** 关键词提取是否走了 LLM 实体抽取（false = 退化为词法命中）。 */
        public boolean isKeywordLlmUsed() {
            return keywordLlmUsed;
        }

        /** 降级原因清单（验收 A3：可观测的状态标志，供日志/测试断言）。 */
        public List<String> degradationReasons() {
            List<String> reasons = new ArrayList<String>();
            if (terminologyDegraded) {
                reasons.add("terminology: embedding model unavailable, degraded to lexical matching");
            }
            if (sqlExampleDegraded) {
                reasons.add("sql_example: data_training table unavailable");
            }
            return reasons;
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
        return prepare(question, oid, dsId, null, null);
    }

    /**
     * 执行知识增强四步（A9：高级应用知识范围隔离）。
     *
     * @param advancedAppId 高级应用 id（可空）；非空时术语/训练样例/自定义提示词均按该应用隔离，
     *                      其他应用的知识不会进入本次提示词
     */
    public KnowledgeContext prepare(String question, long oid, Long dsId, Long advancedAppId) {
        return prepare(question, oid, dsId, advancedAppId, null);
    }

    /**
     * 执行知识增强四步（A9 隔离 + A2 LLM 实体抽取带历史上下文）。
     *
     * @param historyText 前序 Q&A 文本（可空）；非空时一并喂给 LLM 实体抽取，使追问能解析前文实体
     */
    public KnowledgeContext prepare(String question, long oid, Long dsId, Long advancedAppId, String historyText) {
        // 验收 A3：显式探测并登记降级，避免术语/示例在 embedding 缺失或表缺失时静默失效
        boolean terminologyDegraded = !modelRouter.embeddingModel().isPresent();
        boolean sqlExampleDegraded = !schemaContextBuilder.trainingTableAvailable();
        if (terminologyDegraded) {
            log.warn("术语召回降级：未配置 embedding 模型，退化为词法匹配（oid={}, dsId={}）", oid, dsId);
        }
        if (sqlExampleDegraded) {
            log.warn("SQL 示例降级：data_training 表不可用，few-shot 示例缺失（oid={}, dsId={}）", oid, dsId);
        }
        // §2.5：对齐 SQLBot 的顺序——先抽取关键词（带历史），再用「抽取词 or 原问题」做术语匹配
        // （llm.py:384 match_text = extracted_keywords or question）。此前是先按原始整句召回术语，
        // 抽取结果只进关键词落库，导致召回精度低于 SQLBot。
        List<String> llmEntities = extractEntitiesWithLlm(question, historyText);
        boolean keywordLlmUsed = llmEntities != null;
        String matchText = keywordLlmUsed && !llmEntities.isEmpty()
                ? String.join(",", llmEntities) : question;
        List<Map<String, Object>> groups = recallTerms(matchText, oid, dsId, advancedAppId);
        List<String> matched = matchedWords(groups, matchText);
        Set<String> extractedSet = new LinkedHashSet<>(matched);
        if (llmEntities != null) {
            for (String entity : llmEntities) {
                if (entity != null && !entity.trim().isEmpty()) {
                    extractedSet.add(entity.trim());
                }
            }
        }
        List<String> extractedList = new ArrayList<>(extractedSet);
        String extracted = String.join(",", extractedList);
        String expanded = String.join(",", expandKeywords(extractedList, groups));
        String terminologyText = terminologyTemplate(groups);
        List<String> sqlExamples = trainingSamples(dsId, question, advancedAppId);
        List<String> customPrompts = customPrompts(oid, dsId, advancedAppId, "GENERATE_SQL");
        return new KnowledgeContext(extracted, expanded, terminologyText, groups, sqlExamples, customPrompts,
                terminologyDegraded, sqlExampleDegraded, keywordLlmUsed);
    }

    /** 术语召回（向量 + 词法；向量不可用时仅词法，不报错）。 */
    private List<Map<String, Object>> recallTerms(String question, long oid, Long dsId, Long advancedAppId) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return terminologyRecallService.recallGroups(question, oid, dsId, advancedAppId);
        } catch (Exception e) {
            log.info("术语召回失败（降级为无术语）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 训练样例召回（data_training 缺失或失败时返回空，不报错）。 */
    private List<String> trainingSamples(Long dsId, String question, Long advancedAppId) {
        if (dsId == null) {
            return new ArrayList<>();
        }
        try {
            List<String> samples = schemaContextBuilder.trainingSamples(dsId, question, advancedAppId);
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
    private List<String> customPrompts(long oid, Long dsId, Long advancedAppId, String type) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT prompt FROM custom_prompt WHERE oid = ? AND type = ? ");
            List<Object> args = new ArrayList<>();
            args.add(oid);
            args.add(type == null || type.isEmpty() ? "GENERATE_SQL" : type);
            if (advancedAppId != null) {
                // A9：高级应用知识范围隔离——仅该应用的自定义提示词
                sql.append(" AND advanced_application = ?");
                args.add(advancedAppId);
            }
            if (dsId == null) {
                sql.append(" AND (specific_ds = FALSE OR specific_ds IS NULL) ORDER BY create_time");
            } else {
                sql.append(" AND (specific_ds = FALSE OR specific_ds IS NULL OR (");
                sql.append("INSTR(',' || COALESCE(REPLACE(REPLACE(datasource_ids, '[', ''), ']', ''), '') ");
                sql.append("|| ',', ',' || ? || ',') > 0)) ORDER BY create_time");
                args.add(String.valueOf(dsId));
            }
            return jdbc.queryForList(sql.toString(), String.class, args.toArray());
        } catch (Exception e) {
            // custom_prompt 表缺失/列不一致时降级为空，不影响主链路
            log.info("自定义提示词召回失败（降级为空）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * §2.2: 按提示词类型召回自定义提示词（SQLBot custom_prompt.type：GENERATE_SQL / ANALYSIS / PREDICT_DATA），
     * 供分析/预测链路取对应类型提示词；数据源范围隔离与主链路一致。
     */
    public List<String> customPromptsForType(long oid, Long dsId, String type) {
        return customPrompts(oid, dsId, null, type);
    }

    // ------------------------------------------------------------------
    // LLM 业务实体抽取（A2 / 2.1：对齐 SQLBot apps/chat/task/llm.py extract_keywords）
    // ------------------------------------------------------------------

    /**
     * LLM 业务实体抽取：从「当前问题 + 前序问答」中抽核心业务实体，忽略时间/动作/修饰词。
     * 未配置可用对话模型或调用失败时返回 null（调用方退化为词法命中，不影响主链路）。
     */
    private List<String> extractEntitiesWithLlm(String question, String historyText) {
        Optional<ChatModelRouter.ModelRef> chat = modelRouter.defaultChatModel();
        if (!chat.isPresent() || !chat.get().usable()) {
            return null;
        }
        ChatModelRouter.ModelRef model = chat.get();
        List<ChatModelClient.ModelMessage> messages = new ArrayList<>();
        messages.add(new ChatModelClient.ModelMessage("system", com.xiongx9527.askdata.common.i18n.I18n.msg("prompt.entity.extraction")));
        StringBuilder user = new StringBuilder();
        if (historyText != null && !historyText.trim().isEmpty()) {
            user.append("【前文问答（解析实体时请优先引用其中已出现的表名、列名与业务实体）】\n")
                    .append(historyText).append("\n\n");
        }
        user.append("【当前问题】\n").append(question == null ? "" : question);
        messages.add(new ChatModelClient.ModelMessage("user", user.toString()));
        try {
            String raw = modelClient.generate(model.apiDomain, model.apiKey, model.baseModel, messages,
                    model.params);
            return parseEntities(raw);
        } catch (Exception e) {
            log.info("LLM 关键词抽取失败（退化为词法命中）: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 LLM 输出为实体列表：去代码块、按逗号/顿号/分号/换行切分、去空去重、上限 {@value #MAX_LLM_ENTITIES}。 */
    private static List<String> parseEntities(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int end = text.lastIndexOf("```");
            if (end > 3) {
                text = text.substring(3, end).trim();
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : text.split("[,，、;；\n\r]+")) {
            String word = part.trim();
            if (!word.isEmpty() && seen.size() < MAX_LLM_ENTITIES) {
                seen.add(word);
            }
        }
        return seen.isEmpty() ? null : new ArrayList<>(seen);
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
