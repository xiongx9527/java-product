package com.xiongx9527.askdata.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解（对齐 SQLBot {@code common/audit/schemas/logger_decorator.py} 的 {@code @system_log} 装饰器）。
 *
 * <p>在写操作方法（新增/修改/删除等）上标注后，由 {@code AuditLogAspect} 自动记录审计：
 * 含真实 {@code execution_time}、资源名（经 {@code AuditResourceNameResolver} 解析）、资源关联行
 * （写入 {@code sys_logs_resource}）；无论审计写入成功与否都不阻断业务流程。</p>
 *
 * <p>资源 ID 解析优先级：{@link #resourceId()} 指定的 SpEL 表达式 &gt; 启发式推导
 * （返回值 / 参数中的 id / resourceId / ids）。资源名解析优先级：{@link #resourceName()}
 * 指定的 SpEL &gt; {@code AuditResourceNameResolver} 按 module + resourceId 查表。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作所属模块（必填）。 */
    AuditModule module();

    /** 操作类型（必填）。 */
    AuditOperationType operation();

    /**
     * 资源 ID 的 SpEL 表达式，可引用方法参数名（如 {@code #id}、{@code #body.id}）
     * 与返回值 {@code #result}。为空时走启发式推导。
     */
    String resourceId() default "";

    /**
     * 资源名的 SpEL 表达式，可引用方法参数与 {@code #result}。为空时由
     * {@code AuditResourceNameResolver} 按 module + resourceId 查表解析。
     */
    String resourceName() default "";

    /** 记录触发时机，默认成功与失败都记录。 */
    AuditRecordOn recordOn() default AuditRecordOn.BOTH;
}
