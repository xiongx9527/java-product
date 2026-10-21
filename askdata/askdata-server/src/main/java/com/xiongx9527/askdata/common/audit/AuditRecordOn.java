package com.xiongx9527.askdata.common.audit;

/**
 * 审计记录触发时机（对齐 SQLBot system_log 的 success_only / record_on 语义）。
 */
public enum AuditRecordOn {
    /** 成功与失败都记录（默认）。 */
    BOTH,
    /** 仅成功时记录。 */
    SUCCESS_ONLY,
    /** 仅失败时记录。 */
    FAILED_ONLY
}
