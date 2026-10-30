package com.xiongx9527.askdata.ai.vector;

import com.xiongx9527.askdata.common.util.Lists;

import com.xiongx9527.askdata.support.MetadbMigration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbVectorStore} 契约测试（纯 JUnit + H2）：DB embedding JSON 列 + 应用内余弦的回退路径。
 *
 * <p>该实现是 {@code askdata.vector-store.type=none}（缺省）及在线库不可达时的回落路径，
 * 必须与改动前的「DB 列 + 余弦」行为完全一致。</p>
 */
class DbVectorStoreTest {

    private JdbcTemplate jdbc;

    private DbVectorStore store;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:db_vector_store_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        store = new DbVectorStore(jdbc, objectMapper, "askdata");
    }

    @Test
    void collectionForUsesPrefixAndObjectType() {
        assertEquals("askdata", store.collectionPrefix());
        assertEquals("askdata-terminology", store.collectionFor("terminology"));
        assertEquals("askdata-core_table", store.collectionFor("core_table"));
    }

    @Test
    void isNotOnlineAndAlwaysHealthy() {
        assertFalse(store.isOnline());
        assertTrue(store.health());
    }

    @Test
    void upsertWritesEmbeddingColumnAndQueryRanksByCosine() {
        insertTerminology(1L, "订单");
        insertTerminology(2L, "用户");
        insertTerminology(3L, "商品");
        store.upsert("askdata-terminology", 1L, new double[] {1D, 0D});
        store.upsert("askdata-terminology", 2L, new double[] {0D, 1D});
        store.upsert("askdata-terminology", 3L, new double[] {1D, 1D});
        assertNotNull(jdbc.queryForObject("SELECT embedding FROM terminology WHERE id = 1", String.class));

        List<VectorStore.IdScore> hits = store.query("askdata-terminology", new double[] {1D, 0D}, 10, Lists.of(1L, 2L, 3L));
        assertEquals(3, hits.size());
        assertEquals(1L, hits.get(0).id(), "完全一致应排第一");
        assertEquals(3L, hits.get(1).id(), "部分一致次之");
        assertEquals(2L, hits.get(2).id(), "正交应垫底");
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertTrue(hits.get(1).score() > hits.get(2).score());
    }

    @Test
    void queryHonorsCandidateIdsAndTopK() {
        insertTerminology(1L, "a");
        insertTerminology(2L, "b");
        insertTerminology(3L, "c");
        store.upsert("askdata-terminology", 1L, new double[] {1D, 0D});
        store.upsert("askdata-terminology", 2L, new double[] {1D, 0D});
        store.upsert("askdata-terminology", 3L, new double[] {1D, 0D});
        List<VectorStore.IdScore> scoped =
                store.query("askdata-terminology", new double[] {1D, 0D}, 10, Lists.of(2L, 3L));
        assertEquals(2, scoped.size());
        assertTrue(scoped.stream().allMatch(h -> h.id() == 2L || h.id() == 3L));
        assertEquals(1, store.query("askdata-terminology", new double[] {1D, 0D}, 1, Lists.of(1L, 2L, 3L)).size());
    }

    @Test
    void queryWithoutCandidateIdsSkipsRowsWithoutVector() {
        insertTerminology(1L, "a");
        insertTerminology(2L, "b");
        store.upsert("askdata-terminology", 2L, new double[] {1D, 1D});
        List<VectorStore.IdScore> hits = store.query("askdata-terminology", new double[] {1D, 1D}, 10, null);
        assertEquals(1, hits.size());
        assertEquals(2L, hits.get(0).id());
    }

    @Test
    void deleteClearsEmbeddingColumn() {
        insertTerminology(1L, "a");
        store.upsert("askdata-terminology", 1L, new double[] {1D, 2D, 3D});
        store.delete("askdata-terminology", 1L);
        assertNull(jdbc.queryForObject("SELECT embedding FROM terminology WHERE id = 1", String.class));
        assertTrue(store.query("askdata-terminology", new double[] {1D, 2D, 3D}, 10, null).isEmpty());
    }

    @Test
    void coreTableUsesTableEmbeddingColumn() {
        insertTable(7L);
        store.upsert("askdata-core_table", 7L, new double[] {0.5D, 0.5D});
        String json = jdbc.queryForObject("SELECT table_embedding FROM core_table WHERE id = 7", String.class);
        assertEquals("0.5,0.5", json.replace("[", "").replace("]", ""));
        List<VectorStore.IdScore> hits = store.query("askdata-core_table", new double[] {1D, 0D}, 10, Lists.of(7L));
        assertEquals(1, hits.size());
        assertEquals(7L, hits.get(0).id());
    }

    @Test
    void queryNeverThrowsOnUnknownCollection() {
        assertTrue(store.query("askdata-no_such_table", new double[] {1D}, 10, Lists.of(1L)).isEmpty());
    }

    private void insertTerminology(long id, String word) {
        jdbc.update("INSERT INTO terminology (id, oid, pid, create_time, word, description, embedding, enabled) "
                + "VALUES (?, 1, NULL, ?, ?, '', NULL, TRUE)", id, System.currentTimeMillis(), word);
    }

    private void insertTable(long id) {
        jdbc.update("INSERT INTO core_table (id, ds_id, checked, table_name, table_comment, custom_comment, "
                + "table_embedding) VALUES (?, 42, TRUE, ?, 'c', NULL, NULL)", id, "t" + id);
    }
}
