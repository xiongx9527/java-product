package com.xiongx9527.askdata.system.parameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.service.ParameterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A35：企业登录参数（{@code /system/parameter/login} 分组）可读可写，
 * 且 {@code login.default_login} 的取值决定登录页默认登录方式（前端 auto2Platform 读取）。
 */
class LoginParameterRoundTripTest {

    private ParameterService parameterService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:login_param_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        StaticMessageSource sms = new StaticMessageSource();
        sms.setUseCodeAsDefaultMessage(true);
        CurrentUserService users = org.mockito.Mockito.mock(CurrentUserService.class);
        parameterService = new ParameterService(new SysArgRepository(jdbc), users,
                new IdGenerator(), objectMapper, new MessageResolver(sms));
    }

    private Map<String, String> loginGroup() {
        Map<String, String> values = new HashMap<>();
        for (Map<String, Object> item : parameterService.group("login")) {
            values.put(String.valueOf(item.get("pkey")), String.valueOf(item.get("pval")));
        }
        return values;
    }

    @Test
    void loginGroupIsReadableWithSeededKeys() {
        Map<String, String> values = loginGroup();
        assertTrue(values.containsKey("login.default_login"), values.toString());
        assertTrue(values.containsKey("login.account_login"), values.toString());
    }

    @Test
    void defaultLoginIsWritableAndReadBack() throws Exception {
        // 登录页 auto2Platform：login.default_login = 2 → OIDC 为默认登录方式
        parameterService.save("[{\"pkey\":\"login.default_login\",\"pval\":\"2\"}]");
        Map<String, String> values = loginGroup();
        assertEquals("2", values.get("login.default_login"));

        parameterService.save("[{\"pkey\":\"login.default_login\",\"pval\":\"0\"},"
                + "{\"pkey\":\"login.account_login\",\"pval\":\"false\"}]");
        values = loginGroup();
        assertEquals("0", values.get("login.default_login"));
        assertEquals("false", values.get("login.account_login"));
    }

    @Test
    void writingLoginParamsDoesNotAffectChatGroup() throws Exception {
        List<Map<String, Object>> before = parameterService.group("chat");
        assertFalse(before.isEmpty());
        parameterService.save("[{\"pkey\":\"login.default_login\",\"pval\":\"4\"}]");
        List<Map<String, Object>> after = parameterService.group("chat");
        assertEquals(before.size(), after.size());
        assertNotNull(after.get(0).get("pkey"));
    }
}
