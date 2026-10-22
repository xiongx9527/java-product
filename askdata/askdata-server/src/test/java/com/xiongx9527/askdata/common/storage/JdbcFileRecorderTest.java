package com.xiongx9527.askdata.common.storage;

import com.xiongx9527.askdata.common.util.IdGenerator;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 落库 FileRecorder：save（分配 id 并持久化）/ getByUrl / update / delete(url|id)  round-trip，
 * 验证凭 url 反查不依赖进程内存（重启/多实例可用）。
 */
class JdbcFileRecorderTest {

    private EmbeddedDatabase db;
    private JdbcFileRecorder recorder;
    private long idSeq = 100;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE file_storage (id BIGINT PRIMARY KEY, url VARCHAR(2000), platform VARCHAR(64), "
                + "path VARCHAR(1000), filename VARCHAR(512), original_filename VARCHAR(512), "
                + "content_type VARCHAR(255), size BIGINT, create_time BIGINT)");

        IdGenerator idGen = mock(IdGenerator.class);
        when(idGen.nextId()).thenAnswer(inv -> idSeq++);
        recorder = new JdbcFileRecorder(new FileStorageRecordRepository(jdbc, idGen));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    private FileInfo sample() {
        FileInfo fi = new FileInfo();
        fi.setUrl("chart/abc.png");
        fi.setPlatform("local");
        fi.setPath("chart");
        fi.setFilename("abc.png");
        fi.setOriginalFilename("abc.png");
        fi.setContentType("image/png");
        fi.setSize(42L);
        return fi;
    }

    @Test
    void saveAssignsIdAndGetByUrlRetrieves() {
        FileInfo fi = sample();
        assertTrue(recorder.save(fi));
        assertNotNull(fi.getId(), "recorder 应分配 id");

        FileInfo got = recorder.getByUrl("chart/abc.png");
        assertNotNull(got);
        assertEquals("chart", got.getPath());
        assertEquals("abc.png", got.getFilename());
        assertEquals("image/png", got.getContentType());
        assertEquals(42L, got.getSize());
    }

    @Test
    void updatePersistsChanges() {
        FileInfo fi = sample();
        recorder.save(fi);
        fi.setContentType("image/jpeg");
        recorder.update(fi);
        assertEquals("image/jpeg", recorder.getByUrl("chart/abc.png").getContentType());
    }

    @Test
    void deleteByUrlRemovesRecord() {
        recorder.save(sample());
        assertTrue(recorder.delete("chart/abc.png"));
        assertNull(recorder.getByUrl("chart/abc.png"));
    }

    @Test
    void deleteByIdRemovesRecord() {
        FileInfo fi = sample();
        recorder.save(fi);
        assertTrue(recorder.delete(fi.getId()));
        assertNull(recorder.getByUrl("chart/abc.png"));
    }

    @Test
    void deleteUnknownReturnsFalse() {
        assertFalse(recorder.delete("nope"));
        assertFalse(recorder.delete(null));
    }

    @Test
    void getByUrlUnknownReturnsNull() {
        assertNull(recorder.getByUrl("missing.png"));
    }
}
