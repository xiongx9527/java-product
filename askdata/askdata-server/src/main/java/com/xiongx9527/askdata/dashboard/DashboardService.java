package com.xiongx9527.askdata.dashboard;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 仪表盘（对齐 SQLBot apps/dashboard：api/dashboard_api.py + crud/dashboard_service.py）：
 * 资源树 CRUD/加载/画布/名称校验/移动。
 *
 * <p>资源树以 pid='root' 为根（对齐前端约定）；folder 为目录、leaf 为仪表盘画布。</p>
 */
@Service
public class DashboardService {
    @Autowired
    private MessageResolver messageResolver;

    private static final String ROOT_PID = "root";

    private final DashboardRepository dashboardRepository;

    private final CurrentUserService currentUserService;

    private final ChartDataExecutor chartDataExecutor;

    private final AuditRecorder auditRecorder;

    private final ObjectMapper objectMapper;

    public DashboardService(DashboardRepository dashboardRepository, CurrentUserService currentUserService,
                            ChartDataExecutor chartDataExecutor, AuditRecorder auditRecorder,
                            ObjectMapper objectMapper) {
        this.dashboardRepository = dashboardRepository;
        this.currentUserService = currentUserService;
        this.chartDataExecutor = chartDataExecutor;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** POST /dashboard/list_resource（资源树；对齐 SQLBot build_tree_generic root_pid=root）。 */
    public List<Map<String, Object>> listResource(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String workspaceId = String.valueOf(currentUserService.oidOf(user));
        String createBy = String.valueOf(Rows.lng(user, "id", 0L));
        String nodeType = Rows.str(body, "node_type");
        List<Map<String, Object>> nodes = dashboardRepository.list(workspaceId, createBy, nodeType);
        return buildTree(nodes);
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : nodes) {
            String id = Rows.str(row, "id");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("name", Rows.str(row, "name"));
            node.put("type", Rows.str(row, "type"));
            node.put("node_type", Rows.str(row, "node_type"));
            node.put("pid", Rows.str(row, "pid"));
            node.put("create_time", Rows.lng(row, "create_time", 0L));
            node.put("leaf", "leaf".equals(Rows.str(row, "node_type")));
            node.put("children", new ArrayList<>());
            byId.put(id, node);
        }
        for (Map<String, Object> row : nodes) {
            String id = Rows.str(row, "id");
            String pid = Rows.str(row, "pid");
            Map<String, Object> node = byId.get(id);
            if (ROOT_PID.equals(pid) || !byId.containsKey(pid)) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) byId.get(pid).get("children");
                children.add(node);
            }
        }
        return roots;
    }

    /**
     * POST /dashboard/load_resource（加载仪表盘；画布图表数据经
     * {@link ChartDataExecutor} 实时执行后回填，对齐 SQLBot load_resource）。
     */
    public Map<String, Object> loadResource(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String uid = String.valueOf(Rows.lng(user, "id", 0L));
        String id = Rows.str(body, "id");
        Map<String, Object> row = dashboardRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("dashboard.resource.not.exist", id)));
        if (!uid.equals(Rows.str(row, "create_by"))) {
            throw new BadRequestException(messageResolver.get("dashboard.resource.not.owned", id));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        // 逐字段输出小写键名（H2 可能返回大写列名；前端按 SQLBot 字段名消费）
        result.put("id", Rows.str(row, "id"));
        result.put("name", Rows.str(row, "name"));
        result.put("pid", Rows.str(row, "pid"));
        result.put("workspace_id", Rows.str(row, "workspace_id"));
        result.put("org_id", Rows.str(row, "org_id"));
        result.put("level", Rows.intg(row, "level", 0));
        result.put("node_type", Rows.str(row, "node_type"));
        result.put("type", Rows.str(row, "type"));
        result.put("canvas_style_data", Rows.str(row, "canvas_style_data"));
        result.put("component_data", Rows.str(row, "component_data"));
        result.put("mobile_layout", Rows.intg(row, "mobile_layout", 0));
        result.put("status", Rows.intg(row, "status", 1));
        result.put("self_watermark_status", Rows.intg(row, "self_watermark_status", 0));
        result.put("sort", Rows.intg(row, "sort", 0));
        result.put("create_time", Rows.lng(row, "create_time", 0L));
        result.put("create_by", Rows.str(row, "create_by"));
        result.put("update_time", Rows.lng(row, "update_time", 0L));
        result.put("update_by", Rows.str(row, "update_by"));
        result.put("remark", Rows.str(row, "remark"));
        result.put("source", Rows.str(row, "source"));
        result.put("delete_flag", Rows.intg(row, "delete_flag", 0));
        result.put("delete_time", Rows.lng(row, "delete_time", 0L));
        result.put("delete_by", Rows.str(row, "delete_by"));
        result.put("version", Rows.intg(row, "version", 3));
        result.put("content_id", Rows.str(row, "content_id"));
        result.put("check_version", Rows.str(row, "check_version"));
        String canvasViewInfo = Rows.str(row, "canvas_view_info");
        if (canvasViewInfo != null && !canvasViewInfo.isEmpty() && !"null".equals(canvasViewInfo)) {
            try {
                Map<String, Object> viewInfo = objectMapper.readValue(canvasViewInfo,
                        new TypeReference<Map<String, Object>>() {
                        });
                for (Map.Entry<String, Object> entry : viewInfo.entrySet()) {
                    if (!(entry.getValue() instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) entry.getValue();
                    Object datasource = item.get("datasource");
                    Object sql = item.get("sql");
                    if (datasource != null && sql != null) {
                        Map<String, Object> dataResult = chartDataExecutor.execute(
                                Rows.lngOrNull(Rows.of("datasource", datasource), "datasource"),
                                String.valueOf(sql));
                        Object data = item.get("data");
                        if (data instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dataMap = (Map<String, Object>) data;
                            dataMap.put("data", dataResult.get("data"));
                        }
                        item.put("status", dataResult.get("status"));
                        item.put("message", dataResult.get("message"));
                    }
                }
                result.put("canvas_view_info", objectMapper.writeValueAsString(viewInfo));
            } catch (Exception e) {
                // 画布视图信息解析失败时保留原值（对齐 SQLBot 容错）
            }
        }
        Map<Long, String> names = dashboardRepository.userNamesOf(Rows.str(row, "create_by"),
                Rows.str(row, "update_by"));
        result.put("create_name", userName(names, Rows.str(row, "create_by")));
        result.put("update_name", userName(names, Rows.str(row, "update_by")));
        return result;
    }

    private String userName(Map<Long, String> names, String createBy) {
        try {
            Long uid = Long.parseLong(createBy);
            return names.get(uid);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    /** POST /dashboard/create_resource（新建目录，对齐 SQLBot create_resource）。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.CREATE)
    public Map<String, Object> createResource(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis() / 1000;
        dashboardRepository.insert(id, requiredText(body, "name"), pidOf(body),
                String.valueOf(currentUserService.oidOf(user)), null,
                intOf(body.get("level"), 0), nodeTypeOf(body, "folder"), typeOf(body),
                null, null, null, 1, String.valueOf(Rows.lng(user, "id", 0L)), now);
        return baseInfo(id, body, user);
    }

    /** POST /dashboard/update_resource（重命名，对齐 SQLBot update_resource）。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.UPDATE)
    public Map<String, Object> updateResource(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String id = Rows.str(body, "id");
        requireOwned(id, user);
        long now = System.currentTimeMillis() / 1000;
        dashboardRepository.updateName(id, requiredText(body, "name"),
                String.valueOf(Rows.lng(user, "id", 0L)), now);
        return baseInfo(id, body, user);
    }

    /** POST /dashboard/create_canvas（新建画布，对齐 SQLBot create_canvas）。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.CREATE)
    public Map<String, Object> createCanvas(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String id = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis() / 1000;
        dashboardRepository.insert(id, requiredText(body, "name"), pidOf(body),
                String.valueOf(currentUserService.oidOf(user)), null,
                intOf(body.get("level"), 1), nodeTypeOf(body, "leaf"), typeOf(body),
                Rows.str(body, "canvas_style_data"), Rows.str(body, "component_data"),
                Rows.str(body, "canvas_view_info"), 1, String.valueOf(Rows.lng(user, "id", 0L)), now);
        return baseInfo(id, body, user);
    }

    /** POST /dashboard/update_canvas（保存画布，对齐 SQLBot update_canvas）。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.UPDATE)
    public Map<String, Object> updateCanvas(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String id = Rows.str(body, "id");
        requireOwned(id, user);
        long now = System.currentTimeMillis() / 1000;
        dashboardRepository.updateCanvas(id, requiredText(body, "name"), Rows.str(body, "component_data"),
                Rows.str(body, "canvas_style_data"), Rows.str(body, "canvas_view_info"),
                String.valueOf(Rows.lng(user, "id", 0L)), now);
        return baseInfo(id, body, user);
    }

    /** POST /dashboard/check_name（名称校验，对齐 SQLBot validate_name）。 */
    public boolean checkName(Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        String opt = Rows.str(body, "opt");
        if (opt == null || opt.isEmpty()) {
            throw new BadRequestException(messageResolver.get("dashboard.opt.required"));
        }
        String workspaceId = String.valueOf(currentUserService.oidOf(user));
        String uid = String.valueOf(Rows.lng(user, "id", 0L));
        String name = Rows.str(body, "name");
        String id = Rows.str(body, "id");
        if ("newLeaf".equals(opt) || "newFolder".equals(opt)) {
            return dashboardRepository.countByName(workspaceId, uid, name, null) == 0;
        }
        if ("updateLeaf".equals(opt) || "updateFolder".equals(opt) || "rename".equals(opt) || "move".equals(opt)) {
            if (id == null || id.isEmpty()) {
                throw new BadRequestException(messageResolver.get("dashboard.id.required.update"));
            }
            return dashboardRepository.countByName(workspaceId, uid, name, id) == 0;
        }
        throw new BadRequestException(messageResolver.get("dashboard.opt.invalid", opt));
    }

    /** DELETE /dashboard/delete_resource/{resource_id}/{name}。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.DELETE)
    public boolean deleteResource(String resourceId) {
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> row = dashboardRepository.findById(resourceId)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("dashboard.resource.not.exist", resourceId)));
        if (!String.valueOf(Rows.lng(user, "id", 0L)).equals(Rows.str(row, "create_by"))) {
            throw new BadRequestException(messageResolver.get("dashboard.resource.not.owned", resourceId));
        }
        dashboardRepository.deleteById(resourceId);
        return true;
    }

    /** DELETE /dashboard/move_resource/{id}（移动到目标父节点，body 带 pid）。 */
    @Transactional
    @AuditLog(module = AuditModule.DASHBOARD, operation = AuditOperationType.UPDATE)
    public void moveResource(String id, Map<String, Object> body) {
        Map<String, Object> user = currentUserService.currentUser();
        requireOwned(id, user);
        String pid = Rows.str(body, "pid");
        if (pid == null || pid.isEmpty()) {
            throw new BadRequestException(messageResolver.get("dashboard.pid.required"));
        }
        dashboardRepository.updatePid(id, pid, String.valueOf(Rows.lng(user, "id", 0L)),
                System.currentTimeMillis() / 1000);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void requireOwned(String id, Map<String, Object> user) {
        Map<String, Object> row = dashboardRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("dashboard.resource.not.exist", id)));
        if (!String.valueOf(Rows.lng(user, "id", 0L)).equals(Rows.str(row, "create_by"))) {
            throw new BadRequestException(messageResolver.get("dashboard.resource.not.owned", id));
        }
    }

    /** BaseDashboard 响应（对齐 SQLBot dashboard_model.BaseDashboard）。 */
    private Map<String, Object> baseInfo(String id, Map<String, Object> body, Map<String, Object> user) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("name", Rows.str(body, "name"));
        info.put("pid", pidOf(body));
        info.put("workspace_id", String.valueOf(currentUserService.oidOf(user)));
        info.put("org_id", Rows.str(body, "org_id"));
        info.put("type", typeOf(body));
        info.put("node_type", nodeTypeOf(body, "folder"));
        info.put("level", intOf(body.get("level"), 0));
        info.put("create_by", Rows.lng(user, "id", 0L));
        return info;
    }

    private static String pidOf(Map<String, Object> body) {
        String pid = Rows.str(body, "pid");
        return pid == null || pid.isEmpty() ? ROOT_PID : pid;
    }

    private static String typeOf(Map<String, Object> body) {
        String type = Rows.str(body, "type");
        return type == null || type.isEmpty() ? "dashboard" : type;
    }

    private static String nodeTypeOf(Map<String, Object> body, String fallback) {
        String nodeType = Rows.str(body, "node_type");
        return nodeType == null || nodeType.isEmpty() ? fallback : nodeType;
    }

    private static int intOf(Object value, int fallback) {
        Long parsed = value instanceof Number ? ((Number) value).longValue() : null;
        if (parsed == null && value != null) {
            try {
                parsed = Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
        }
        return parsed == null ? fallback : parsed.intValue();
    }

    private String requiredText(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("dashboard.param.required", key));
        }
        return value.trim();
    }
}
