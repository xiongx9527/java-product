package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * 对齐 SQLBot {@code test_cwe89_escape_fix.py} 的转义语义等价单测：
 * 引号加倍、反斜杠加倍、逻辑运算符白名单（仅 AND/OR）。
 */
class SqlEscapeTest {

    // 反斜杠（用码点 92 构造，避免源码字面量反斜杠）
    private static final String BS = Character.toString((char) 92);

    // ---- 安全值原样通过 ----

    @Test
    void normalStringUnchanged() {
        assertEquals("hello", SqlEscape.escapeValue("hello"));
    }

    @Test
    void emptyStringUnchanged() {
        assertEquals("", SqlEscape.escapeValue(""));
    }

    @Test
    void numericStringUnchanged() {
        assertEquals("12345", SqlEscape.escapeValue("12345"));
    }

    @Test
    void noneReturnsNull() {
        assertNull(SqlEscape.escapeValue(null));
    }

    @Test
    void unicodeUnchanged() {
        String s = "\u65e5\u672c\u8a9e";
        assertEquals(s, SqlEscape.escapeValue(s));
    }

    // ---- 注入载荷被中和 ----

    @Test
    void singleQuoteDoubled() {
        // ' OR 1=1 --  ->  '' OR 1=1 --
        assertEquals("'' OR 1=1 --", SqlEscape.escapeValue("' OR 1=1 --"));
    }

    @Test
    void doubleQuotesDoubled() {
        assertEquals("it''''s", SqlEscape.escapeValue("it''s"));
    }

    @Test
    void apostropheName() {
        assertEquals("O''Malley", SqlEscape.escapeValue("O'Malley"));
    }

    @Test
    void backslashDoubled() {
        // 单个反斜杠 -> 两个反斜杠
        String input = "a" + BS + "b";
        String expected = "a" + BS + BS + "b";
        assertEquals(expected, SqlEscape.escapeValue(input));
    }

    @Test
    void combinedQuoteAndBackslash() {
        // test' ; DROP -> test'' ; DROP  (反斜杠加倍 + 引号加倍)
        String input = "test" + BS + "'; DROP TABLE users; --";
        String expected = "test" + BS + BS + "''; DROP TABLE users; --";
        assertEquals(expected, SqlEscape.escapeValue(input));
    }

    @Test
    void unionInjectionNeutralized() {
        String payload = "' UNION SELECT password FROM users --";
        assertEquals("'' UNION SELECT password FROM users --", SqlEscape.escapeValue(payload));
    }

    @Test
    void stackedQueryInjectionNeutralized() {
        assertEquals("''; DELETE FROM users; --", SqlEscape.escapeValue("'; DELETE FROM users; --"));
    }

    @Test
    void numericCoercedToString() {
        assertEquals("42", SqlEscape.escapeValue(42));
    }

    @Test
    void backslashQuoteBypassAttempt() {
        // 反斜杠 + 引号 -> 反斜杠加倍、引号加倍
        String input = BS + "'";
        String result = SqlEscape.escapeValue(input);
        assertTrue(result.contains("''"));
        assertTrue(result.contains(BS));
    }

    // ---- 逻辑运算符白名单 ----

    @Test
    void andAccepted() {
        assertTrue(SqlEscape.isValidLogicOp("AND"));
    }

    @Test
    void orAccepted() {
        assertTrue(SqlEscape.isValidLogicOp("OR"));
    }

    @Test
    void injectionViaLogicRejected() {
        assertFalse(SqlEscape.isValidLogicOp("AND 1=1) UNION SELECT"));
    }

    @Test
    void semicolonRejected() {
        assertFalse(SqlEscape.isValidLogicOp(";"));
    }

    @Test
    void dropRejected() {
        assertFalse(SqlEscape.isValidLogicOp("DROP"));
    }

    @Test
    void emptyRejected() {
        assertFalse(SqlEscape.isValidLogicOp(""));
    }

    @Test
    void onlyTwoOperators() {
        assertTrue(SqlEscape.isValidLogicOp("and"));
        assertTrue(SqlEscape.isValidLogicOp("Or"));
        assertFalse(SqlEscape.isValidLogicOp("NOT"));
    }
}
