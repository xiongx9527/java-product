package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A19：Hive 元数据细节 + SQLServer GBK 回正。
 *
 * <ul>
 *   <li>{@code SHOW TABLES}：Spark Thrift Server 返回 {@code (database, tableName, isTemporary)}，
 *       HiveServer2 只返回单列 {@code tab_name}；</li>
 *   <li>{@code DESCRIBE table}：跳过 {@code # Partition Information} / {@code # col_name} 段标题与
 *       遗留 Hive-JDBC 路径的表头行，分区列去重；</li>
 *   <li>SQLServer varchar 列被驱动以 latin-1 读出 GBK 字节时按 {@code latin-1 → GBK} 回正。</li>
 * </ul>
 */
class HiveSparkThriftMetadataTest {

    private static List<List<Object>> rows(Object[]... rows) {
        List<List<Object>> result = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            result.add(Arrays.asList(row));
        }
        return result;
    }

    @Test
    void sparkThriftTablesUseSecondColumnAndSkipHeaderRow() {
        List<List<Object>> raw = rows(
                new Object[] {"default", "tableName", false},   // 遗留 Hive-JDBC 表头行
                new Object[] {"default", "orders", false},
                new Object[] {"default", "users", false},
                new Object[] {"default", "orders", false});    // 重复行

        List<TableSchema> tables = JdbcEngine.parseHiveTableRows(raw, true);
        assertEquals(2, tables.size());
        assertEquals("orders", tables.get(0).getTableName());
        assertEquals("users", tables.get(1).getTableName());
    }

    @Test
    void hiveServer2TablesUseFirstColumn() {
        List<List<Object>> raw = rows(new Object[] {"orders"}, new Object[] {"users"});
        List<TableSchema> tables = JdbcEngine.parseHiveTableRows(raw, false);
        assertEquals(2, tables.size());
        assertEquals("orders", tables.get(0).getTableName());
    }

    @Test
    void describeSkipsSectionHeadingsAndDuplicatesPartitionColumns() {
        List<List<Object>> raw = rows(
                new Object[] {"col_name", "data_type", "comment"},           // 遗留表头行
                new Object[] {"id", "bigint", "主键"},
                new Object[] {"name", "string", "名称"},
                new Object[] {"# Partition Information", "", ""},           // 段标题
                new Object[] {"# col_name", "data_type", "comment"},        // 段标题
                new Object[] {"dt", "string", "分区日期"},
                new Object[] {"dt", "string", "分区日期"});                  // 分区列重复

        List<ColumnSchema> fields = JdbcEngine.parseHiveFieldRows(raw, true);
        assertEquals(3, fields.size());
        assertEquals("id", fields.get(0).getFieldName());
        assertEquals("bigint", fields.get(0).getFieldType());
        assertEquals("主键", fields.get(0).getFieldComment());
        assertEquals("name", fields.get(1).getFieldName());
        assertEquals("dt", fields.get(2).getFieldName());
    }

    @Test
    void describeWithoutSparkKeepsHeaderRowAsData() {
        // 非 Spark 路径：col_name 不作为表头行跳过（对齐 SQLBot 的 is_spark 判断）
        List<List<Object>> raw = rows(
                new Object[] {"col_name", "data_type", "comment"},
                new Object[] {"id", "bigint", ""});
        List<ColumnSchema> fields = JdbcEngine.parseHiveFieldRows(raw, false);
        assertEquals(2, fields.size());
    }

    @Test
    void sparkThriftDetectionFollowsEngineField() {
        DatasourceConf hive = new DatasourceConf();
        hive.setEngine("hive");
        assertFalse(JdbcEngine.isSparkThrift(hive));
        DatasourceConf spark = new DatasourceConf();
        spark.setEngine("spark");
        assertTrue(JdbcEngine.isSparkThrift(spark));
        DatasourceConf blank = new DatasourceConf();
        blank.setEngine("");
        assertFalse(JdbcEngine.isSparkThrift(blank));
    }

    @Test
    void gbkStringIsRestoredFromLatinOne() {
        // "中文" 的 GBK 字节被 latin-1 误读后的形态
        String garbled = new String(new byte[] {(byte) 0xd6, (byte) 0xd0, (byte) 0xce, (byte) 0xc4},
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("中文", JdbcEngine.convertGbkString(garbled));
        // 无法用 latin-1 表示的文本（已是正常中文）原样返回，不会被误转成乱码
        assertEquals("中文", JdbcEngine.convertGbkString("中文"));
        assertEquals("abc", JdbcEngine.convertGbkString("abc"));
        assertEquals(null, JdbcEngine.convertGbkString(null));
    }
}
