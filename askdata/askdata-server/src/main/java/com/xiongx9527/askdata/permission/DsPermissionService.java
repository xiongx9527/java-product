package com.xiongx9527.askdata.permission;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

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

/**
 * 数据权限配置（SQLBot /ds_permission 位于 xpack ds_permission，此处按 D4「行为可用」重写；
 * 数据结构移植 SQLBot alembic 026/027 与前端 views/system/permission 的保存契约）：
 * 规则组 list/save/delete。
 *
 * <p>行/列权限在问数执行时的过滤由 datasource-engine/chat-core 消费
 * {@code ds_permission}（expression_tree/permissions/white_list_user）实现，
 * 本模块负责配置管理面。</p>
 */
@Service
public class DsPermissionService {
    @Autowired
    private MessageResolver messageResolver;

    private final DsPermissionRepository dsPermissionRepository;

    private final CurrentUserService currentUserService;

    private final AuditRecorder auditRecorder;

    public DsPermissionService(DsPermissionRepository dsPermissionRepository, CurrentUserService currentUserService,
                               AuditRecorder auditRecorder) {
        this.dsPermissionRepository = dsPermissionRepository;
        this.currentUserService = currentUserService;
        this.auditRecorder = auditRecorder;
    }

    /** POST /ds_permission/list（规则组列表；权限条目同时给出字符串与解析形态）。 */
    public List<Map<String, Object>> list() {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> rules = dsPermissionRepository.listRules(oid);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            long ruleId = Rows.lng(rule, "id", 0L);
            List<Map<String, Object>> entries = dsPermissionRepository.listPermissionsByRule(ruleId);
            List<Map<String, Object>> permissions = new ArrayList<>();
            for (Map<String, Object> entry : entries) {
                permissions.add(entryView(entry));
            }
            if (permissions.isEmpty()) {
                // 兼容仅保存 permission_list 的历史数据
                for (Map<String, Object> item : dsPermissionRepository.jsonArray(Rows.str(rule, "permission_list"))) {
                    permissions.add(entryView(item));
                }
            }
            result.add(Rows.of(
                    "id", ruleId,
                    "name", Rows.str(rule, "name"),
                    "permissions", permissions,
                    "users", dsPermissionRepository.longArray(Rows.str(rule, "user_list"))));
        }
        return result;
    }

    /**
     * 条目视图（对齐前端编辑契约）：
     * permissions/expression_tree 为 JSON 字符串（保存时回传），
     * permission_list/tree 为解析形态（编辑表单直接使用）。
     */
    private Map<String, Object> entryView(Map<String, Object> entry) {
        String type = Rows.str(entry, "type");
        String permissions = Rows.str(entry, "permissions");
        String expressionTree = Rows.str(entry, "expression_tree");
        if (permissions == null) {
            permissions = "[]";
        }
        if (expressionTree == null) {
            expressionTree = "{}";
        }
        Object permissionList = dsPermissionRepository.fromJson(permissions, Object.class, new ArrayList<>());
        Object tree = dsPermissionRepository.fromJson(expressionTree, Object.class, new LinkedHashMap<>());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Rows.lngOrNull(entry, "id"));
        view.put("name", Rows.str(entry, "name"));
        view.put("type", type);
        view.put("ds_id", Rows.lngOrNull(entry, "ds_id"));
        view.put("ds_name", Rows.str(entry, "ds_name"));
        view.put("table_id", Rows.lngOrNull(entry, "table_id"));
        view.put("table_name", Rows.str(entry, "table_name"));
        view.put("permissions", permissions);
        view.put("permission_list", permissionList);
        view.put("expression_tree", expressionTree);
        view.put("tree", tree);
        return view;
    }

    /**
     * POST /ds_permission/save（对齐前端 save 载荷）：
     * {id?, name, permissions: [{name, type, ds_id, table_id, ds_name, table_name,
     * permissions: <json string>, expression_tree: <json string>}], users: [uid...]}。
     */
    @Transactional
    @AuditLog(module = AuditModule.PERMISSION, operation = AuditOperationType.CREATE_OR_UPDATE)
    public long save(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        String name = Rows.str(body, "name");
        if (name == null || name.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("perm.ds.name.required"));
        }
        List<Map<String, Object>> entries = Rows.mapList(Rows.get(body, "permissions"));
        List<Long> users = Rows.longList(Rows.get(body, "users"));
        long now = System.currentTimeMillis();
        String permissionListJson = dsPermissionRepository.toJson(entries);
        String userListJson = dsPermissionRepository.toJson(users);
        Long id = Rows.lngOrNull(body, "id");
        long ruleId;
        if (id != null) {
            if (!dsPermissionRepository.findRule(id).isPresent()) {
                throw new BadRequestException(messageResolver.get("perm.ds.rule.not.exist"));
            }
            dsPermissionRepository.updateRule(id, name, null, permissionListJson, userListJson);
            ruleId = id;
        } else {
            ruleId = dsPermissionRepository.insertRule(name, null, permissionListJson, userListJson, oid, now);
        }
        dsPermissionRepository.deletePermissionsByRule(ruleId);
        for (Map<String, Object> entry : entries) {
            String type = Rows.str(entry, "type");
            if (!"row".equals(type) && !"column".equals(type)) {
                throw new BadRequestException(messageResolver.get("perm.ds.type.invalid", type));
            }
            String permissions = Rows.str(entry, "permissions");
            String expressionTree = Rows.str(entry, "expression_tree");
            if (permissions == null) {
                permissions = "[]";
            }
            if (expressionTree == null) {
                expressionTree = "{}";
            }
            dsPermissionRepository.insertPermission(ruleId, Rows.str(entry, "name"), type,
                    Rows.lngOrNull(entry, "ds_id"), Rows.lngOrNull(entry, "table_id"), expressionTree,
                    permissions, now);
        }
        return ruleId;
    }

    /** POST /ds_permission/delete/{id}。 */
    @Transactional
    @AuditLog(module = AuditModule.PERMISSION, operation = AuditOperationType.DELETE)
    public void delete(long id) {
        currentUserService.requireWsAdmin();
        if (!dsPermissionRepository.findRule(id).isPresent()) {
            throw new BadRequestException(messageResolver.get("perm.ds.rule.not.exist"));
        }
        dsPermissionRepository.deletePermissionsByRule(id);
        dsPermissionRepository.deleteRule(id);
    }
}
