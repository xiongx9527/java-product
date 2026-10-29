package com.xiongx9527.askdata.system.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 端到端 SSO 测试（SAML2）。
 *
 * 与 AuthenticationSsoLoginTest（service 层、SSO 客户端被 mock）不同，本测试：
 *  - 启动完整 Spring Boot 应用（真实 Bean + 真实 embedded-postgres，不 mock 任何业务服务）；
 *  - 向真实控制器端点 POST /api/v1/system/authentication/sso/5 发真实 HTTP 请求；
 *  - 请求体 SAMLResponse 由 IdpTestCrypto 生成带真实 XML-DSig 签名的断言（真实 RSA 密钥对签名，非 mock）；
 *  - 走真实 Saml2SsoClient.parseResponse + 真实 SamlSignatureVerifier 验签 + 真实属性抽取 +
 *    真实 resolveLocalUser 自动建本地账号并落 sys_user_platform 绑定；
 *  - 断言：HTTP 200、返回账号信息正确、本地账号与绑定真实写入数据库。
 *
 * 即：前后端联通的真实链路（前端消费的同一套 REST 契约），且对 SSO 协议客户端不做任何 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamlSsoRealHttpE2ETest {

    private static final int TYPE_SAML2 = 5;
    private static final long AUTH_ID = 50005L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final ThreadLocal<KeyPair> KEY_PAIR_HOLDER = new ThreadLocal<>();

    @BeforeEach
    void seedSamlAuthSource() throws Exception {
        jdbc.update("DELETE FROM sys_authentication WHERE type = ?", TYPE_SAML2);
        jdbc.update("DELETE FROM sys_user WHERE account = ?", "alice");
        jdbc.update("DELETE FROM sys_user_platform WHERE platform_uid = ?", "alice");

        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        KEY_PAIR_HOLDER.set(pair);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("certificate", IdpTestCrypto.publicKeyBase64(pair));
        config.put("wantAssertionsSigned", "false");
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            configJson = config.toString();
        }
        jdbc.update(
                "INSERT INTO sys_authentication (id, name, type, config, enable, valid, create_time) "
                        + "VALUES (?, ?, ?, ?::json, ?, ?, ?)",
                AUTH_ID, "real-saml-idp", TYPE_SAML2, configJson, true, true,
                System.currentTimeMillis());
    }

    @Test
    void samlCallbackOverHttpVerifiesSignatureAndCreatesLocalUser() throws Exception {
        KeyPair pair = KEY_PAIR_HOLDER.get();
        String signed = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "alice", "alice@example.com");
        String base64 = IdpTestCrypto.base64(signed);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("SAMLResponse", base64);
        String bodyJson = objectMapper.writeValueAsString(body);

        MvcResult result = mockMvc.perform(
                        post("/api/v1/system/authentication/sso/" + TYPE_SAML2)
                                .contentType("application/json")
                                .content(bodyJson))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("alice"),
                "response should carry alice account info, actual: " + response);

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE account = ?", Integer.class, "alice");
        assertEquals(Integer.valueOf(1), userCount, "SAML login should auto-create local user alice");

        Integer bindingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_platform WHERE platform_uid = ? AND origin = ?",
                Integer.class, "alice", TYPE_SAML2);
        assertEquals(Integer.valueOf(1), bindingCount, "should write sys_user_platform binding");
    }
}
