package com.xiongx9527.askdata.datasource.embedding;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatModelRouter;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表级向量召回离线单测（验收 A1，纯 JUnit + H2，不启动 Spring）：
 * <ol>
 *   <li>中文表名排第一；</li>
 *   <li>反引号/方括号/双引号标识符命中（ASCII 边界感知）；</li>
 *   <li>表名重叠取最长（user vs user_profile、订单 vs 订单表）；</li>
 *   <li>仅命中表注释（custom_comment / table_comment）也排第一；</li>
 *   <li>无 embedding 模型 / 端点不可达时降级且不抛错；</li>
 *   <li>TOP-N 截断（12 张表取 8）。</li>
 * </ol>
 * 另覆盖向量通路（本地 HTTP 桩）与 backfill 异步写回。
 */
class TableEmbeddingServiceTest {

    /** 桩 embedding 服务的词表：向量 = 各关键词出现次数（确定性语义相似度）。 */
    private static final String[] KEYWORDS =
            {"订单", "用户", "商品", "库存", "盘点", "差异", "金额", "状态"};

    /** 测试数据源 id（V2 种子数据只覆盖 ds_id=1，避开种子行）。 */
    private static final long DS = 42L;

    private JdbcTemplate appJdbc;

    private ObjectMapper objectMapper = new ObjectMapper();

    private final IdGenerator ids = new IdGenerator();

    private TableRepository tableRepository;

    private FieldRepository fieldRepository;

    private ChatModelRouter modelRouter;

    private ChatModelClient modelClient;

    private TableEmbeddingService service;

    private HttpServer stubServer;

    @BeforeEach
    void setUp() throws Exception {
        // 每个测试方法独立内存库（DB_CLOSE_DELAY=-1 下同名库跨方法共享，故加唯一后缀）
        appJdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:table_embedding_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = appJdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        tableRepository = new TableRepository(appJdbc, ids);
        fieldRepository = new FieldRepository(appJdbc, ids);
        modelRouter = new ChatModelRouter(new AiModelRepository(appJdbc, ids), objectMapper);
        modelClient = new ChatModelClient(objectMapper);
        service = new TableEmbeddingService(tableRepository, fieldRepository, modelRouter, modelClient,
                appJdbc, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (stubServer != null) {
            stubServer.stop(0);
            stubServer = null;
        }
    }

    // ------------------------------------------------------------------
    // ① 中文表名排第一
    // ------------------------------------------------------------------

    @Test
    void chineseTableNameRanksFirst() {
        table(101, "orders", "订单表", null);
        table(102, "用户表", "用户信息表", null);
        table(103, "products", "商品表", null);
        List<Long> ranked = service.rankTables(DS, "查一下用户表的注册数量");
        assertEquals(102L, ranked.get(0).longValue());
    }

    // ------------------------------------------------------------------
    // ② 反引号/方括号/双引号标识符命中（ASCII 边界感知）
    // ------------------------------------------------------------------

    @Test
    void quotedIdentifiersHit() {
        table(201, "orders", "订单表", null);
        table(202, "users", "用户表", null);
        table(203, "products", "商品表", null);
        assertEquals(201L, service.rankTables(DS, "select * from `orders` where amount > 100")
                .get(0).longValue());
        assertEquals(202L, service.rankTables(DS, "select * from [users]").get(0).longValue());
        assertEquals(201L, service.rankTables(DS, "select * from \"orders\" limit 1").get(0).longValue());
        // 边界：orders_ds 中的 orders 前后是标识符字符，不构成对 orders 的提及
        double[] scores = mentionScores("orders_ds 的汇总数据");
        assertEquals(0D, scoreOf(201, scores));
        assertEquals(0D, scoreOf(202, scores));
        assertEquals(0D, scoreOf(203, scores));
    }

    // ------------------------------------------------------------------
    // ③ 表名重叠取最长（user vs user_profile；订单 vs 订单表）
    // ------------------------------------------------------------------

    @Test
    void overlappingNamesTakeLongest() {
        table(301, "user", "用户", null);
        table(302, "user_profile", "用户资料", null);
        // ASCII 重叠：user_profile 命中时不把分记给 user
        double[] ascii = mentionScores("统计 user_profile 的字段数量");
        assertEquals(0D, scoreOf(301, ascii));
        assertEquals(1D, scoreOf(302, ascii));
        assertEquals(302L, service.rankTables(DS, "统计 user_profile 的字段数量").get(0).longValue());
        // 两者独立出现时各自命中
        double[] both = mentionScores("user 和 user_profile 都要统计");
        assertEquals(1D, scoreOf(301, both));
        assertEquals(1D, scoreOf(302, both));
        // 中文重叠：订单表 命中时不把分记给 订单
        table(303, "订单", "订单", null);
        table(304, "订单表", "订单表", null);
        double[] chinese = mentionScores("订单表明细");
        assertEquals(0D, scoreOf(303, chinese));
        assertEquals(1D, scoreOf(304, chinese));
        assertEquals(304L, service.rankTables(DS, "订单表明细").get(0).longValue());
    }

    // ------------------------------------------------------------------
    // ④ 仅命中表注释
    // ------------------------------------------------------------------

    @Test
    void commentOnlyHitRanksFirst() {
        table(501, "t_stock", "库存盘点表", null);
        table(502, "t_order", "订单表", null);
        table(503, "t_member", "等级表", "会员等级");
        // 仅命中 table_comment
        assertEquals(501L, service.rankTables(DS, "库存盘点表差异有哪些").get(0).longValue());
        // 仅命中 custom_comment
        assertEquals(503L, service.rankTables(DS, "会员等级分布").get(0).longValue());
    }

    // ------------------------------------------------------------------
    // ④-b 引号标识符显式提及优先（对齐 SQLBot _select_tables）
    // ------------------------------------------------------------------

    @Test
    void quotedMentionRanksAboveUnquoted() {
        table(601, "用户表", "用户信息", null);
        table(602, "商品表", "商品信息", null);
        // 两者都在问题中出现（mention 同分），但仅 用户表 被引号显式包裹 → 必须排第一
        List<Long> ranked = service.rankTables(DS, "对比 \"用户表\" 和 商品表 的数量");
        assertEquals(601L, ranked.get(0).longValue());
    }

    @Test
    void quotedTableCommentIsExplicit() {
        table(611, "t_order", "订单表", null);
        table(612, "t_user", "用户表", null);
        // 中文引号包裹的是表注释（订单表），同样算显式提及
        List<Long> ranked = service.rankTables(DS, "统计 “订单表” 的金额，不要 用户表");
        assertEquals(611L, ranked.get(0).longValue());
    }

    @Test
    void quotedTokensExtracted() {
        Set<String> tokens = com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService
                .quotedTokens("select * from `orders` join [users] on \"订单表\" and “商品”");
        assertTrue(tokens.contains("orders"), tokens.toString());
        assertTrue(tokens.contains("users"), tokens.toString());
        assertTrue(tokens.contains("订单表"), tokens.toString());
        assertTrue(tokens.contains("商品"), tokens.toString());
    }

    // ------------------------------------------------------------------
    // ④-c 提及分级：表名命中(1.0) > 表注释子串命中(0.6)（对齐 SQLBot calc_keyword_score）
    // ------------------------------------------------------------------

    @Test
    void nameMatchOutranksCommentMatch() {
        table(701, "t_sales", "销售", null);
        table(702, "t_report", "营收汇总", null);
        // 701 表名命中 t_sales；702 仅注释命中 营收汇总（两者不重叠）
        double[] scores = mentionScores("统计 t_sales 的营收汇总");
        assertEquals(1D, scoreOf(701, scores));
        assertEquals(0.6D, scoreOf(702, scores));
        assertEquals(701L, service.rankTables(DS, "统计 t_sales 的营收汇总").get(0).longValue());
    }

    // ------------------------------------------------------------------
    // ⑤ 无 embedding 模型 / 端点不可达：降级不抛错
    // ------------------------------------------------------------------

    @Test
    void noEmbeddingModelDegradesWithoutError() {
        // ai_model 无行：router.embeddingModel() = empty
        assertNull(service.embed("任意文本"));
        table(601, "orders", "订单表", null);
        table(602, "users", "用户表", null);
        // 表向量缺失/非法也不抛错，仍按提及排序
        appJdbc.update("UPDATE core_table SET table_embedding = ? WHERE id = 601", "not-a-json");
        List<Long> ranked = service.rankTables(DS, "users 表有多少条数据");
        assertEquals(602L, ranked.get(0).longValue());
        // backfill 无模型时不写向量、不抛错（601 的非法串保持原样，602 仍无向量）
        service.backfill(DS);
        assertEquals("not-a-json", tableRepository.findEmbedding(601));
        assertNull(tableRepository.findEmbedding(602));
    }

    @Test
    void unreachableEmbeddingEndpointDegrades() {
        // 配置了 embedding 模型但端点不可达：embed 返回 null，召回退化为提及
        new AiModelRepository(appJdbc, ids).insert(ids.nextId(), "", "http://127.0.0.1:1", 1, 0,
                "stub-bge-m3", 1, "bge-m3", false, "[]", 1, System.currentTimeMillis());
        assertNull(service.embed("任意文本"));
        table(611, "orders", "订单表", null);
        table(612, "users", "用户表", null);
        assertEquals(612L, service.rankTables(DS, "统计 users 的数量").get(0).longValue());
    }

    // ------------------------------------------------------------------
    // ⑥ TOP-N 截断（12 张表取 8）
    // ------------------------------------------------------------------

    @Test
    void topNTruncatesToLimit() {
        for (int i = 1; i <= 12; i++) {
            table(700 + i, String.format("t%02d", i), "表" + i, null);
        }
        List<Long> all = service.rankTables(DS, "t12 的汇总");
        assertEquals(12, all.size());
        assertEquals(712L, all.get(0).longValue());
        List<Long> top8 = service.rankTables(DS, "t12 的汇总", 8);
        assertEquals(8, top8.size());
        assertEquals(712L, top8.get(0).longValue(), "点名表必须留在 TOP-N 内");
        assertTrue(top8.contains(712L));
    }

    // ------------------------------------------------------------------
    // 向量通路（本地 HTTP 桩）+ backfill 异步写回
    // ------------------------------------------------------------------

    @Test
    void vectorPathRanksSemanticMatchAndBackfillWritesEmbedding() throws Exception {
        startEmbeddingStub();
        new AiModelRepository(appJdbc, ids).insert(ids.nextId(), "",
                "http://127.0.0.1:" + stubServer.getAddress().getPort(), 1, 0,
                "stub-bge-m3", 1, "bge-m3", false, "[]", 1, System.currentTimeMillis());
        table(801, "t_stock", "库存表", null);
        field(811, 801, "diff_qty", "decimal", "盘点差异数量", 0);
        table(802, "t_order", "订单表", null);
        field(812, 802, "amount", "decimal", "订单金额", 0);
        table(803, "t_user", "用户表", null);
        field(813, 803, "city", "varchar", "用户城市", 0);

        // backfill 异步写回表向量（轮询等待守护线程完成，向量召回的前提）
        service.backfill(DS);
        for (int i = 0; i < 100; i++) {
            if (tableRepository.findEmbedding(801) != null && tableRepository.findEmbedding(802) != null
                    && tableRepository.findEmbedding(803) != null) {
                break;
            }
            Thread.sleep(50);
        }
        String embedding = tableRepository.findEmbedding(801);
        assertNotNull(embedding, "backfill 应写回 table_embedding");
        JsonNode node = objectMapper.readTree(embedding);
        assertTrue(node.isArray(), embedding);
        assertEquals(KEYWORDS.length, node.size());
        assertEquals(1D, node.get(3).asDouble(), "库存");
        assertEquals(1D, node.get(4).asDouble(), "盘点");
        assertEquals(1D, node.get(5).asDouble(), "差异");

        // 问题不含任何表名/表注释子串，仅与 t_stock 字段语义相关 → 纯向量召回排第一
        List<Long> ranked = service.rankTables(DS, "盘点差异数量是多少");
        assertEquals(801L, ranked.get(0).longValue());
    }

    // ------------------------------------------------------------------
    // 测试基建
    // ------------------------------------------------------------------

    private void table(long id, String name, String tableComment, String customComment) {
        appJdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, "
                        + "table_embedding) VALUES (?, ?, TRUE, ?, ?, ?, NULL)",
                id, DS, name, tableComment, customComment);
    }

    private void field(long id, long tableId, String name, String type, String comment, int index) {
        appJdbc.update("INSERT INTO core_field (id, ds_id, table_id, checked, field_name, field_type, "
                        + "field_comment, custom_comment, field_index) VALUES (?, ?, ?, TRUE, ?, ?, ?, NULL, ?)",
                id, DS, tableId, name, type, comment, index);
    }

    private double[] mentionScores(String question) {
        return service.mentionScores(tableRepository.listByDsIdChecked(DS), question);
    }

    private double scoreOf(long tableId, double[] scores) {
        List<Map<String, Object>> tables = tableRepository.listByDsIdChecked(DS);
        for (int i = 0; i < tables.size(); i++) {
            if (Rows.lng(tables.get(i), "id", 0L) == tableId) {
                return scores[i];
            }
        }
        throw new IllegalStateException("table not found: " + tableId);
    }

    /** 本地 HTTP 桩：OpenAI 兼容 /v1/embeddings，返回关键词计数向量（确定性语义相似度）。 */
    private void startEmbeddingStub() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/v1/embeddings", this::handleEmbeddings);
        stubServer.setExecutor(null);
        stubServer.start();
    }

    private void handleEmbeddings(HttpExchange exchange) {
        try {
            byte[] request = readAll(exchange.getRequestBody());
            JsonNode inputs = objectMapper.readTree(request).get("input");
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    data.append(",");
                }
                data.append("{\"embedding\":").append(keywordVector(inputs.get(i).asText())).append("}");
            }
            data.append("]");
            byte[] body = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } catch (Exception e) {
            // 桩异常不影响断言主体（向量调用方会降级）
        } finally {
            exchange.close();
        }
    }

    private String keywordVector(String text) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < KEYWORDS.length; i++) {
            if (i > 0) {
                vector.append(",");
            }
            int count = 0;
            int from = text.indexOf(KEYWORDS[i]);
            while (from >= 0) {
                count++;
                from = text.indexOf(KEYWORDS[i], from + 1);
            }
            vector.append(count);
        }
        return vector.append("]").toString();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
