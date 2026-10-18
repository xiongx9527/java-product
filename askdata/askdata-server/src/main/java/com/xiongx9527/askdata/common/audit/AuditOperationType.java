package com.xiongx9527.askdata.common.audit;

/**
 * 审计操作类型（对齐 SQLBot {@code common/audit/models/log_model.py} OperationType）。
 */
public enum AuditOperationType {
    CREATE("create"),
    DELETE("delete"),
    UPDATE("update"),
    RESET_PWD("reset_pwd"),
    UPDATE_PWD("update_pwd"),
    UPDATE_STATUS("update_status"),
    UPDATE_TABLE_RELATION("update_table_relation"),
    EDIT("edit"),
    LOGIN("login"),
    VIEW("view"),
    EXPORT("export"),
    IMPORT("import"),
    ADD("add"),
    CREATE_OR_UPDATE("create_or_update"),
    ANALYSIS("analysis"),
    PREDICTION("prediction");

    private final String code;

    AuditOperationType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
