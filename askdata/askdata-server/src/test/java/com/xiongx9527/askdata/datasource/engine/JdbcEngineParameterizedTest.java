package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiongx9527.askdata.datasource.DatasourceException;

/**
 * A5 执行安全单测：
 * <ul>
 *   <li>{@code execSql} 走参数化执行（PreparedStatement），不使用 {@code Statement}；</li>
 *   <li>非法 JDBC 连接参数被拦截（{@code local_infile}/{@code allowLoadLocalInfile} 等）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JdbcEngineParameterizedTest {

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate appJdbc;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private ConnectionPoolManager poolManager;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private ResultSetMetaData resultSetMetaData;

    @InjectMocks
    private JdbcEngine engine;

    @Test
    void execSqlUsesPreparedStatementNotStatement() throws Exception {
        // 借出的连接由 mock 提供，避免真实建连；prepareStatement 返回 mock
        when(poolManager.borrow(anyString(), any(), any())).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(0);

        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase("jdbc:h2:mem:param_test");
        Map<String, Object> result = engine.execSql(DatasourceType.H2, conf, "SELECT 1", 1L);

        verify(connection).prepareStatement(anyString());
        verify(connection, never()).createStatement();
        assertEquals(List.of(), result.get("fields"));
    }

    @Test
    void illegalJdbcParamLocalInfileBlocked() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DatasourceType.checkParams("local_infile=true", DatasourceType.MYSQL.illegalParams()));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("local_infile"));
    }

    @Test
    void illegalJdbcParamAllowLoadLocalInfileBlocked() {
        // 现代 Connector/J 同义参数 allowLoadLocalInfile 在 buildJdbcUrl 一并拦截（A5）
        DatasourceConf conf = new DatasourceConf();
        conf.setExtraJdbc("allowLoadLocalInfile=true");
        assertThrows(DatasourceException.class,
                () -> engine.buildJdbcUrl(DatasourceType.MYSQL, conf));
    }

    @Test
    void safeJdbcParamsAllowed() {
        // 不应抛出
        DatasourceType.checkParams("useUnicode=true&characterEncoding=utf8",
                DatasourceType.MYSQL.illegalParams());
    }

    @Test
    void mysqlIllegalParamsContainsLocalInfile() {
        // 对齐 SQLBot：MYSQL.illegalParams 精确为 [local_infile]
        assertEquals(List.of("local_infile"), DatasourceType.MYSQL.illegalParams());
    }
}
