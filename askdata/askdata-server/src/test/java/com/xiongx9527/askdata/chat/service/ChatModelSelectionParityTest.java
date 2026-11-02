package com.xiongx9527.askdata.chat.service;

import com.xiongx9527.askdata.support.MysqlTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.system.repository.AiModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 SQLBot 的模型选择语义：
 * <ul>
 *   <li>工作空间内多个对话模型时，{@code custom_model} 可随时切换（限本空间可见）；</li>
 *   <li>未指定时用全局默认；无默认才退到本空间可用模型；</li>
 *   <li>未绑定且非默认的模型不得被跨空间取用；</li>
 *   <li>问答记录落本次使用的模型 id 与名称。</li>
 * </ul>
 */
class ChatModelSelectionParityTest {

    private static final long OID = 11L;

    private static final long OTHER_OID = 22L;

    private JdbcTemplate jdbc;

    private AiModelRepository repository;

    private ChatModelRouter router;

    private ChatRepository chatRepository;

    private final IdGenerator ids = new IdGenerator();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MysqlTestSupport.createSchema();
        repository = new AiModelRepository(jdbc, ids);
        router = new ChatModelRouter(repository, new ObjectMapper());
        chatRepository = new ChatRepository(jdbc, ids, new ObjectMapper());
    }

    private long model(String name, boolean defaultModel) {
        long id = ids.nextId();
        repository.insert(id, "", "https://api.example.com", 1, 0, name, 0, name,
                defaultModel, "[]", 1, System.currentTimeMillis(), true, true, 1, null);
        return id;
    }

    private void bind(long modelId, long oid) {
        jdbc.update("INSERT INTO ai_model_workspace_mapping (id, ai_model_id, workspace_id) VALUES (?, ?, ?)",
                ids.nextId(), modelId, oid);
    }

    @Test
    void globalDefaultWinsWhenWorkspaceHasSeveralModels() {
        long workspaceModel = model("ws-model", false);
        bind(workspaceModel, OID);
        long globalDefault = model("global-default", true);

        assertEquals(globalDefault, router.defaultChatModel(OID).get().id);
    }

    @Test
    void fallsBackToWorkspaceModelWhenNoGlobalDefault() {
        long workspaceModel = model("ws-model", false);
        bind(workspaceModel, OID);
        long otherModel = model("other-model", false);
        bind(otherModel, OTHER_OID);

        assertEquals(workspaceModel, router.defaultChatModel(OID).get().id,
                "无全局默认时使用本空间的可用模型，不得跨空间取用");
    }

    @Test
    void multipleWorkspaceModelsWithoutDefaultAreAmbiguous() {
        bind(model("model-a", false), OID);
        bind(model("model-b", false), OID);

        assertFalse(router.defaultChatModel(OID).isPresent(),
                "多个可用模型且无默认时不得替用户猜一个");
        assertEquals(2, router.workspaceModelCount(OID));
    }

    @Test
    void customModelIsSelectableWhenBoundToWorkspace() {
        long first = model("model-a", false);
        long second = model("model-b", false);
        bind(first, OID);
        bind(second, OID);

        assertEquals(second, router.chatModelById(second, OID).get().id);
        assertEquals(first, router.chatModelById(first, OID).get().id);
    }

    @Test
    void customModelFromAnotherWorkspaceIsRejected() {
        long other = model("other-model", false);
        bind(other, OTHER_OID);

        assertFalse(router.chatModelById(other, OID).isPresent(), "未授权的模型不得被取用");
        assertTrue(router.chatModelById(other, OTHER_OID).isPresent());
    }

    @Test
    void defaultModelIsVisibleWithoutExplicitBinding() {
        long globalDefault = model("global-default", true);
        assertTrue(router.chatModelById(globalDefault, OID).isPresent(),
                "默认模型对任意空间可见（对齐 SQLBot with_default）");
    }

    @Test
    void recordStoresModelUsedForThisQuestion() {
        long chatId = chatRepository.insertChat(1L, OID, "标题", null, null, null, 0, null);
        long modelId = model("model-a", false);
        long recordId = chatRepository.insertRecord(chatId, "问题", 1L, OID, null, false, null,
                modelId, "model-a");

        Map<String, Object> record = chatRepository.findRecord(recordId).orElse(null);
        assertEquals(modelId, ((Number) record.get("ai_modal_id")).longValue());
        assertEquals("model-a", record.get("ai_modal_name"));
    }
}
