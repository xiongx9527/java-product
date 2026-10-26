package com.xiongx9527.askdata.system.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.audit.AuditClientIpResolver;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.AuthContext;
import com.xiongx9527.askdata.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;

/**
 * 审计日志写入组件（移植 SQLBot {@code common/audit/schemas/logger_decorator.py} 的
 * LogConfig/system_log 约定）：在各业务操作点记录 sys_logs。
 *
 * <p>写操作优先经 {@link com.xiongx9527.askdata.common.audit.AuditLog} 切面
 * （{@link #recordTimed}）自动记录，含真实 {@code execution_time}、资源名（经
 * {@code AuditResourceNameResolver} 解析）、资源关联行（写入 {@code sys_logs_resource}）。
 * 写入异常不影响业务流程（仅告警日志）。当前上下文无登录用户时跳过普通记录（登录审计见
 * {@link #recordLogin}）。</p>
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    private final UserRepository userRepository;

    private final AuditClientIpResolver clientIpResolver;

    private final AuditResourceNameResolver resourceNameResolver;

    public AuditRecorder(JdbcTemplate jdbc, IdGenerator idGenerator, UserRepository userRepository,
                         AuditClientIpResolver clientIpResolver, AuditResourceNameResolver resourceNameResolver) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.userRepository = userRepository;
        this.clientIpResolver = clientIpResolver;
        this.resourceNameResolver = resourceNameResolver;
    }

    /** 操作成功记录（对齐 SQLBot save_on_success_only 语义：仅成功时落库）。 */
    public void success(AuditOperationType type, AuditModule module, String resourceId) {
        record(type, module, resourceId, null, null, null, "success", null);
    }

    /** 操作成功记录（带 remark，对齐 SQLBot remark_expr）。 */
    public void success(AuditOperationType type, AuditModule module, String resourceId, String remark) {
        record(type, module, resourceId, remark, null, null, "success", null);
    }

    /** 操作失败记录（带错误信息）。 */
    public void failure(AuditOperationType type, AuditModule module, String resourceId, String errorMessage) {
        record(type, module, resourceId, null, null, null, "failed", errorMessage);
    }

    /**
     * 供切面在删除类操作<b>执行前</b>解析资源名：删除成功后业务行已不存在，
     * 事后查表会拿到 null（对齐 SQLBot 在业务操作前取资源名的语义）。
     */
    public String resolveResourceName(AuditModule module, String resourceId) {
        return resourceNameResolver.resolve(module, resourceId);
    }

    /**
     * 带真实执行耗时的完整记录入口（由审计切面调用）。
     *
     * @param resourceName    资源名称（为空则按 module + resourceId 查表解析）
     * @param executionTimeMs 实际执行耗时（毫秒）
     */
    public void recordTimed(AuditOperationType type, AuditModule module, String resourceId, String remark,
                            String operationDetail, String resourceName, String status, String errorMessage,
                            long executionTimeMs) {
        try {
            Long uid = currentUid();
            if (uid == null) {
                return;
            }
            Optional<Map<String, Object>> user = userRepository.findById(uid);
            long oid = user.map(u -> Rows.lng(u, "oid", 0L)).orElse(0L);
            String userName = user.map(u -> Rows.str(u, "name")).orElse(null);
            String ip = clientIp();
            String ua = userAgent();
            long logId = idGenerator.nextId();
            String resolvedName = (resourceName == null || resourceName.isEmpty())
                    ? resourceNameResolver.resolve(module, resourceId) : resourceName;
            // 逗号分隔的多资源（如工作空间成员批量移除）：逐个解析展示名并以逗号拼接，
            // 否则按整串查表恒为 null（对齐 SQLBot 对 resource_id_info 逐项取名的语义）。
            if ((resolvedName == null || resolvedName.isEmpty())
                    && resourceId != null && String.valueOf(resourceId).contains(",")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String ridValue : String.valueOf(resourceId).split(",")) {
                    String name = resourceNameResolver.resolve(module, ridValue.trim());
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
                if (!names.isEmpty()) {
                    resolvedName = String.join(",", names);
                }
            }
            jdbc.update("INSERT INTO sys_logs (id, operation_type, operation_detail, user_id, user_name, "
                            + "operation_status, oid, ip_address, user_agent, execution_time, error_message, "
                            + "create_time, module, remark, resource_id, resource_name, request_method, request_path) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    logId, type.code(), operationDetail, uid, userName, status, oid, ip, ua,
                    executionTimeMs, errorMessage, System.currentTimeMillis(), module.code(), remark,
                    resourceId == null ? null : String.valueOf(resourceId), resolvedName,
                    requestMethod(), requestPath());
            // F-19：SQLBot 语义——sys_logs_resource 按「归一化后的 resource_id」写关联行：
            // 支持逗号分隔多值（SQLBot resource_id_info 为 list 时逐行写入的语义）；
            // resource_id 为空串时也保底写一条关联行（保留 module 维度），
            // 仅当确实无 resourceId 语义（resourceId 为 null）时跳过。
            if (resourceId != null) {
                java.util.List<String> resourceIdValues = new java.util.ArrayList<>();
                for (String ridValue : String.valueOf(resourceId).split(",")) {
                    if (!ridValue.trim().isEmpty()) {
                        resourceIdValues.add(ridValue.trim());
                    }
                }
                if (resourceIdValues.isEmpty()) {
                    resourceIdValues.add("");
                }
                try {
                    for (String ridValue : resourceIdValues) {
                        String rowName = resolvedName;
                        if (resourceIdValues.size() > 1 && (resourceName == null || resourceName.isEmpty())) {
                            rowName = resourceNameResolver.resolve(module, ridValue);
                        }
                        jdbc.update("INSERT INTO sys_logs_resource (id, log_id, resource_id, resource_name, module) "
                                        + "VALUES (?, ?, ?, ?, ?)",
                                idGenerator.nextId(), logId, ridValue, rowName, module.code());
                    }
                } catch (Exception e) {
                    // 资源关联行写入失败不影响主审计记录
                    log.warn("audit resource row failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            // 审计写入不得影响业务流程（对齐 SQLBot SystemLogger.create_log 的容错）
            log.warn("audit record failed: {}", e.getMessage());
        }
    }

    /**
     * 登录审计（成功/失败均记录，对齐 SQLBot 对登录的 @system_log）：登录时无已登录用户，
     * 故以尝试登录的账号名记录 user_name，user_id 为空。
     */
    public void recordLogin(String account, AuditOperationType type, String status, String errorMessage,
                            long executionTimeMs) {
        try {
            long logId = idGenerator.nextId();
            jdbc.update("INSERT INTO sys_logs (id, operation_type, user_id, user_name, operation_status, oid, "
                            + "ip_address, user_agent, execution_time, error_message, create_time, module, "
                            + "resource_id, resource_name, request_method, request_path) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    logId, type.code(), null, account, status, 0L, clientIp(), userAgent(),
                    executionTimeMs, errorMessage, System.currentTimeMillis(), AuditModule.USER.code(),
                    null, null, requestMethod(), requestPath());
        } catch (Exception e) {
            log.warn("audit login record failed: {}", e.getMessage());
        }
    }

    /** 完整记录入口（保留给历史手动调用，execution_time 恒为 0）。 */
    public void record(AuditOperationType type, AuditModule module, String resourceId, String remark,
                       String operationDetail, String resourceName, String status, String errorMessage) {
        recordTimed(type, module, resourceId, remark, operationDetail, resourceName, status, errorMessage, 0L);
    }

    protected Long currentUid() {
        // 支线通道（API Key / 嵌入助手 token / SQLBot mcp_assistant 合成用户）优先使用
        // AuthContext 中的当前用户；合成用户 id=-1 也必须落审计（A4）。
        Map<String, Object> user = AuthContext.currentUser();
        if (user != null) {
            try {
                long id = Rows.lng(user, "id", 0L);
                if (id != 0L) {
                    return id;
                }
            } catch (RuntimeException ignored) {
                // 非法 id 退回 Sa-Token
            }
        }
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        }
        return null;
    }

    private String clientIp() {
        return clientIpResolver.resolve(currentRequest());
    }

    private String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private String requestMethod() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getMethod();
    }

    private String requestPath() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRequestURI();
    }
}
