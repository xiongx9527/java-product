package com.xiongx9527.askdata.chat.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A14（耗时展示）+ A11（会话标题）的仓储层契约。
 *
 * <ul>
 *   <li>{@code duration} 保持秒单位并向上取整：1ms~1s 区间落库为 1，避免前端显示 "0 s"；</li>
 *   <li>{@code renameChat} 只覆盖 brief，不做追加/截断拼接，保证首问生成的标题稳定。</li>
 * </ul>
 */
class ChatDurationAndTitleTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private final ChatRepository repository = new ChatRepository(jdbc, mock(IdGenerator.class), new ObjectMapper());

    /** 调用 finishRecord 并返回 jdbc.update 的入参（顺序：finish_time, run_time, duration, tokens, id）。 */
    private static Object[] finishArgs(long runTimeMs) {
        JdbcTemplate template = mock(JdbcTemplate.class);
        ChatRepository repo = new ChatRepository(template, mock(IdGenerator.class), new ObjectMapper());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        repo.finishRecord(9L, runTimeMs, 12L);
        verify(template).update(anyString(), args.capture());
        return args.getValue();
    }

    @Test
    void subSecondDurationRoundsUpToOneSecond() {
        // 亚秒级（120ms）问数：duration 必须为 1，前端不再显示 "0 s"
        assertEquals(1L, finishArgs(120L)[2]);
    }

    @Test
    void exactSecondStaysOne() {
        assertEquals(1L, finishArgs(1000L)[2]);
    }

    @Test
    void millisecondRemainderRoundsUp() {
        assertEquals(2L, finishArgs(1500L)[2]);
        assertEquals(2L, finishArgs(1001L)[2]);
        assertEquals(3L, finishArgs(2999L)[2]);
    }

    @Test
    void zeroRunTimeKeepsZero() {
        // 未记录耗时（0）不伪造 1 秒
        assertEquals(0L, finishArgs(0L)[2]);
    }

    @Test
    void renameChatOverwritesBriefInsteadOfAppending() {
        repository.renameChat(7L, "各省销售额");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals("各省销售额", args.getValue()[0]);
        assertEquals(7L, args.getValue()[2]);
    }

    @Test
    void renameChatIgnoresBlankBrief() {
        repository.renameChat(7L, "   ");
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void finishRecordWritesFinishAndTokens() {
        Object[] args = finishArgs(2600L);
        assertTrue(args[1] instanceof Long);
        assertEquals(3L, args[2]);
        assertEquals(12L, args[3]);
        assertEquals(9L, args[4]);
    }
}
