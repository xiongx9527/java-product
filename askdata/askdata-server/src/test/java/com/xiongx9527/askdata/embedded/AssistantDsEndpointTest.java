package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.common.util.Lists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;

/**
 * GET /system/assistant/ds acceptance test (A3, H2 in-memory).
 * Aligns SQLBot assistant.py /ds; returns current assistant datasources
 * (id/name/description/type/type_name/num).
 *
 * datasource scope is decided by assistant configuration(JSON) oid;
 * type=0 constrained by online/public_list, type=1 returns all under oid.
 * CurrentUserService is MockBean to bypass Sa-Token; the real controller
 * method is invoked directly and the MVC route is asserted registered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:assistant_ds;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class AssistantDsEndpointTest {

    @Autowired
    private AssistantController assistantController;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUp() {
        doNothing().when(currentUserService).requireWsAdmin();
    }

    @Test
    void dsEndpointIsRegistered() {
        List<String> mappings = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (info.getPatternsCondition() != null) {
                mappings.addAll(info.getPatternsCondition().getPatterns());
            } else if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(mappings::add);
            }
        });
        assertTrue(mappings.contains("/api/v1/system/assistant/ds"), mappings.toString());
    }

    @Test
    void type0OfflinePublicListFiltersDatasources() throws Exception {
        long oid = 1001L;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("oid", oid);
        config.put("online", false);
        config.put("public_list", Lists.of(201L, 202L));
        long assistantId = insertAssistant("assistantA", 0, oid, objectMapper.writeValueAsString(config));

        insertDatasource(201L, "orders", "order ds", "mysql", "MySQL", "1/3", oid);
        insertDatasource(202L, "users", "user ds", "mysql", "MySQL", "2/5", oid);
        insertDatasource(203L, "logs", "log ds", "mysql", "MySQL", "0/0", oid);

        List<Map<String, Object>> result = assistantController.datasources(assistantId).getData();
        assertEquals(2, result.size(), result.toString());

        Map<String, Object> byId = new LinkedHashMap<>();
        for (Map<String, Object> ds : result) {
            byId.put(String.valueOf(ds.get("id")), ds);
        }
        assertTrue(byId.containsKey("201"));
        assertTrue(byId.containsKey("202"));
        assertFalse(byId.containsKey("203"));

        Map<String, Object> ds201 = (Map<String, Object>) byId.get("201");
        assertEquals("orders", ds201.get("name"));
        assertEquals("order ds", ds201.get("description"));
        assertEquals("mysql", ds201.get("type"));
        assertEquals("MySQL", ds201.get("type_name"));
        assertEquals("1/3", ds201.get("num"));
    }

    @Test
    void type0OfflineEmptyPublicListReturnsEmpty() throws Exception {
        long oid = 1002L;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("oid", oid);
        config.put("online", false);
        config.put("public_list", new ArrayList<Long>());
        long assistantId = insertAssistant("assistantB", 0, oid, objectMapper.writeValueAsString(config));

        insertDatasource(211L, "orders", "x", "mysql", "MySQL", "1/3", oid);

        List<Map<String, Object>> result = assistantController.datasources(assistantId).getData();
        assertNotNull(result);
        assertEquals(0, result.size(), result.toString());
    }

    @Test
    void type1ReturnsAllDatasourcesUnderOid() throws Exception {
        long oid = 2001L;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("oid", oid);
        long assistantId = insertAssistant("appC", 1, oid, objectMapper.writeValueAsString(config));

        insertDatasource(301L, "primary", "x", "mysql", "MySQL", "3/3", oid);
        insertDatasource(302L, "replica", "x", "h2", "H2", "1/1", oid);
        insertDatasource(399L, "other", "x", "mysql", "MySQL", "0/0", 9999L);

        List<Map<String, Object>> result = assistantController.datasources(assistantId).getData();
        assertEquals(2, result.size(), result.toString());
        assertTrue(result.stream().allMatch(ds -> {
            long id = Rows.lng(ds, "id", 0L);
            return id == 301L || id == 302L;
        }));
        assertFalse(result.stream().anyMatch(ds -> Rows.lng(ds, "id", 0L) == 399L));
    }

    private long insertAssistant(String name, int type, long oid, String configuration) {
        long id = System.nanoTime();
        jdbc.update("INSERT INTO sys_assistant (id, name, type, domain, description, configuration, "
                        + "create_time, app_id, app_secret, oid, enable_custom_model, custom_model) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, type, "example.com", "desc", configuration, 0L, "app", "secret", oid, false, null);
        return id;
    }

    private void insertDatasource(long id, String name, String description, String type, String typeName,
                                  String num, long oid) {
        jdbc.update("INSERT INTO core_datasource (id, name, description, type, type_name, configuration, "
                        + "create_time, create_by, status, num, oid, table_relation, embedding, recommended_config) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, description, type, typeName, "{}", null, oid, "Success", num, oid, null, null, 1);
    }
}
