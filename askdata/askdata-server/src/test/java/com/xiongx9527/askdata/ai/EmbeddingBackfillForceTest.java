package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.support.MysqlTestSupport;

import com.xiongx9527.askdata.common.lock.SingleWorkerGuard;
import com.xiongx9527.askdata.common.startup.EmbeddingBackfillRunner;
import com.xiongx9527.askdata.datasource.embedding.DatasourceEmbeddingService;
import com.xiongx9527.askdata.support.MetadbMigration;
import com.xiongx9527.askdata.terminology.TerminologyRecallService;
import com.xiongx9527.askdata.training.TrainingRecallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A8.2：向量只存在在线库，DB 的 embedding 列不再写入，因此回填不能再用「向量为空」筛选记录——
 * 启动回填与重建都按业务范围全量重算，差异只在范围（全空间 / 单空间）。
 */
class EmbeddingBackfillForceTest {

    private JdbcTemplate jdbc;

    private EmbeddingService embeddingService;

    private TerminologyRecallService terminologyRecall;

    private TrainingRecallService trainingRecall;

    private DatasourceEmbeddingService datasourceEmbedding;

    private EmbeddingBackfillRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = MysqlTestSupport.createSchema();
        embeddingService = mock(EmbeddingService.class);
        terminologyRecall = mock(TerminologyRecallService.class);
        trainingRecall = mock(TrainingRecallService.class);
        datasourceEmbedding = mock(DatasourceEmbeddingService.class);
        runner = new EmbeddingBackfillRunner(embeddingService, terminologyRecall, trainingRecall,
                datasourceEmbedding, mock(SingleWorkerGuard.class), jdbc, true);
    }

    private void seedTerminology() {
        jdbc.update("INSERT INTO terminology (id, oid, pid, word, embedding, enabled) VALUES (1, 10, NULL, '订单', '[0.1,0.2]', TRUE)");
        jdbc.update("INSERT INTO terminology (id, oid, pid, word, embedding, enabled) VALUES (2, 10, NULL, '客户', NULL, TRUE)");
    }

    @Test
    void backfillRecomputesEveryRowInScope() {
        seedTerminology();
        runner.backfillTerminology(null);

        List<Long> saved = capturedIds();
        assertTrue(saved.contains(1L), "DB 列不再保存向量，历史值不能作为跳过依据");
        assertTrue(saved.contains(2L));
    }

    @Test
    void backfillIsScopedToWorkspace() {
        seedTerminology();
        jdbc.update("INSERT INTO terminology (id, oid, pid, word, embedding, enabled) VALUES (3, 20, NULL, '库存', '[0.3]', TRUE)");

        runner.backfillTerminology(10L);

        List<Long> saved = capturedIds();
        assertTrue(saved.contains(1L));
        assertTrue(!saved.contains(3L), "按工作空间重建不得越界");
    }

    @Test
    void rebuildWorkspaceCoversTrainingDatasourceAndTables() {
        seedTerminology();
        jdbc.update("INSERT INTO data_training (id, oid, question, embedding, enabled) VALUES (11, 10, 'q', '[0.1]', TRUE)");
        jdbc.update("INSERT INTO core_datasource (id, oid, name) VALUES (21, 10, 'ds')");

        runner.rebuildWorkspace(10L);

        verify(trainingRecall, atLeastOnce()).saveEmbeddings(anyList());
        verify(datasourceEmbedding, atLeastOnce()).saveEmbedding(21L);
        verify(terminologyRecall, atLeastOnce()).saveEmbeddings(anyList());
    }

    @Test
    void noRowsMeansNoUpstreamCall() {
        runner.backfillTerminology(999L);
        assertTrue(capturedIds().isEmpty(), "无待处理记录时不得调用上游");
    }

    /** 汇总所有 saveEmbeddings 调用的 id（父术语与同义词两次调用）。 */
    @SuppressWarnings("unchecked")
    private List<Long> capturedIds() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(terminologyRecall, atLeastOnce()).saveEmbeddings(captor.capture());
        List<Long> ids = new java.util.ArrayList<>();
        for (List<Long> batch : captor.getAllValues()) {
            ids.addAll(batch);
        }
        return ids;
    }
}
