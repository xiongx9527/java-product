package com.xiongx9527.askdata.datasource.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.datasource.DatasourceException;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
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

    public TableRelationService(DatasourceRepository datasourceRepository, ObjectMapper objectMapper) {
        this.datasourceRepository = datasourceRepository;
        this.objectMapper = objectMapper;
    }

    /** POST /table_relation/save/{ds_id}。 */
    public void save(long dsId, List<Map<String, Object>> relation) {
        datasourceRepository.findById(dsId)
                .orElseThrow(() -> new DatasourceException("no datasource"));
        String json;
        try {
            json = objectMapper.writeValueAsString(relation == null ? new ArrayList<>() : relation);
        } catch (Exception e) {
            throw new DatasourceException("Serialize table relation failed: " + e.getMessage());
        }
        datasourceRepository.updateTableRelation(dsId, json);
    }

    /** POST /table_relation/get/{ds_id}：无关系时返回空列表（对齐 SQLBot）。 */
    public List<Object> get(long dsId) {
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
            throw new BadRequestException("Datasource with id " + dsId + " not found");
        }
    }
}
