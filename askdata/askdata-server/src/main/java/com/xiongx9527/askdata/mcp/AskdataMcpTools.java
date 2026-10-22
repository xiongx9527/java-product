package com.xiongx9527.askdata.mcp;

import com.ajaxjs.mcp.server.feature.annotation.McpService;
import com.ajaxjs.mcp.server.feature.annotation.Tool;
import com.ajaxjs.mcp.server.feature.annotation.ToolArg;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.fw.plus.ai.autoconfigure.langchain4j.support.assistant.SimpleAssistant;
import com.xiongx9527.fw.plus.common.util.SpringUtils;
import com.xiongx9527.askdata.ai.AskdataNl2Sql;
import com.xiongx9527.askdata.datasource.service.DatasourceService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * askdata 的 MCP 工具集（框架 fw-plus-ai-mcp-server 扫描注册）。
 *
 * <p>工具类由框架反射无参实例化，业务服务经框架的 {@link SpringUtils#getBean(Class)} 从容器获取。
 * 能力：列出数据源、服务信息、问数助手对话（框架 {@link SimpleAssistant}，多轮记忆）。</p>
 */
@McpService
public class AskdataMcpTools {

    /**
     * 列出 askdata 数据源（返回 JSON 数组）。
     */
    @Tool(value = "datasource_list", description = "列出 askdata 的全部数据源（JSON 数组）")
    public String datasource_list() throws Exception {
        DatasourceService datasourceService = SpringUtils.getBean(DatasourceService.class);
        List<Map<String, Object>> list = datasourceService.list(null);
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        return mapper.writeValueAsString(list);
    }

    /**
     * 服务信息探针：用于 MCP client 验证 askdata MCP 链路连通。
     */
    @Tool(value = "server_info", description = "返回 askdata MCP 服务信息（连通性探针）")
    public String server_info() throws Exception {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("app", "askdata");
        info.put("mcp", "fw-plus-ai-mcp-server");
        info.put("transport", "http-sse");
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        return mapper.writeValueAsString(info);
    }

    /**
     * 问数助手对话：走框架装配的 {@link SimpleAssistant}（多轮记忆 + thinking 透传能力）。
     * 未装配模型时返回可读错误（不抛异常），由调用方降级。
     */
    @Tool(value = "assistant", description = "问数助手对话（多轮记忆，基于默认对话模型）")
    public String assistant(@ToolArg("message") String message) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Map<String, SimpleAssistant> beans = SpringUtils.getBeansOfType(SimpleAssistant.class);
        if (beans.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "assistant 未装配：需配置默认对话模型并设置 askdata.assistant.enabled=true");
            return mapper.writeValueAsString(err);
        }
        SimpleAssistant assistant = beans.values().iterator().next();
        String answer = assistant.chat("mcp", null, message);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("answer", answer);
        return mapper.writeValueAsString(out);
    }

    /**
     * 问数（NL2SQL）：自然语言 → SQL（框架 SqlDatabaseContentRetriever）→ 只读执行，返回 question/sql/rows。
     * 未装配模型时返回可读错误。
     */
    @Tool(value = "question_answer", description = "问数：自然语言转SQL并只读执行，返回 SQL 与结果行；可指定 datasourceId 在选定数据源上执行（非 MySQL 亦支持）")
    public String question_answer(@ToolArg("question") String question,
                                 @ToolArg(value = "datasourceId", required = false) Long datasourceId) throws Exception {
        ObjectMapper mapper = SpringUtils.getBean(ObjectMapper.class);
        Map<String, AskdataNl2Sql> beans = SpringUtils.getBeansOfType(AskdataNl2Sql.class);
        if (beans.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("error", "question_answer 未装配：需配置默认对话模型并设置 askdata.assistant.enabled=true");
            return mapper.writeValueAsString(err);
        }
        // 验收 A2：datasourceId 非空时在该数据源（可能为 PostgreSQL/Oracle 等非 MySQL）上生成并执行正确方言的 SQL
        Map<String, Object> result = beans.values().iterator().next().ask(question, datasourceId);
        return mapper.writeValueAsString(result);
    }
}
