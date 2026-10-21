package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应信封兜底单测（对齐 SQLBot {@code common/core/response_middleware.py}）。
 */
class ResponseEnvelopeAdviceTest {

    @Test
    void alreadyWrappedResponseIsNotWrappedAgain() {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("code", 0);
        wrapped.put("data", "payload");
        wrapped.put("msg", null);
        assertTrue(ResponseEnvelopeAdvice.isAlreadyWrapped(wrapped));
    }

    @Test
    void plainMapIsNotConsideredWrapped() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("id", 1);
        assertFalse(ResponseEnvelopeAdvice.isAlreadyWrapped(plain));
    }

    @Test
    void partialEnvelopeIsNotConsideredWrapped() {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("code", 0);
        partial.put("data", "payload");
        assertFalse(ResponseEnvelopeAdvice.isAlreadyWrapped(partial));
    }

    @Test
    void nonMapBodyIsNotConsideredWrapped() {
        assertFalse(ResponseEnvelopeAdvice.isAlreadyWrapped("text"));
        assertFalse(ResponseEnvelopeAdvice.isAlreadyWrapped(42));
        assertFalse(ResponseEnvelopeAdvice.isAlreadyWrapped(null));
    }
}
