package com.xiongx9527.askdata.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7（§2.8）回归：MCP {@code question_answer} 的 {@code returnImg} 语义。
 *
 * <p>未传 → 默认出图（对齐 SQLBot {@code McpQuestion.return_img} 缺省 true）；
 * 显式 {@code false} → 不渲染、响应不含 {@code imageUrl} 字段。</p>
 */
class McpQuestionReturnImgTest {

    @Test
    void absentReturnImgDefaultsToRendering() {
        assertTrue(AskdataMcpTools.shouldReturnImage(null), "未传 returnImg 应保持出图");
    }

    @Test
    void explicitFalseSuppressesImage() {
        assertFalse(AskdataMcpTools.shouldReturnImage(Boolean.FALSE), "returnImg=false 不应渲染图片");
    }

    @Test
    void explicitTrueRendersImage() {
        assertTrue(AskdataMcpTools.shouldReturnImage(Boolean.TRUE));
    }
}
