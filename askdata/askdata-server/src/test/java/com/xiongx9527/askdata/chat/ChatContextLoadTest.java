package com.xiongx9527.askdata.chat;

import org.springframework.test.context.DynamicPropertySource;

import org.springframework.test.context.DynamicPropertyRegistry;

import org.springframework.jdbc.core.JdbcTemplate;

import com.xiongx9527.askdata.support.MysqlTestSupport;

import com.xiongx9527.askdata.chat.controller.ChatController;
import com.xiongx9527.askdata.chat.repository.ChatRepository;
import com.xiongx9527.askdata.chat.repository.RecommendedQuestionRepository;
import com.xiongx9527.askdata.chat.service.ChatModelClient;
import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 Spring 上下文装载（A6 接线证据）：
 * 验证 chat 模块全部 Bean 可注入、且 {@code /chat/**} 端点在真实 MVC 路由表中注册
 * （避免与其它模块的控制器出现 {@code Ambiguous mapping} 导致应用启动失败）。
 *
 * <p>元数据库指向真实 MySQL 临时 schema（应用自身的 Flyway 负责迁移），
 * 连接不可达时整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChatContextLoadTest {

    private static JdbcTemplate mysql;

    @DynamicPropertySource
    static void mysqlDatasource(DynamicPropertyRegistry registry) {
        mysql = MysqlTestSupport.createEmptySchema();
        MysqlTestSupport.bindSpringDatasource(registry, mysql);
    }
    @Autowired
    private ChatController chatController;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatQuestionService chatQuestionService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private RecommendedQuestionRepository recommendedQuestionRepository;

    @Autowired
    private ChatModelClient chatModelClient;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void chatBeansAreWired() {
        assertNotNull(chatController);
        assertNotNull(chatService);
        assertNotNull(chatQuestionService);
        assertNotNull(chatRepository);
        assertNotNull(recommendedQuestionRepository);
        assertNotNull(chatModelClient);
    }

    @Test
    void chatEndpointsAreRegistered() {
        List<String> mappings = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (info.getPatternsCondition() != null) {
                mappings.addAll(info.getPatternsCondition().getPatterns());
            } else if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(mappings::add);
            }
        });
        assertTrue(mappings.contains("/api/v1/chat/list"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/start"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/assistant/start"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/question"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/{id}"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/{id}/with_data"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/rename"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/recent_questions/{ds}"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/data"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/data_live"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/predict_data"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/log"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/usage"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/excel/export/{chat_id}"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/record/{rid}/{action_type}"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/recommend_questions/{rid}"), mappings.toString());
        assertTrue(mappings.contains("/api/v1/chat/question/pager/{page}/{size}"), mappings.toString());
        // 占位桩已由 chat-core 接管（否则启动时报 Ambiguous mapping）
        assertFalse(mappings.stream().anyMatch(pattern ->
                pattern.startsWith("/api/v1/chat") && pattern.contains("list")
                        && !pattern.startsWith("/api/v1/chat/list")), mappings.toString());
    }
}
