package com.xiongx9527.askdata.datasource.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.permission.DsGrantService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表关系（对齐 SQLBot apps/datasource/api/table_relation.py）：
 * save/get 数据源的表关系图（前端 TableRelationship.vue 的 jointjs cells JSON，
 * 落 core_datasource.table_relation，SQLBot 为 JSONB，此处 JSON 文本）。
 */
@Service
public class TableRelationService {

    private final DatasourceRepository datasourceRepository;

    private final ObjectMapper objectMapper;

    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    private final CurrentUserService currentUserService;

    private final DsGrantService dsGrantService;

    /** A6：Spring 注入完整依赖（含 ws_admin 与资源授权）。 */
    @Autowired
    public TableRelationService(DatasourceRepository datasourceRepository, ObjectMapper objectMapper,
                                com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver,
                                CurrentUserService currentUserService, DsGrantService dsGrantService) {
        this.datasourceRepository = datasourceRepository;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
        this.currentUserService = currentUserService;
        this.dsGrantService = dsGrantService;
    }

    /** 兼容旧构造（测试用，不带授权依赖，仅做存在性校验）。 */
    public TableRelationService(DatasourceRepository datasourceRepository, ObjectMapper objectMapper,
                                com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this(datasourceRepository, objectMapper, messageResolver, null, null);
    }

    /** A6：要求 ws_admin 且数据源通过资源授权（SQLBot @require_permissions(type='ds')）。 */
    private void checkAuthorized(long dsId) {
        if (currentUserService == null || dsGrantService == null) {
            // 测试/旧路径降级：未注入授权依赖时不强制（保持既有行为）
            return;
        }
        Map<String, Object> ds = datasourceRepository.findById(dsId)
                .orElseThrow(() -> new DatasourceException("no datasource"));
        long oid = Rows.lng(ds, "oid", 0L);
        currentUserService.requireWsAdmin();
        dsGrantService.checkUsable(oid, currentUserService.uid(),
                currentUserService.isAdmin(currentUserService.currentUser()), dsId);
    }

    /** POST /table_relation/save/{ds_id}（A6：要求 ws_admin + 资源授权）。 */
    public void save(long dsId, List<Map<String, Object>> relation) {
        checkAuthorized(dsId);
        String json;
        try {
            json = objectMapper.writeValueAsString(relation == null ? new ArrayList<>() : relation);
        } catch (Exception e) {
            throw new DatasourceException("Serialize table relation failed: " + e.getMessage());
        }
        datasourceRepository.updateTableRelation(dsId, json);
    }

    /** POST /table_relation/get/{ds_id}（A6：要求 ws_admin + 资源授权）。 */
    public List<Object> get(long dsId) {
        checkAuthorized(dsId);
        String json = datasourceRepository.findTableRelation(dsId);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** ds 存在性校验（controller 层 400 语义）。 */
    public void requireExists(long dsId) {
        if (!datasourceRepository.exists(dsId)) {
            throw new BadRequestException(messageResolver.get("ds.not.found", dsId));
        }
    }
}
