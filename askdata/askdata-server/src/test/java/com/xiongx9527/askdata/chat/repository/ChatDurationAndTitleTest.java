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
        return updateArgs(repo -> repo.finishRecord(9L, runTimeMs, 12L));
    }

    /**
     * 执行一次写操作并返回 {@code jdbc.update} 的 varargs（不含 SQL）。
     *
     * <p>Mockito 4 对 varargs 是逐元素捕获，{@code ArgumentCaptor<Object[]>} 只能取到最后一个
     * 元素（会抛 ClassCastException）；这里用默认 Answer 读取原始调用参数，再按 Spring 5.3
     * 的行为（{@code getArguments()} 已展开 varargs）去掉首个 SQL 参数。</p>
     */
    private static Object[] updateArgs(java.util.function.Consumer<ChatRepository> call) {
        final Object[][] captured = new Object[1][];
        JdbcTemplate template = mock(JdbcTemplate.class, invocation -> {
            if ("update".equals(invocation.getMethod().getName())) {
                Object[] raw = invocation.getArguments();
                captured[0] = java.util.Arrays.copyOfRange(raw, 1, raw.length);
            }
            return 1;
        });
        ChatRepository repo = new ChatRepository(template, mock(IdGenerator.class), new ObjectMapper());
        call.accept(repo);
        return captured[0];
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
        Object[] args = updateArgs(repo -> repo.renameChat(7L, "各省销售额"));
        assertEquals("各省销售额", args[0]);
        assertEquals(7L, args[2]);
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
