package com.xiongx9527.askdata.system.audit;

import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 审计切面（对齐 SQLBot {@code @system_log} 装饰器）。
 *
 * <p>在带 {@link AuditLog} 注解的方法上自动记录审计：</p>
 * <ul>
 *   <li>资源 id：先按 {@code resourceIdExpr} 从<b>入参</b>提取；为空时按
 *       {@code resultIdExpr} 从<b>返回值</b>提取；</li>
 *   <li>备注：按 {@code remarkExpr} 从返回值提取；</li>
 *   <li>{@code execution_time}：<b>真实耗时</b>（毫秒）；</li>
 *   <li>异常：记录失败状态与错误信息（{@code saveOnSuccessOnly=true} 时仍记录失败，
 *       因为失败本身是审计要覆盖的场景）；</li>
 *   <li>审计写入失败不阻断业务（{@link AuditRecorder} 内部吞异常）。</li>
 * </ul>
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditRecorder auditRecorder;

    public AuditLogAspect(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /**
     * 环绕通知：执行被注解的方法并记录审计。
     *
     * @param joinPoint 连接点
     * @return 方法返回值
     * @throws Throwable 方法自身的异常原样抛出
     */
    @Around("@annotation(com.xiongx9527.askdata.system.audit.AuditLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        AuditLog annotation = annotationOf(joinPoint);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        long start = System.currentTimeMillis();
        String resourceId = extractResourceId(joinPoint, annotation);
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            String resultId = extractResultId(result, annotation.resultIdExpr());
            if (resultId != null && !resultId.isEmpty()) {
                resourceId = resultId;
            }
            String remark = extractResultId(result, annotation.remarkExpr());
            String resourceName = extractResultId(result, "name");
            auditRecorder.record(annotation.operationType(), annotation.module(), resourceId, remark,
                    annotation.operationDetail(), resourceName, "success", null, elapsed);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - start;
            auditRecorder.record(annotation.operationType(), annotation.module(), resourceId, null,
                    annotation.operationDetail(), null, "failed",
                    e.getMessage() == null ? e.toString() : e.getMessage(), elapsed);
            throw e;
        }
    }

    /** 取方法上的 {@link AuditLog} 注解。 */
    private static AuditLog annotationOf(ProceedingJoinPoint joinPoint) {
        Method method = methodOf(joinPoint);
        return method == null ? null : method.getAnnotation(AuditLog.class);
    }

    /** 取目标方法。 */
    private static Method methodOf(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature)) {
            return null;
        }
        return ((MethodSignature) joinPoint.getSignature()).getMethod();
    }

    /**
     * 从入参提取资源 id。
     *
     * <p>支持 {@code args[N]}（第 N 个位置参数）与 {@code name}（按参数名匹配，
     * 需编译时保留参数名；取不到时按类型匹配常见 id 形参）。</p>
     */
    private static String extractResourceId(ProceedingJoinPoint joinPoint, AuditLog annotation) {
        String expression = annotation.resourceIdExpr();
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        if (expression.startsWith("args[")) {
            try {
                int index = Integer.parseInt(expression.substring(5, expression.length() - 1));
                if (index >= 0 && index < args.length) {
                    return asIdString(args[index]);
                }
            } catch (RuntimeException e) {
                return null;
            }
            return null;
        }
        // 按参数名匹配（编译未保留参数名时 name 取不到）
        Method method = methodOf(joinPoint);
        if (method != null) {
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                if (parameters[i].getName().equals(expression)) {
                    return asIdString(args[i]);
                }
            }
        }
        // 退化为：第一个整型参数
        for (Object arg : args) {
            if (arg instanceof Long || arg instanceof Integer) {
                return asIdString(arg);
            }
        }
        return null;
    }

    /**
     * 从返回值按表达式提取文本值。
     *
     * <p>支持 {@code name}（Map 键）、{@code data.id}（嵌套 Map 路径）。</p>
     */
    static String extractResultId(Object result, String expression) {
        if (result == null || expression == null || expression.isEmpty()) {
            return null;
        }
        if (result instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) result;
            String[] parts = expression.split("\\.");
            Object current = map;
            for (String part : parts) {
                if (!(current instanceof Map)) {
                    return null;
                }
                Map<?, ?> currentMap = (Map<?, ?>) current;
                Object value = currentMap.get(part);
                if (value == null) {
                    // 大小写不敏感再试一次
                    for (Map.Entry<?, ?> entry : currentMap.entrySet()) {
                        if (entry.getKey() != null && part.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                            value = entry.getValue();
                            break;
                        }
                    }
                }
                if (value == null) {
                    return null;
                }
                current = value;
            }
            return asIdString(current);
        }
        return null;
    }

    /** 值转字符串（null → null）。 */
    private static String asIdString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
