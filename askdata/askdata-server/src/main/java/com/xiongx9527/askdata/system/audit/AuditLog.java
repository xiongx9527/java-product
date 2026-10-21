package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解（对齐 SQLBot {@code common/audit/schemas/logger_decorator.py} 的 {@code @system_log}）。
 *
 * <p>标注在 Controller/Service 的写操作方法上，由 {@link AuditLogAspect} 自动记录审计，
 * 取代逐处手动调用 {@code auditRecorder.*}。</p>
 *
 * <p>属性语义：</p>
 * <ul>
 *   <li>{@link #operationType()}：操作类型（create/delete/update/...）；</li>
 *   <li>{@link #module()}：所属模块（数据源/术语/…）；</li>
 *   <li>{@link #operationDetail()}：操作详情（可选）；</li>
 *   <li>{@link #resourceIdExpr()}：资源 id 表达式（从方法入参中提取，如 {@code "id"} 或
 *       {@code "args[0]"}；可为空表示不记录资源）；</li>
 *   <li>{@link #resultIdExpr()}：资源 id 表达式（从返回值中提取，如 {@code "id"}）；</li>
 *   <li>{@link #remarkExpr()}：备注表达式（从返回值中提取，如 {@code "name"}）；</li>
 *   <li>{@link #saveOnSuccessOnly()}：仅成功时记录（默认 true，对齐 SQLBot 语义）。</li>
 * </ul>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型。 */
    AuditOperationType operationType();

    /** 所属模块。 */
    AuditModule module();

    /** 操作详情（可空）。 */
    String operationDetail() default "";

    /** 资源 id 表达式（从入参提取；可空）。 */
    String resourceIdExpr() default "";

    /** 资源 id 表达式（从返回值提取；可空）。 */
    String resultIdExpr() default "";

    /** 备注表达式（从返回值提取；可空）。 */
    String remarkExpr() default "";

    /** 仅成功时记录（默认 true）。 */
    boolean saveOnSuccessOnly() default true;
}
