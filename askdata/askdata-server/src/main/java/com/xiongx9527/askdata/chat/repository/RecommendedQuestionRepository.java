package com.xiongx9527.askdata.chat.repository;

import com.xiongx9527.askdata.common.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 推荐问题表（recommended_questions）JDBC 访问层（对齐 SQLBot RecommendedQuestion）。
 */
@Repository
public class RecommendedQuestionRepository {

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public RecommendedQuestionRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** 批量保存某会话的推荐问题（覆盖写：先删后插，sort 即顺序）。 */
    public void replaceByChat(long chatId, List<String> questions) {
        jdbc.update("DELETE FROM recommended_questions WHERE chat_id = ?", chatId);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (int i = 0; i < questions.size(); i++) {
            jdbc.update("INSERT INTO recommended_questions (id, chat_id, question, sort, create_time) VALUES (?, ?, ?, ?, ?)",
                    idGenerator.nextId(), chatId, questions.get(i), i, now);
        }
    }

    public List<Map<String, Object>> listByChat(long chatId) {
        return jdbc.queryForList("SELECT id, chat_id, question, sort FROM recommended_questions "
                + "WHERE chat_id = ? ORDER BY sort", chatId);
    }

    /**
     * 最近问题（对齐 SQLBot recent_questions）：返回该数据源下当前用户
     * （{@code create_by + oid} 双过滤）最近的若干条提问（去重、按时间倒序）。
     *
     * <p>A1：不能只按工作空间过滤，否则同工作空间的其他用户会读到本用户的提问历史；
     * 排序增加 id 作为稳定兜底，保证同一时间戳下返回顺序可重复。降级语义：历史为空时
     * 返回空列表，前端按空态处理。</p>
     */
    public List<String> recentByDatasource(long datasourceId, long createBy, long oid, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT question FROM chat_record WHERE datasource = ? AND create_by = ? AND oid = ? "
                        + "AND question IS NOT NULL ORDER BY create_time DESC, id DESC",
                datasourceId, createBy, oid);
        List<String> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object q = row.get("question");
            if (q == null) {
                continue;
            }
            String question = String.valueOf(q);
            if (question.trim().isEmpty() || !seen.add(question)) {
                continue;
            }
            result.add(question);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }
}
