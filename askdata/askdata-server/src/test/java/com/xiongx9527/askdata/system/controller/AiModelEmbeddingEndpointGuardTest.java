package com.xiongx9527.askdata.system.controller;

import com.xiongx9527.askdata.ai.EmbeddingIndexService;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.web.ForbiddenException;
import com.xiongx9527.askdata.system.service.AiModelService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A8.9：向量索引两个接口仅管理员可调用，且纳入审计。
 */
class AiModelEmbeddingEndpointGuardTest {

    private final AiModelService aiModelService = mock(AiModelService.class);

    private final EmbeddingIndexService embeddingIndexService = mock(EmbeddingIndexService.class);

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);

    private final AiModelController controller =
            new AiModelController(aiModelService, embeddingIndexService, currentUserService);

    @Test
    void nonAdminIsRejectedByStatusEndpoint() {
        org.mockito.Mockito.doThrow(new ForbiddenException("forbidden"))
                .when(currentUserService).requireAdmin();

        assertThrows(ForbiddenException.class, controller::embeddingStatus);
        verify(embeddingIndexService, never()).status();
    }

    @Test
    void nonAdminIsRejectedByRebuildEndpoint() {
        org.mockito.Mockito.doThrow(new ForbiddenException("forbidden"))
                .when(currentUserService).requireAdmin();

        assertThrows(ForbiddenException.class, controller::embeddingRebuild);
        verify(embeddingIndexService, never()).rebuild();
    }

    @Test
    void adminCanReadStatusAndTriggerRebuild() {
        when(embeddingIndexService.status()).thenReturn(new LinkedHashMap<>());
        when(embeddingIndexService.rebuild()).thenReturn(new LinkedHashMap<>());

        assertNotNull(controller.embeddingStatus());
        assertNotNull(controller.embeddingRebuild());
        verify(embeddingIndexService).status();
        verify(embeddingIndexService).rebuild();
    }

    @Test
    void bothEndpointsAreAudited() throws Exception {
        Method status = AiModelController.class.getMethod("embeddingStatus");
        AuditLog statusLog = status.getAnnotation(AuditLog.class);
        assertNotNull(statusLog, "查询向量索引状态需记录审计");
        assertEquals(AuditModule.AI_MODEL, statusLog.module());
        assertEquals(AuditOperationType.VIEW, statusLog.operation());

        Method rebuild = AiModelController.class.getMethod("embeddingRebuild");
        AuditLog rebuildLog = rebuild.getAnnotation(AuditLog.class);
        assertNotNull(rebuildLog, "触发向量重建需记录审计");
        assertEquals(AuditModule.AI_MODEL, rebuildLog.module());
        assertEquals(AuditOperationType.UPDATE, rebuildLog.operation());
    }
}
