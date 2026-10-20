package com.xiongx9527.askdata.system.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
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
 * <p>可复用：各模块在操作成功后调用 {@link #success}，失败时调用 {@link #failure}；
 * 写入异常不影响业务流程（仅告警日志）。当前上下文无登录用户时跳过记录。</p>
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    private final UserRepository userRepository;

    public AuditRecorder(JdbcTemplate jdbc, IdGenerator idGenerator, UserRepository userRepository) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.userRepository = userRepository;
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
     * 完整记录入口。
     *
     * @param operationDetail 操作详情（SQLBot operation_detail；可为空）
     * @param resourceName    资源名称（对齐 SQLBot resource_name）
     */
    public void record(AuditOperationType type, AuditModule module, String resourceId, String remark,
                       String operationDetail, String resourceName, String status, String errorMessage) {
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
            jdbc.update("INSERT INTO sys_logs (id, operation_type, operation_detail, user_id, user_name, "
                            + "operation_status, oid, ip_address, user_agent, execution_time, error_message, "
                            + "create_time, module, remark, resource_id, resource_name, request_method, request_path) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    idGenerator.nextId(), type.code(), operationDetail, uid, userName, status, oid, ip, ua,
                    0L, errorMessage, System.currentTimeMillis(), module.code(), remark,
                    resourceId == null ? null : String.valueOf(resourceId), resourceName,
                    requestMethod(), requestPath());
        } catch (Exception e) {
            // 审计写入不得影响业务流程（对齐 SQLBot SystemLogger.create_log 的容错）
            log.warn("audit record failed: {}", e.getMessage());
        }
    }

    private Long currentUid() {
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
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
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
