package com.xiongx9527.askdata.datasource.engine;

import java.util.List;

/**
 * 表元信息（对齐 SQLBot {@code TableSchemaResponse}：{@code tableName}/{@code tableComment}）。
 */
public class TableSchema {

    private final String tableName;

    private final String tableComment;

    public TableSchema(String tableName, String tableComment) {
        this.tableName = tableName;
        this.tableComment = tableComment;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTableComment() {
        return tableComment;
    }
}
