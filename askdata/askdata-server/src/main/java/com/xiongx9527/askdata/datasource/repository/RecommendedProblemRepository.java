package com.xiongx9527.askdata.datasource.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.xiongx9527.askdata.common.util.IdGenerator;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 数据源推荐问题（ds_recommended_problem）JDBC 访问层。
 * 表结构移植自 SQLBot alembic（ds_recommended_problem；字段名不变）。
 */
@Repository
public class RecommendedProblemRepository {

    private static final String COLUMNS =
            "id, datasource_id, question, remark, sort, create_time, create_by";

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public RecommendedProblemRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    public List<Map<String, Object>> listByDsId(long dsId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ds_recommended_problem WHERE datasource_id = ? "
                + "ORDER BY sort, id", dsId);
    }

    public List<String> questionsByDsId(long dsId) {
        return jdbc.queryForList("SELECT question FROM ds_recommended_problem WHERE datasource_id = ? ORDER BY sort, id",
                String.class, dsId);
    }

    public void deleteByDsId(long dsId) {
        jdbc.update("DELETE FROM ds_recommended_problem WHERE datasource_id = ?", dsId);
    }

    public void insert(long datasourceId, String question, String remark, int sort, long createBy) {
        jdbc.update("INSERT INTO ds_recommended_problem (id, datasource_id, question, remark, sort, create_time, "
                        + "create_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                idGenerator.nextId(), datasourceId, question, remark, sort, new Timestamp(System.currentTimeMillis()),
                createBy);
    }
}
