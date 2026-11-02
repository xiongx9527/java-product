package com.xiongx9527.askdata.config;

import com.xiongx9527.askdata.support.MysqlTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据库版本守卫：主库只支持 MySQL（含衍生版本）与 PostgreSQL。
 *
 * <p>版本分支用纯函数覆盖（无法起一个 5.7 实例来「真实」验证拒绝），
 * 「真实 MySQL 8 通过」这一条打真库。</p>
 */
class MetadataDatabaseGuardTest {

    @Test
    void rejectsMysql57WithActionableMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("MySQL", "jdbc:mysql://127.0.0.1:3306/askdata",
                        5, 7));

        assertTrue(error.getMessage().contains("MySQL 8.0+"), error.getMessage());
        assertTrue(error.getMessage().contains("5.7"), "消息应点明检测到的版本：" + error.getMessage());
    }

    @Test
    void acceptsMysql8() {
        assertDoesNotThrow(() -> MetadataDatabaseGuard.verifyVersion(
                "MySQL", "jdbc:mysql://127.0.0.1:3307/askdata", 8, 4));
    }

    @Test
    void acceptsMariadbAsMysqlDerivative() {
        assertDoesNotThrow(() -> MetadataDatabaseGuard.verifyVersion(
                "MariaDB", "jdbc:mariadb://127.0.0.1:3306/askdata", 10, 11));
    }

    @Test
    void rejectsMariadbBelowTen() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("MariaDB", "jdbc:mariadb://127.0.0.1:3306/askdata",
                        5, 5));
        assertTrue(error.getMessage().contains("MariaDB"), error.getMessage());
    }

    @Test
    void acceptsPostgresql() {
        assertDoesNotThrow(() -> MetadataDatabaseGuard.verifyVersion(
                "PostgreSQL", "jdbc:postgresql://127.0.0.1:5432/askdata", 16, 2));
    }

    @Test
    void rejectsPostgresqlBelowTen() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("PostgreSQL",
                        "jdbc:postgresql://127.0.0.1:5432/askdata", 9, 6));
        assertTrue(error.getMessage().contains("PostgreSQL 10+"), error.getMessage());
    }

    @Test
    void rejectsH2EvenInMemory() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("H2", "jdbc:h2:mem:askdata-test;MODE=MySQL", 2, 1));
        assertTrue(error.getMessage().contains("不再支持 H2"), error.getMessage());
    }

    @Test
    void rejectsH2FileDatabase() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("H2", "jdbc:h2:file:./data/askdata", 2, 1));
        assertTrue(error.getMessage().contains("不再支持 H2"), error.getMessage());
    }

    @Test
    void rejectsOtherProducts() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MetadataDatabaseGuard.verifyVersion("Oracle", "jdbc:oracle:thin:@127.0.0.1:1521:orcl",
                        19, 0));
        assertTrue(error.getMessage().contains("仅支持 MySQL"), error.getMessage());
    }

    @Test
    void realMysql8PassesVerification() {
        MysqlTestSupport.requireAvailable();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MysqlTestSupport.baseUrl() + "/information_schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                MysqlTestSupport.user(), MysqlTestSupport.password());

        assertDoesNotThrow(() -> MetadataDatabaseGuard.verify(dataSource));
    }
}
