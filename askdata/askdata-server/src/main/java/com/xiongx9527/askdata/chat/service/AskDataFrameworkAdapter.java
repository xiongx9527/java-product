package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlGenInput;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlGenResult;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlGenerationCapability;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlGenerationCapabilityBuilder;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlGenerationException;
import com.xiongx9527.fw.plus.ai.askdata.capability.SqlValidationStrategy;
import com.xiongx9527.fw.plus.ai.askdata.chart.ChartFormat;
import com.xiongx9527.fw.plus.ai.askdata.chart.ChartGenInput;
import com.xiongx9527.fw.plus.ai.askdata.chart.ChartGenResult;
import com.xiongx9527.fw.plus.ai.askdata.chart.ChartGenerationCapabilityBuilder;
import com.xiongx9527.fw.plus.ai.askdata.chart.ChartType;
import com.xiongx9527.fw.plus.ai.askdata.chart.data.ChartDataset;
import com.xiongx9527.fw.plus.ai.askdata.execution.SqlExecutionCapabilityBuilder;
import com.xiongx9527.fw.plus.ai.askdata.postprocess.SqlRepairPolicy;
import com.xiongx9527.fw.plus.ai.askdata.postprocess.ValidationResult;
import com.xiongx9527.fw.plus.ai.askdata.prompt.SqlPrompt;
import com.xiongx9527.fw.plus.ai.base.model.LlmPort;
import com.xiongx9527.fw.plus.jdbc.execution.ColumnMeta;
import com.xiongx9527.fw.plus.jdbc.execution.ExecutionSettings;
import com.xiongx9527.fw.plus.jdbc.execution.QueryResult;
import com.xiongx9527.fw.plus.jdbc.execution.SqlExecInput;
import com.xiongx9527.fw.plus.jdbc.execution.SqlExecutionCapability;
import com.xiongx9527.fw.plus.jdbc.execution.SqlExecutorPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品侧装配 / 适配层：把问数主链路的核心步骤委派给框架 {@code fw-plus-ai-askdata} 能力。
 *
 * <p><b>设计原则（对齐用户对框架的要求）</b>：通用能力（SQL 执行、SQL 生成、图表生成）进框架；
 * 产品特有 glue（按 oid 解析故障转移端口、按 dsId 取执行通道、schema/RAG 字段映射）集中在此处，
 * 不污染 {@link ChatQuestionService} 主链路。<b>本类只组装框架能力，不重写任何业务规则</b>。</p>
 *
 * <p>执行通道说明：产品执行实际走 {@code JdbcEngine}（基于 {@code DatasourceConf} 的连接池，
 * 含 ES/HTTP、Decimal 归一、限定列名归一、真实 DB 错误透传），并不暴露 {@code javax.sql.DataSource}。
 * 因此不直接用框架 {@code JdbcSqlExecutor(DataSource)}，而是用一个产品侧 {@link SqlExecutorPort}
 * 把产品执行器适配到框架契约——框架的只读守卫 / 结果自检 / 列级脱敏等通用守卫在 {@link
 * SqlExecutionCapability} 层叠加，产品特有的归一逻辑不丢。</p>
 */
@Component
public class AskDataFrameworkAdapter {

    /** 执行结果行数上限（对齐 SQLBot 默认 1000，chat.limit_rows 关闭时由调用方另行控制）。 */
    private static final int SQL_ROW_LIMIT = 1000;

    /** 执行超时（秒）。产品 JdbcEngine 另有自身超时（取自连接配置），此处仅作为框架侧兜底。 */
    private static final int EXEC_TIMEOUT_SECONDS = 30;

    /** SQL 精炼修复轮重试上限（对齐 {@code ChatQuestionService.MAX_SQL_REPAIR_RETRIES = 2}）。 */
    private static final int SQL_REFINE_REPAIR_RETRIES = 2;

    private final AskDataLlmPort askDataLlmPort;

    private final DatasourceService datasourceService;

    public AskDataFrameworkAdapter(AskDataLlmPort askDataLlmPort, DatasourceService datasourceService) {
        this.askDataLlmPort = askDataLlmPort;
        this.datasourceService = datasourceService;
    }

    /**
     * 按工作空间 {@code oid} 取框架 {@link LlmPort}（含候选链故障转移 + 熔断）。
     * 每问动态解析，无可用模型返回 {@code null}。供 SQL 生成 / 图表生成装配能力时复用。
     */
    public LlmPort llmPort(long oid) {
        return askDataLlmPort.buildLlmPort(oid);
    }

    /**
     * 静态数据源执行：委托框架 {@link SqlExecutionCapability}（产品执行器经 {@link SqlExecutorPort} 适配）。
     * 返回形态与产品 {@code datasourceService.execSql} 一致：{@code {fields:[...], data:[{col:val}...]}}。
     */
    public Map<String, Object> executeSql(String sql, long dsId) {
        SqlExecutorPort port = new StaticSqlExecutorPort(dsId);
        SqlExecutionCapability capability = SqlExecutionCapabilityBuilder.builder().executor(port).build();
        QueryResult result = capability.execute(SqlExecInput.builder()
                .sql(sql)
                .maxRows(SQL_ROW_LIMIT)
                .queryTimeoutSeconds(EXEC_TIMEOUT_SECONDS)
                .readOnly(true)
                .selfCheck(true)
                .build());
        return toProductResult(result);
    }

    /**
     * 动态（未入库）数据源执行：与静态路径共用框架执行能力，仅底层执行通道不同。
     */
    public Map<String, Object> executeSqlTransient(String sql, DatasourceType type, DatasourceConf conf) {
        SqlExecutorPort port = new TransientSqlExecutorPort(type, conf);
        SqlExecutionCapability capability = SqlExecutionCapabilityBuilder.builder().executor(port).build();
        QueryResult result = capability.execute(SqlExecInput.builder()
                .sql(sql)
                .maxRows(SQL_ROW_LIMIT)
                .queryTimeoutSeconds(EXEC_TIMEOUT_SECONDS)
                .readOnly(true)
                .selfCheck(true)
                .build());
        return toProductResult(result);
    }

    /**
     * 图表生成（G2 模式）：委派框架 {@link com.xiongx9527.fw.plus.ai.askdata.chart.ChartGenerationCapability}，
     * 模型直接产出 G2 风格配置（type/title/columns/axis），能力层原样透传，不构造 ECharts。
     *
     * <p>返回 {@link ChartGenResult}：{@code config} 即前端可消费的 G2 配置 Map，
     * {@code rawOutput} 为模型原始输出（供 A26 落库原始思考）。无可用模型或任一环节异常时返回
     * {@code null}，由调用方回退产品侧启发式构造。</p>
     */
    public ChartGenResult generateChart(long oid, String question, Map<String, Object> result,
            String sql, String mSchema, String chartType) {
        LlmPort port = llmPort(oid);
        if (port == null) {
            return null;
        }
        ChartDataset ds = toChartDataset(result);
        if (ds == null || ds.rowCount() == 0) {
            return null;
        }
        ChartType preferred = (chartType == null || chartType.trim().isEmpty()) ? null : ChartType.fromHint(chartType);
        ChartGenInput input = ChartGenInput.builder()
                .question(question)
                .data(ds)
                .sql(sql)
                .mSchema(mSchema)
                .preferredType(preferred)
                .chartFormat(ChartFormat.G2)
                .build();
        try {
            return ChartGenerationCapabilityBuilder.builder()
                    .llm(port)
                    .chartFormat(ChartFormat.G2)
                    .build()
                    .generate(input);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * SQL 精炼（抽取 → 本地校验 → 修复）：委派框架 {@link SqlGenerationCapability#refine}，
     * 复用框架通用 SQL 引擎（抽取 / 校验 / 修复轮），产品保留自有提示词组装、流式输出与
     * {@code validateTables}/{@code validateSql}/动态数据源 glue（这些不在框架内）。
     *
     * <p>返回框架 {@link SqlGenResult}：{@code valid} 表示经抽取 + 本地校验 + 修复后 SQL 可用；
     * {@code sql/brief/chartType/tables} 由框架从模型 JSON 协议透明抽取；{@code success} 透传模型自报标志。
     * 无可用模型端口时返回 {@code null}，调用方回退产品原生 ①号解析 + 修复轮。框架修复后仍不可用
     * （fail-closed）时返回 {@code valid=false} 的结果（{@code explanation} 携带失败原因），调用方据此报错。</p>
     *
     * <p>仅做本地校验（DML 禁用 / 括号平衡），不启用 LLM 语义校验，对齐产品 ①号只做 JSON 解析的语义；
     * 表可见性 / 方言等由产品后续 {@code validateTables}/{@code validateSql} 把关。</p>
     */
    public SqlGenResult refineSql(long oid, String question, String rawOutput,
            List<ChatModelClient.ModelMessage> sqlMessages) {
        LlmPort port = llmPort(oid);
        if (port == null) {
            return null;
        }
        String system = null;
        String user = null;
        for (ChatModelClient.ModelMessage m : sqlMessages) {
            if ("system".equals(m.role) && system == null) {
                system = m.content;
            } else if ("user".equals(m.role)) {
                user = m.content;
            }
        }
        if (system == null || user == null) {
            return null;
        }
        SqlPrompt prompt = new SqlPrompt(system, user);
        SqlGenInput meta = SqlGenInput.builder().question(question == null ? "" : question).build();
        // 关闭 LLM 语义校验，避免 ①号 阶段引入额外 LLM 调用与误判；仅本地规则校验 + 自我修复。
        SqlValidationStrategy localOnly = new SqlValidationStrategy() {
            @Override
            public ValidationResult validate(String sql, SqlGenInput input) {
                return ValidationResult.ok(Collections.emptyList());
            }
        };
        SqlGenerationCapability cap = SqlGenerationCapabilityBuilder.builder()
                .llm(port)
                .llmValidation(localOnly)
                .repairPolicy(SqlRepairPolicy.builder()
                        .enabled(true)
                        .maxAttempts(SQL_REFINE_REPAIR_RETRIES)
                        .failClosed(true)
                        .build())
                .build();
        try {
            return cap.refine(rawOutput, prompt, meta);
        } catch (SqlGenerationException e) {
            return SqlGenResult.builder().valid(false).success(false).explanation(e.getMessage()).build();
        }
    }

    /** 产品执行结果（{@code {fields,data}}）→ 框架 {@link ChartDataset}，供图表能力消费。 */
    private static ChartDataset toChartDataset(Map<String, Object> productResult) {
        QueryResult qr = fromProduct(productResult);
        if (qr == null) {
            return null;
        }
        return ChartDataset.from(qr);
    }

    // ------------------------------------------------------------------
    // 产品执行器 → 框架 SqlExecutorPort 适配（产品 glue，保留归一与真实错误）
    // ------------------------------------------------------------------

    /** 静态数据源执行端口：委托产品 {@code DatasourceService.execSql(id, sql)}。 */
    private final class StaticSqlExecutorPort implements SqlExecutorPort {
        private final long dsId;

        StaticSqlExecutorPort(long dsId) {
            this.dsId = dsId;
        }

        @Override
        public QueryResult execute(String sql, ExecutionSettings settings) {
            return fromProduct(datasourceService.execSql(dsId, sql));
        }
    }

    /** 动态数据源执行端口：委托产品 {@code DatasourceService.execSqlTransient(type, conf, sql)}。 */
    private final class TransientSqlExecutorPort implements SqlExecutorPort {
        private final DatasourceType type;
        private final DatasourceConf conf;

        TransientSqlExecutorPort(DatasourceType type, DatasourceConf conf) {
            this.type = type;
            this.conf = conf;
        }

        @Override
        public QueryResult execute(String sql, ExecutionSettings settings) {
            return fromProduct(datasourceService.execSqlTransient(type, conf, sql));
        }
    }

    // ------------------------------------------------------------------
    // 结果映射：框架 QueryResult ↔ 产品 {fields, data}
    // ------------------------------------------------------------------

    /** 框架 {@link QueryResult}（列式）转回产品形态（字段名列表 + 行对象列表），主链路其余代码无需改动。 */
    private static Map<String, Object> toProductResult(QueryResult qr) {
        List<String> fields = new ArrayList<>();
        if (qr.getColumns() != null) {
            for (ColumnMeta c : qr.getColumns()) {
                fields.add(c.getName());
            }
        }
        List<Map<String, Object>> data = new ArrayList<>();
        if (qr.getRows() != null) {
            for (List<Object> row : qr.getRows()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (int i = 0; i < fields.size(); i++) {
                    m.put(fields.get(i), i < row.size() ? row.get(i) : null);
                }
                data.add(m);
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fields", fields);
        result.put("data", data);
        return result;
    }

    /** 产品执行结果（{@code {fields:[String], data:[{col:val}]}}）转为框架 {@link QueryResult}。 */
    @SuppressWarnings("unchecked")
    private static QueryResult fromProduct(Map<String, Object> product) {
        List<String> fields = new ArrayList<>();
        Object f = product.get("fields");
        if (f instanceof List) {
            for (Object o : (List<?>) f) {
                fields.add(o == null ? "" : String.valueOf(o));
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        Object d = product.get("data");
        if (d instanceof List) {
            for (Object rowObj : (List<?>) d) {
                if (rowObj instanceof Map) {
                    Map<String, Object> rowMap = (Map<String, Object>) rowObj;
                    List<Object> row = new ArrayList<Object>();
                    for (String field : fields) {
                        row.add(rowMap.get(field));
                    }
                    rows.add(row);
                } else if (rowObj instanceof List) {
                    rows.add(new ArrayList<Object>((List<Object>) rowObj));
                }
            }
        }
        List<ColumnMeta> cols = new ArrayList<ColumnMeta>();
        for (String field : fields) {
            cols.add(new ColumnMeta(field, null, true));
        }
        return QueryResult.builder().columns(cols).rows(rows).build();
    }
}
