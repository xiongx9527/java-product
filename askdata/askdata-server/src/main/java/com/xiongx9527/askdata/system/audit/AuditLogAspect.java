package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditClientIpResolver;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.audit.AuditRecordOn;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审计切面（对齐 SQLBot {@code common/audit/schemas/logger_decorator.py} 的 @system_log 装饰器）。
 *
 * <p>拦截所有标注 {@link AuditLog} 的方法：测量真实执行耗时、按 module + resourceId
 * 解析资源名、按成功/失败记录审计（含 sys_logs 主记录与 sys_logs_resource 关联行）。
 * 切面自身包裹在 try/catch 中，审计写入失败绝不阻断业务方法返回。</p>
 */
@Aspect
@Component
@Order(100)
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditRecorder recorder;

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer paramNames = new org.springframework.core.DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                long executionTime = System.currentTimeMillis() - start;
                boolean success = error == null;
                if (shouldRecord(auditLog.recordOn(), success)) {
                String status = success ? "success" : "failed";
                String resourceId = resolveResourceId(auditLog, pjp, result);
                String resourceName = resolveResourceName(auditLog, pjp, result, resourceId, auditLog.module());
                String errorMessage = error == null ? null
                        : (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                recorder.recordTimed(auditLog.operation(), auditLog.module(), resourceId, null, null,
                        resourceName, status, errorMessage, executionTime);
                }
            } catch (Throwable ignore) {
                // 审计逻辑异常不得影响业务
                log.warn("audit aspect failed: {}", ignore.getMessage());
            }
        }
    }

    private boolean shouldRecord(AuditRecordOn on, boolean success) {
        if (on == AuditRecordOn.SUCCESS_ONLY) {
            return success;
        }
        if (on == AuditRecordOn.FAILED_ONLY) {
            return !success;
        }
        return true;
    }

    private String resolveResourceId(AuditLog ann, ProceedingJoinPoint pjp, Object result) {
        if (!ann.resourceId().isEmpty()) {
            Object v = eval(ann.resourceId(), pjp, result, null);
            return v == null ? null : asString(v);
        }
        return heuristicResourceId(pjp, result);
    }

    private String resolveResourceName(AuditLog ann, ProceedingJoinPoint pjp, Object result, String resourceId,
                                       AuditModule module) {
        if (!ann.resourceName().isEmpty()) {
            Object v = eval(ann.resourceName(), pjp, result, null);
            return v == null ? null : String.valueOf(v);
        }
        return null; // 由 AuditRecorder 经 AuditResourceNameResolver 解析
    }

    @SuppressWarnings("unchecked")
    private String heuristicResourceId(ProceedingJoinPoint pjp, Object result) {
        Object v = tryExtract(result);
        if (v != null) {
            return asString(v);
        }
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        String[] names = ms.getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof List) {
                List<?> list = (List<?>) a;
                if (!list.isEmpty()) {
                    return list.stream().map(Object::toString).collect(Collectors.joining(","));
                }
            }
            v = tryExtract(a);
            if (v != null) {
                return asString(v);
            }
            if (a instanceof String && names != null && i < names.length) {
                String n = names[i];
                if ("id".equals(n) || "resourceId".equals(n) || "resource_id".equals(n)) {
                    return (String) a;
                }
            }
        }
        return null;
    }

    private Object tryExtract(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return o;
        }
        if (o instanceof Map) {
            Object id = ((Map<?, ?>) o).get("id");
            if (id != null) {
                return id;
            }
        }
        try {
            Method m = o.getClass().getMethod("getId");
            return m.invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object v) {
        if (v instanceof List) {
            return ((List<?>) v).stream().map(Object::toString).collect(Collectors.joining(","));
        }
        return String.valueOf(v);
    }

    private Object eval(String expr, ProceedingJoinPoint pjp, Object result, Throwable error) {
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            MethodSignature ms = (MethodSignature) pjp.getSignature();
            String[] names = ms.getParameterNames();
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length && i < args.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            ctx.setVariable("result", result);
            ctx.setVariable("error", error);
            return parser.parseExpression(expr).getValue(ctx, Object.class);
        } catch (Exception e) {
            log.warn("audit spel eval failed for [{}]: {}", expr, e.getMessage());
            return null;
        }
    }
}
