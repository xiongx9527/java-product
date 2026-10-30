package com.xiongx9527.askdata.datasource.service;

import com.xiongx9527.askdata.common.util.Lists;

import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.datasource.embedding.TableEmbeddingService;
import com.xiongx9527.askdata.datasource.engine.JdbcEngine;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A20：向量刷新时机——改表注释、改字段注释、改数据源配置后重算表/字段/数据源向量。
 */
class VectorRefreshTimingTest {

    private DatasourceService service;
    private TableEmbeddingService tableEmbeddingService;
    private DatasourceEmbeddingService datasourceEmbeddingService;
    private TableRepository tableRepository;
    private FieldRepository fieldRepository;

    @BeforeEach
    void setUp() {
        DatasourceRepository datasourceRepository = mock(DatasourceRepository.class);
        tableRepository = mock(TableRepository.class);
        fieldRepository = mock(FieldRepository.class);
        tableEmbeddingService = mock(TableEmbeddingService.class);
        datasourceEmbeddingService = mock(DatasourceEmbeddingService.class);
        service = new DatasourceService(datasourceRepository, tableRepository, fieldRepository,
                null, mock(JdbcEngine.class), mock(CurrentUserService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), null,
                tableEmbeddingService, null, datasourceEmbeddingService);
    }

    @Test
    void editTableCommentRecomputesTableVectors() {
        Map<String, Object> table = new HashMap<>();
        table.put("id", 11L);
        table.put("ds_id", 5L);
        when(tableRepository.findById(11L)).thenReturn(Optional.of(table));

        service.editTable(rows("id", 11L, "ds_id", 5L, "custom_comment", "订单表"));

        verify(tableRepository).updateCheckedAndComment(eq(11L), Mockito.anyBoolean(), eq("订单表"));
        verify(tableEmbeddingService).backfill(5L);
        verify(datasourceEmbeddingService, never()).saveEmbedding(anyLong());
    }

    @Test
    void editFieldCommentRecomputesTableVectors() {
        Map<String, Object> field = new HashMap<>();
        field.put("id", 21L);
        field.put("ds_id", 5L);
        field.put("table_id", 11L);
        when(fieldRepository.listByTableId(11L)).thenReturn(Lists.of(field));

        service.editField(rows("id", 21L, "table_id", 11L, "ds_id", 5L, "custom_comment", "订单号"));

        verify(fieldRepository).updateCheckedAndComment(eq(21L), Mockito.anyBoolean(), eq("订单号"));
        verify(tableEmbeddingService).backfill(5L);
    }

    @Test
    void datasourceConfigChangeRecomputesDatasourceVector() {
        Map<String, Object> ds = new HashMap<>();
        ds.put("id", 5L);
        ds.put("name", "旧名");
        ds.put("type", "mysql");
        DatasourceRepository datasourceRepository = mock(DatasourceRepository.class);
        when(datasourceRepository.findById(5L)).thenReturn(Optional.of(ds));
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.oidOf(Mockito.any())).thenReturn(1L);
        DatasourceService svc = new DatasourceService(datasourceRepository, tableRepository, fieldRepository,
                null, mock(JdbcEngine.class), currentUserService,
                new com.fasterxml.jackson.databind.ObjectMapper(), null,
                tableEmbeddingService, null, datasourceEmbeddingService);

        // 配置用合法加密串，保证走到 update 落库后的向量重算时机
        String stored = com.xiongx9527.askdata.datasource.support.DatasourceCrypto.encodeForStore(
                "{\"host\":\"127.0.0.1\",\"port\":3306,\"database\":\"demo\"}");
        Map<String, Object> body = rows("id", 5L, "name", "新名", "type", "mysql",
                "configuration", stored);
        try {
            svc.update(body);
        } catch (RuntimeException e) {
            throw e;
        }
        verify(datasourceEmbeddingService).saveEmbedding(5L);
    }

    private static Map<String, Object> rows(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
