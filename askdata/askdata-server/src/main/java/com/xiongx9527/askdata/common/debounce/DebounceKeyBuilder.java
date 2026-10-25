package com.xiongx9527.askdata.common.debounce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 防抖键构造（纯逻辑，便于单测）：把「接口 + 可选用户 + 可选参数 + 可选 SpEL」拼成稳定字符串后做 SHA-256 摘要。
 * 避免在缓存键里落下令牌或参数原文。
 */
@Component
public class DebounceKeyBuilder {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    public DebounceKeyBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造防抖键。
     *
     * @param method   被标注的方法
     * @param args     方法实际参数
     * @param debounce 注解
     * @param userId   当前用户 id（null 表示取不到，回退 anon）
     * @return 缓存键（SHA-256 十六进制摘要）
     */
    public String buildKey(Method method, Object[] args, Debounce debounce, Long userId) {
        String base = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        StringBuilder sb = new StringBuilder(base);
        if (debounce.user()) {
            sb.append("|u:").append(userId == null ? "anon" : userId.toString());
        }
        if (debounce.args().length > 0) {
            sb.append("|a:").append(serializeArgs(method, debounce.args(), args));
        }
        if (debounce.spel().length > 0) {
            sb.append("|s:").append(evalSpel(method, debounce.spel(), args, userId));
        }
        return digest(sb.toString());
    }

    private String serializeArgs(Method method, String[] selectors, Object[] args) {
        boolean all = Arrays.stream(selectors).anyMatch(s -> {
            String t = s == null ? "" : s.trim();
            return "*".equals(t) || "#".equals(t);
        });
        if (all) {
            return safeJson(args);
        }
        StringBuilder sb = new StringBuilder();
        for (String sel : selectors) {
            Object arg = resolveArg(method, sel == null ? "" : sel.trim(), args);
            sb.append(arg == null ? "null" : safeJson(arg)).append('|');
        }
        return sb.toString();
    }

    private Object resolveArg(Method method, String sel, Object[] args) {
        if (sel.isEmpty()) {
            return null;
        }
        String s = sel;
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        String idx = s;
        if (idx.startsWith("p") || idx.startsWith("a")) {
            idx = idx.substring(1);
        }
        if (!idx.isEmpty() && idx.chars().allMatch(Character::isDigit)) {
            int i = Integer.parseInt(idx);
            if (i >= 0 && i < args.length) {
                return args[i];
            }
            return null;
        }
        String[] names = paramNames.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(sel) && i < args.length) {
                    return args[i];
                }
            }
        }
        return null;
    }

    private String evalSpel(Method method, String[] exprs, Object[] args, Long userId) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] names = paramNames.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                ctx.setVariable(names[i], args[i]);
            }
        }
        ctx.setVariable("user", userId == null ? "anon" : userId.toString());
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("p" + i, args[i]);
            ctx.setVariable("a" + i, args[i]);
        }
        StringBuilder sb = new StringBuilder();
        for (String expr : exprs) {
            if (expr == null || expr.trim().isEmpty()) {
                continue;
            }
            try {
                Object v = parser.parseExpression(expr).getValue(ctx, Object.class);
                sb.append(v == null ? "null" : v.toString()).append('|');
            } catch (Exception e) {
                sb.append("err|");
            }
        }
        return sb.toString();
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
