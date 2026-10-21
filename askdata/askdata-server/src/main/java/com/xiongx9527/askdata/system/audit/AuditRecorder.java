package com.xiongx9527.askdata.system.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.audit.AuditClientIpResolver;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审计日志写入组件（移植 SQLBot {@code common/audit/schemas/logger_decorator.py} 的
 * LogConfig/system_log 约定）：在各业务操作点记录 sys_logs。
 *
 * <p>可复用：各模块在操作成功后调用 {@link #success}，失败时调用 {@link #failure}；
 * 写入异常不影响业务流程（仅告警日志）。当前上下文无登录用户时跳过记录。</p>
 *
 * <p>相对早期实现补齐三点：</p>
 * <ul>
 *   <li>{@code execution_time} 记录<b>真实耗时</b>（毫秒），此前硬编码 0；</li>
 *   <li>客户端 IP 走 {@link AuditClientIpResolver} 的六级链 + RFC 7239；</li>
 *   <li>资源名解析 + {@code sys_logs_resource} 关联行写入。</li>
 * </ul>
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    private final UserRepository userRepository;

    private final AuditResourceNameResolver resourceNameResolver;

    public AuditRecorder(JdbcTemplate jdbc, IdGenerator idGenerator, UserRepository userRepository,
                         AuditResourceNameResolver resourceNameResolver) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.userRepository = userRepository;
        this.resourceNameResolver = resourceNameResolver;
    }

    /** 操作成功记录（对齐 SQLBot save_on_success_only 语义：仅成功时落库）。 */
    public void success(AuditOperationType type, AuditModule module, String resourceId) {
        record(type, module, resourceId, null, null, null, "success", null, 0L);
    }

    /** 操作成功记录（带 remark，对齐 SQLBot remark_expr）。 */
    public void success(AuditOperationType type, AuditModule module, String resourceId, String remark) {
        record(type, module, resourceId, remark, null, null, "success", null, 0L);
    }

    /** 操作失败记录（带错误信息）。 */
    public void failure(AuditOperationType type, AuditModule module, String resourceId, String errorMessage) {
        record(type, module, resourceId, null, null, null, "failed", errorMessage, 0L);
    }

    /**
     * 完整记录入口。
     *
     * @param operationDetail 操作详情（SQLBot operation_detail；可为空）
     * @param resourceName    资源名称（对齐 SQLBot resource_name；为空时自动解析）
     * @param executionTime   执行耗时（毫秒；真实值，非 0）
     */
    public void record(AuditOperationType type, AuditModule module, String resourceId, String remark,
                       String operationDetail, String resourceName, String status, String errorMessage,
                       long executionTime) {
        try {
            Long uid = currentUid();
            if (uid == null) {
                return;
            }
            Optional<Map<String, Object>> user = userRepository.findById(uid);
            long oid = user.map(u -> Rows.lng(u, "oid", 0L)).orElse(0L);
            String userName = user.map(u -> Rows.str(u, "name")).orElse(null);
            String ip = AuditClientIpResolver.resolve(currentRequest());
            String ua = userAgent();
            long logId = idGenerator.nextId();
            String resolvedName = resourceName;
            if ((resolvedName == null || resolvedName.isEmpty()) && resourceId != null && !resourceId.isEmpty()) {
                resolvedName = resourceNameResolver.resolve(resourceId, module.code());
            }
            jdbc.update("INSERT INTO sys_logs (id, operation_type, operation_detail, user_id, user_name, "
                            + "operation_status, oid, ip_address, user_agent, execution_time, error_message, "
                            + "create_time, module, remark, resource_id, resource_name, request_method, request_path) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    logId, type.code(), operationDetail, uid, userName, status, oid, ip, ua,
                    executionTime, errorMessage, System.currentTimeMillis(), module.code(), remark,
                    resourceId == null ? null : String.valueOf(resourceId), resolvedName,
                    requestMethod(), requestPath());
            linkResources(logId, resourceId, module, resolvedName);
        } catch (Exception e) {
            // 审计写入不得影响业务流程（对齐 SQLBot SystemLogger.create_log 的容错）
            log.warn("audit record failed: {}", e.getMessage());
        }
    }

    /**
     * 写入 {@code sys_logs_resource} 关联行（对齐 SQLBot 的多对多资源关联）。
     *
     * <p>表不存在（老库未升级）时静默忽略——审计主记录已落库，关联行缺失不应报错。</p>
     */
    private void linkResources(long logId, String resourceId, AuditModule module, String resourceName) {
        if (resourceId == null || resourceId.isEmpty()) {
            return;
        }
        try {
            List<String> ids = new ArrayList<>();
            ids.add(resourceId);
            List<Map<String, Object>> resources = resourceNameResolver.resolveAll(ids, module.code());
            for (Map<String, Object> resource : resources) {
                jdbc.update("INSERT INTO sys_logs_resource (id, resource_id, module, resource_name, log_id) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        idGenerator.nextId(), resource.get("resource_id"), resource.get("module"),
                        resourceName != null ? resourceName : resource.get("resource_name"), logId);
            }
        } catch (Exception e) {
            log.debug("写入审计资源关联失败（忽略）: {}", e.getMessage());
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
