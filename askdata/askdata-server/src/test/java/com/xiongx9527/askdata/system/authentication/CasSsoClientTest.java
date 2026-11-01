package com.xiongx9527.askdata.system.authentication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CAS 校验响应解析：成功取 {@code <cas:user>}，失败把 {@code authenticationFailure} 的
 * code/原因透出来（否则用户只看到“没取到用户”，查不出是票据过期还是被重放）。
 */
class CasSsoClientTest {

    @Test
    void readsUserFromSuccessResponse() {
        String xml = "<cas:serviceResponse xmlns:cas=\"http://www.yale.edu/tp/cas\">"
                + "<cas:authenticationSuccess><cas:user> sunsx </cas:user></cas:authenticationSuccess>"
                + "</cas:serviceResponse>";
        assertEquals("sunsx", CasSsoClient.userFrom(xml));
        assertNull(CasSsoClient.failureFrom(xml));
    }

    @Test
    void surfacesFailureCodeAndMessage() {
        String xml = "<cas:serviceResponse xmlns:cas=\"http://www.yale.edu/tp/cas\">"
                + "<cas:authenticationFailure code=\"INVALID_TICKET\">Ticket ST-3-fake not recognized"
                + "</cas:authenticationFailure></cas:serviceResponse>";
        assertNull(CasSsoClient.userFrom(xml));
        assertEquals("INVALID_TICKET Ticket ST-3-fake not recognized", CasSsoClient.failureFrom(xml));
    }

    @Test
    void unknownResponseHasNoUserAndNoFailure() {
        assertNull(CasSsoClient.userFrom("<html>502</html>"));
        assertNull(CasSsoClient.failureFrom("<html>502</html>"));
    }

    /**
     * MaxKey 应用没配适配器时返回 code="null" 内容 "null"：不能把 null 当成原因抛给用户。
     */
    @Test
    void blankFailureCodeIsReportedAsMissingDetail() {
        String xml = "<cas:serviceResponse xmlns:cas=\"http://www.yale.edu/tp/cas\">"
                + "<cas:authenticationFailure code=\"null\">null</cas:authenticationFailure>"
                + "</cas:serviceResponse>";
        assertEquals("IdP returned no failure detail (check the CAS application registration, e.g. adapter)",
                CasSsoClient.failureFrom(xml));
    }

    /**
     * 属性节点带 {@code cas:} 前缀，中文值是 {@code base64:} 编码：都要还原成 username/displayName 这样的键和原文，
     * 否则按 username 取值取不到，登录报 “missing account”。
     */
    @Test
    void parsesNamespacedAttributesAndDecodesBase64Values() {
        String xml = "<cas:serviceResponse xmlns:cas=\"http://www.yale.edu/tp/cas\">"
                + "<cas:authenticationSuccess><cas:user>admin</cas:user><cas:attributes>"
                + "<cas:birthday>null</cas:birthday>"
                + "<cas:displayName>base64:57O757uf566h55CG5ZGY</cas:displayName>"
                + "<cas:uid>1</cas:uid>"
                + "<cas:username>admin</cas:username>"
                + "</cas:attributes></cas:authenticationSuccess></cas:serviceResponse>";

        Map<String, Object> attributes = new CasSsoClient(new ObjectMapper()).parseAttributes(xml);

        assertEquals("admin", attributes.get("username"));
        assertEquals("1", attributes.get("uid"));
        assertEquals("系统管理员", attributes.get("displayName"));
        // 字面量 null 视为没有该属性
        assertFalse(attributes.containsKey("birthday"));
        // 前缀已去掉，不应残留 cas: 键
        assertFalse(attributes.containsKey("cas:username"));
    }
}
