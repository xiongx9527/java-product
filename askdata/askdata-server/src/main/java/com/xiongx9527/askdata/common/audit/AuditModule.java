package com.xiongx9527.askdata.common.audit;

/**
 * 审计操作模块（对齐 SQLBot {@code common/audit/models/log_model.py} OperationModules）。
 */
public enum AuditModule {
    CHAT("chat"),
    DATASOURCE("datasource"),
    DASHBOARD("dashboard"),
    MEMBER("member"),
    PERMISSION("permission"),
    RULES("rules"),
    TERMINOLOGY("terminology"),
    DATA_TRAINING("data_training"),
    PROMPT_WORDS("prompt_words"),
    USER("user"),
    WORKSPACE("workspace"),
    AI_MODEL("ai_model"),
    APPLICATION("application"),
    THEME("theme"),
    PARAMS_SETTING("params_setting"),
    API_KEY("api_key"),
    LOG_SETTING("log_setting"),
    SETTING("setting"),
    SYSTEM_MANAGEMENT("system_management"),
    OPT_LOG("opt_log");

    private final String code;

    AuditModule(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
