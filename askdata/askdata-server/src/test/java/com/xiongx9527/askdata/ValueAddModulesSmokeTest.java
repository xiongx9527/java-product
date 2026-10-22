package com.xiongx9527.askdata;

import com.xiongx9527.askdata.system.auth.CryptoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增值模块定向 smoke 验证（A9 训练/术语/提示词、A10 仪表盘、A13 审计/数据权限/嵌入）：
 * 经真实 HTTP + Sa-Token 登录态跑通管理面 CRUD/启停/导入导出/密钥重置。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:askdata-value-add-test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class ValueAddModulesSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    private String token;

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/login/access-token")
                        .param("username", CryptoUtil.encryptSqlbot("admin"))
                        .param("password", CryptoUtil.encryptSqlbot("SQLBot@123456")))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":0"), "login response: " + body);
        // data.access_token
        int start = body.indexOf("\"access_token\":\"");
        assertTrue(start > 0, "token missing: " + body);
        int from = start + "\"access_token\":\"".length();
        int end = body.indexOf('"', from);
        return body.substring(from, end);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("X-SQLBOT-TOKEN", "Bearer " + token);
    }

    private void ensureLogin() throws Exception {
        if (token == null) {
            token = login();
        }
    }

    // ------------------------------------------------------------------
    // A9 训练/术语/提示词
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A9：SQL 示例训练 CRUD/启停/导出/模板")
    void dataTrainingCrud() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String create = "{\"question\":\"smoke-" + suffix + "\",\"description\":\"SELECT 1\",\"datasource\":1}";
        MvcResult created = mockMvc.perform(auth(put("/api/v1/system/data-training")
                        .contentType(MediaType.APPLICATION_JSON).content(create)))
                .andExpect(status().isOk()).andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(createdBody.contains("\"code\":0"), createdBody);
        long id = Long.parseLong(createdBody.replaceAll("(?s).*\"data\":\"?(\\d+).*", "$1"));

        MvcResult page = mockMvc.perform(auth(get("/api/v1/system/data-training/page/1/10")))
                .andExpect(status().isOk()).andReturn();
        String pageBody = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pageBody.contains("smoke-" + suffix), pageBody);
        assertTrue(pageBody.contains("total_count"), pageBody);

        mockMvc.perform(auth(get("/api/v1/system/data-training/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/data-training/" + id + "/enable/false")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/data-training/" + id + "/enable/true")))
                .andExpect(status().isOk());

        MockHttpServletResponse export = mockMvc.perform(auth(get("/api/v1/system/data-training/export")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(export.getContentType().contains("spreadsheetml"), export.getContentType());
        assertTrue(export.getContentAsByteArray().length > 0, "export bytes empty");

        MockHttpServletResponse template = mockMvc.perform(auth(get("/api/v1/system/data-training/template")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(template.getContentAsByteArray().length > 0, "template bytes empty");

        mockMvc.perform(auth(delete("/api/v1/system/data-training")
                        .contentType(MediaType.APPLICATION_JSON).content("[" + id + "]")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A9：术语库 CRUD/启停/导出/settings 命名空间")
    void terminologyCrud() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String word = "term-" + suffix;
        String create = "{\"word\":\"" + word + "\",\"description\":\"术语描述\",\"specific_ds\":false,"
                + "\"other_words\":[\"同义词-" + suffix + "\"]}";
        MvcResult created = mockMvc.perform(auth(put("/api/v1/system/terminology")
                        .contentType(MediaType.APPLICATION_JSON).content(create)))
                .andExpect(status().isOk()).andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(createdBody.contains("\"code\":0"), createdBody);
        long id = Long.parseLong(createdBody.replaceAll("(?s).*\"data\":\"?(\\d+).*", "$1"));

        MvcResult page = mockMvc.perform(auth(get("/api/v1/system/terminology/page/1/10")))
                .andExpect(status().isOk()).andReturn();
        String pageBody = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pageBody.contains(word), pageBody);
        assertTrue(pageBody.contains("同义词-" + suffix), pageBody);
        assertTrue(pageBody.contains("\"other_words\""), pageBody);

        // ds_list 筛选（specific_ds=false 时命中；同时验证 INSTR JSON 包含语法）
        mockMvc.perform(auth(get("/api/v1/system/terminology/page/1/10?ds_list=1")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(get("/api/v1/system/terminology/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/terminology/" + id + "/enable/false")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/terminology/" + id + "/enable/true")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/terminology/export")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/terminology/template")))
                .andExpect(status().isOk());

        MvcResult settingsPage = mockMvc.perform(auth(get("/api/v1/settings/terminology/pager/1/10")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(settingsPage.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(word));
        mockMvc.perform(auth(get("/api/v1/settings/terminology/" + id)))
                .andExpect(status().isOk());

        mockMvc.perform(auth(delete("/api/v1/system/terminology")
                        .contentType(MediaType.APPLICATION_JSON).content("[" + id + "]")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A9：自定义提示词 CRUD/导出")
    void customPromptCrud() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "prompt-" + suffix;
        String create = "{\"type\":\"GENERATE_SQL\",\"name\":\"" + name + "\",\"prompt\":\"生成SQL\","
                + "\"specific_ds\":false}";
        MvcResult created = mockMvc.perform(auth(put("/api/v1/system/custom_prompt")
                        .contentType(MediaType.APPLICATION_JSON).content(create)))
                .andExpect(status().isOk()).andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(createdBody.contains("\"code\":0"), createdBody);
        long id = Long.parseLong(createdBody.replaceAll("(?s).*\"data\":\"?(\\d+).*", "$1"));

        MvcResult page = mockMvc.perform(auth(post("/api/v1/system/custom_prompt/GENERATE_SQL/page/1/10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isOk()).andReturn();
        String pageBody = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pageBody.contains(name), pageBody);

        mockMvc.perform(auth(get("/api/v1/system/custom_prompt/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/v1/system/custom_prompt/GENERATE_SQL/export")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get("/api/v1/system/custom_prompt/template")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(delete("/api/v1/system/custom_prompt")
                        .contentType(MediaType.APPLICATION_JSON).content("[" + id + "]")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // A10 仪表盘
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A10：仪表盘资源/画布 CRUD/加载/校验/移动/删除")
    void dashboardCrud() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String folderName = "folder-" + suffix;
        String canvasName = "canvas-" + suffix;

        MvcResult folder = mockMvc.perform(auth(post("/api/v1/dashboard/create_resource")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + folderName + "\",\"node_type\":\"folder\",\"pid\":\"root\","
                                + "\"type\":\"dashboard\",\"level\":0}")))
                .andExpect(status().isOk()).andReturn();
        String folderBody = folder.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String folderId = folderBody.replaceAll("(?s).*\"id\":\"([0-9a-f]+)\".*", "$1");
        assertNotNull(folderId);

        MvcResult canvas = mockMvc.perform(auth(post("/api/v1/dashboard/create_canvas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + canvasName + "\",\"node_type\":\"leaf\",\"pid\":\""
                                + folderId + "\",\"type\":\"dashboard\",\"level\":1,"
                                + "\"component_data\":\"[]\",\"canvas_style_data\":\"{}\","
                                + "\"canvas_view_info\":\"{}\"}")))
                .andExpect(status().isOk()).andReturn();
        String canvasBody = canvas.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String canvasId = canvasBody.replaceAll("(?s).*\"id\":\"([0-9a-f]+)\".*", "$1");

        MvcResult list = mockMvc.perform(auth(post("/api/v1/dashboard/list_resource")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isOk()).andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(listBody.contains(folderName), listBody);
        assertTrue(listBody.contains(canvasName), listBody);
        assertTrue(listBody.contains("children"), listBody);

        MvcResult loaded = mockMvc.perform(auth(post("/api/v1/dashboard/load_resource")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + canvasId + "\"}")))
                .andExpect(status().isOk()).andReturn();
        String loadedBody = loaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(loadedBody.contains(canvasName), loadedBody);
        assertTrue(loadedBody.contains("component_data"), loadedBody);
        assertTrue(loadedBody.contains("create_name"), loadedBody);

        MvcResult check = mockMvc.perform(auth(post("/api/v1/dashboard/check_name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opt\":\"newLeaf\",\"name\":\"" + canvasName + "\",\"pid\":\"root\"}")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(check.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"data\":false"),
                "duplicate name should be rejected");

        mockMvc.perform(auth(post("/api/v1/dashboard/update_canvas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + canvasId + "\",\"name\":\"" + canvasName + "-renamed\","
                                + "\"component_data\":\"[]\",\"canvas_style_data\":\"{}\","
                                + "\"canvas_view_info\":\"{}\"}")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(delete("/api/v1/dashboard/move_resource/" + folderId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pid\":\"root\"}")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(delete("/api/v1/dashboard/delete_resource/" + canvasId + "/x")))
                .andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/v1/dashboard/delete_resource/" + folderId + "/x")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // A13 审计/数据权限/嵌入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A13：审计日志分页/选项/导出")
    void auditPagerAndExport() throws Exception {
        ensureLogin();
        // 先触发一次被审计的操作（创建后删除仪表盘目录），保证审计有数据
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(auth(post("/api/v1/dashboard/create_resource")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"audit-" + suffix + "\",\"node_type\":\"folder\",\"pid\":\"root\"}")))
                .andExpect(status().isOk()).andReturn();
        String folderId = created.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?s).*\"id\":\"([0-9a-f]+)\".*", "$1");
        mockMvc.perform(auth(delete("/api/v1/dashboard/delete_resource/" + folderId + "/x")))
                .andExpect(status().isOk());

        MvcResult page = mockMvc.perform(auth(get("/api/v1/system/audit/page/1/10")))
                .andExpect(status().isOk()).andReturn();
        String pageBody = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pageBody.contains("\"data\""), pageBody);
        assertTrue(pageBody.contains("total_count"), pageBody);
        assertTrue(pageBody.contains("operation_type_name"), pageBody);
        assertTrue(!pageBody.contains("\"total_count\":0"), "audit should have records: " + pageBody);

        MvcResult options = mockMvc.perform(auth(get("/api/v1/system/audit/get_options")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(options.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("value"));

        // 多值筛选参数（__ 分隔）与状态筛选
        mockMvc.perform(auth(get("/api/v1/system/audit/page/1/10?log_status=success&opt_type_list=create__delete")))
                .andExpect(status().isOk());

        MockHttpServletResponse export = mockMvc.perform(auth(get("/api/v1/system/audit/export")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(export.getContentType().contains("spreadsheetml"), export.getContentType());
        assertTrue(export.getContentAsByteArray().length > 0);
    }

    @Test
    @DisplayName("A13：数据权限配置保存/查询/删除")
    void dsPermissionSaveAndDelete() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String save = "{\"name\":\"rule-" + suffix + "\",\"users\":[],\"permissions\":[{"
                + "\"name\":\"col-1\",\"type\":\"column\",\"ds_id\":1,\"table_id\":1,\"ds_name\":\"ds\","
                + "\"table_name\":\"t\",\"permissions\":\"[]\",\"expression_tree\":\"{}\"}]}";
        MvcResult saved = mockMvc.perform(auth(post("/api/v1/ds_permission/save")
                        .contentType(MediaType.APPLICATION_JSON).content(save)))
                .andExpect(status().isOk()).andReturn();
        String savedBody = saved.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(savedBody.contains("\"code\":0"), savedBody);
        long ruleId = Long.parseLong(savedBody.replaceAll("(?s).*\"data\":\"?(\\d+).*", "$1"));

        MvcResult list = mockMvc.perform(auth(post("/api/v1/ds_permission/list")))
                .andExpect(status().isOk()).andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(listBody.contains("rule-" + suffix), listBody);
        assertTrue(listBody.contains("permission_list"), listBody);
        assertTrue(listBody.contains("\"tree\""), listBody);

        mockMvc.perform(auth(post("/api/v1/ds_permission/delete/" + ruleId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A13：assistant 与 embedded CRUD/密钥重置")
    void assistantAndEmbeddedCrud() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "assistant-" + suffix;

        MvcResult created = mockMvc.perform(auth(post("/api/v1/system/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"domain\":\"http://localhost:8080\","
                                + "\"type\":0,\"description\":\"smoke\"}")))
                .andExpect(status().isOk()).andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long assistantId = Long.parseLong(createdBody.replaceAll("(?s).*\"id\":\"?(\\d+).*", "$1"));
        assertTrue(createdBody.contains("app_id"), "app_id should be generated");

        MvcResult list = mockMvc.perform(auth(get("/api/v1/system/assistant")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(list.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(name));

        mockMvc.perform(auth(get("/api/v1/system/assistant/advanced_application")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(put("/api/v1/system/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + assistantId + ",\"name\":\"" + name + "-upd\","
                                + "\"domain\":\"http://localhost:8080\",\"type\":0,\"description\":\"smoke\"}")))
                .andExpect(status().isOk());

        mockMvc.perform(auth(delete("/api/v1/system/assistant/" + assistantId)))
                .andExpect(status().isOk());

        // embedded（type=4 页面嵌入）
        String embeddedName = "embedded-" + suffix;
        MvcResult embedded = mockMvc.perform(auth(post("/api/v1/system/embedded")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + embeddedName + "\",\"domain\":\"http://localhost:8080\","
                                + "\"type\":4,\"description\":\"smoke\"}")))
                .andExpect(status().isOk()).andReturn();
        String embeddedBody = embedded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long embeddedId = Long.parseLong(embeddedBody.replaceAll("(?s).*\"id\":\"?(\\d+).*", "$1"));

        MvcResult page = mockMvc.perform(auth(get("/api/v1/system/embedded/1/10")))
                .andExpect(status().isOk()).andReturn();
        String pageBody = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(pageBody.contains(embeddedName), pageBody);
        assertTrue(pageBody.contains("\"items\""), pageBody);
        assertTrue(pageBody.contains("\"total\""), pageBody);

        mockMvc.perform(auth(get("/api/v1/system/embedded/" + embeddedId)))
                .andExpect(status().isOk());

        MvcResult secret = mockMvc.perform(auth(patch("/api/v1/system/embedded/secret/" + embeddedId)))
                .andExpect(status().isOk()).andReturn();
        assertTrue(secret.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"code\":0"));

        mockMvc.perform(auth(delete("/api/v1/system/embedded")
                        .contentType(MediaType.APPLICATION_JSON).content("[" + embeddedId + "]")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A13：assistant 外观配置(ui)与图片(picture)")
    void assistantUiAndPicture() throws Exception {
        ensureLogin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "ui-assistant-" + suffix;
        MvcResult created = mockMvc.perform(auth(post("/api/v1/system/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"domain\":\"http://localhost:8080\","
                                + "\"type\":0,\"description\":\"smoke\"}")))
                .andExpect(status().isOk()).andReturn();
        long assistantId = Long.parseLong(created.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?s).*\"id\":\"?(\\d+).*", "$1"));

        // 1x1 PNG；文件名带 ,logo 标志位（对齐前端 SetUi 约定）
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        MockMultipartFile logo = new MockMultipartFile("files", "logo.png,logo", "image/png", png);
        mockMvc.perform(auth(multipart("/api/v1/system/assistant/ui")
                        .file(logo)
                        .param("data", "{\"id\":" + assistantId + ",\"theme\":\"#1E90FF\","
                                + "\"welcome\":\"欢迎使用\"}")
                        .with(request -> {
                            request.setMethod("PATCH");
                            return request;
                        })))
                .andExpect(status().isOk());

        // 配置已合并：logo fileId 与主题写入 configuration
        MvcResult loaded = mockMvc.perform(auth(get("/api/v1/system/assistant")))
                .andExpect(status().isOk()).andReturn();
        String body = loaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("#1E90FF"), body);
        int cfgStart = body.indexOf("\"configuration\":\"{\\\"logo\\\":\\\"");
        assertTrue(cfgStart > 0, "logo fileId missing in configuration: " + body);
        String fileId = body.substring(cfgStart + "\"configuration\":\"{\\\"logo\\\":\\\"".length(),
                body.indexOf("\\\"", cfgStart + "\"configuration\":\"{\\\"logo\\\":\\\"".length()));

        MvcResult picture = mockMvc.perform(auth(get("/api/v1/system/assistant/picture/" + fileId)))
                .andExpect(status().isOk()).andReturn();
        assertEquals("image/png", picture.getResponse().getContentType());
        assertTrue(picture.getResponse().getContentAsByteArray().length > 0);

        mockMvc.perform(auth(delete("/api/v1/system/assistant/" + assistantId)))
                .andExpect(status().isOk());
    }
}
