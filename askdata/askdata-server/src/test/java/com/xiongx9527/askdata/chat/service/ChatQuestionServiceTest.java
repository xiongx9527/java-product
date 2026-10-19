package com.xiongx9527.askdata.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatQuestionServiceTest {

    @Test
    void extractsChineseTableName() {
        List<String> tables = ChatQuestionService.usedTables(
                "SELECT * FROM EXCEL_\u9500\u552e\u6570\u636e_319C13A236 WHERE id = 1");
        assertEquals(Arrays.asList("excel_\u9500\u552e\u6570\u636e_319c13a236"), tables);
    }

    @Test
    void asciiTableNameUnchanged() {
        List<String> tables = ChatQuestionService.usedTables(
                "SELECT * FROM excel_sales_23c13ee62a JOIN other ON 1=1");
        assertEquals(Arrays.asList("excel_sales_23c13ee62a", "other"), tables);
    }

    @Test
    void dropsPunctuationAroundName() {
        List<String> tables = ChatQuestionService.usedTables(
                "FROM (weird_name);");
        assertEquals(Arrays.asList("weird_name"), tables);
    }
}
