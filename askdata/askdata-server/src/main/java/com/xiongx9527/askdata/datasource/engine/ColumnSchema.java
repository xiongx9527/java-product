package com.xiongx9527.askdata.datasource.engine;

/**
 * 字段元信息（对齐 SQLBot {@code ColumnSchema}：{@code fieldName}/{@code fieldType}/{@code fieldComment}）。
 */
public class ColumnSchema {

    private final String fieldName;

    private final String fieldType;

    private final String fieldComment;

    public ColumnSchema(String fieldName, String fieldType, String fieldComment) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.fieldComment = fieldComment;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getFieldType() {
        return fieldType;
    }

    public String getFieldComment() {
        return fieldComment;
    }
}
