package com.xiongx9527.askdata.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验收 A2：只读/单语句/危险片段校验（AskdataSqlRetriever.checkSql）覆盖生成后、执行前的边界。
 */
class AskdataSqlRetrieverTest {

    @Test
    void validSelectPasses() {
        assertDoesNotThrow(() -> AskdataSqlRetriever.checkSql("SELECT id FROM t"));
    }

    @Test
    void withCtePasses() {
        assertDoesNotThrow(() -> AskdataSqlRetriever.checkSql("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void nonSelectRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AskdataSqlRetriever.checkSql("INSERT INTO t VALUES (1)"));
    }

    @Test
    void multiStatementRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AskdataSqlRetriever.checkSql("SELECT 1; DROP TABLE t"));
    }

    @Test
    void trailingSemicolonThenSecondStatementRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AskdataSqlRetriever.checkSql("SELECT 1; DELETE FROM t"));
    }

    @Test
    void forbiddenFragmentRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AskdataSqlRetriever.checkSql("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
        assertThrows(IllegalArgumentException.class,
                () -> AskdataSqlRetriever.checkSql("SELECT SLEEP(5) FROM t"));
    }
}
