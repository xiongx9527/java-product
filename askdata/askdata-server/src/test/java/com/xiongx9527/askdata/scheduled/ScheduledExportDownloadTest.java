package com.xiongx9527.askdata.scheduled;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.support.MetadbMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 定时任务导出文件的跨实例下载（验收 A6）：
 * 运行记录里的 export_path 为共享存储引用时，任意实例都能取回；历史本地路径仍可读。
 */
class ScheduledExportDownloadTest {

    @TempDir
    Path tempDir;

    private ScheduledTaskRepository repository;

    private ScheduledTaskService service;

    private AskdataFileStorage storage;

    private long runId;

    private long taskId;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sched_export_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            MetadbMigration.migrate(connection);
        }
        repository = new ScheduledTaskRepository(jdbc, new IdGenerator());

        ScheduledTask task = new ScheduledTask();
        task.setName("export");
        task.setOid(1L);
        task.setCreateBy(1L);
        task.setQuestion("q");
        task.setEnabled(false);
        task.setCreateTime(System.currentTimeMillis());
        task.setUpdateTime(System.currentTimeMillis());
        taskId = repository.insert(task);

        CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUser.currentUser()).thenReturn(Rows.of("id", 1L, "oid", 1L));
        Mockito.when(currentUser.isAdmin(Mockito.any())).thenReturn(true);
        Mockito.when(currentUser.oidOf(Mockito.any())).thenReturn(1L);

        storage = Mockito.mock(AskdataFileStorage.class);
        Mockito.when(storage.enabled()).thenReturn(true);

        service = new ScheduledTaskService(repository, Mockito.mock(ScheduledTaskExecutor.class),
                currentUser, new MessageResolver(new StaticMessageSource()));
        service.setFileStorage(storage);
    }

    private long insertRun(String exportPath) {
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setTaskId(taskId);
        run.setOid(1L);
        run.setTriggerType(ScheduledTaskRun.TRIGGER_SCHEDULE);
        run.setStatus(ScheduledTaskRun.STATUS_SUCCESS);
        run.setExportPath(exportPath);
        run.setStartedAt(System.currentTimeMillis());
        run.setCreateTime(System.currentTimeMillis());
        return repository.insertRun(run);
    }

    @Test
    void storageReferenceIsReadableFromAnyInstance() {
        String url = "http://minio:9000/askdata/scheduled/orders_1.csv";
        runId = insertRun(url);
        Mockito.when(storage.info(eq(url)))
                .thenReturn(new org.dromara.x.file.storage.core.FileInfo());
        Mockito.when(storage.read(eq(url)))
                .thenReturn("id,name\n1,a\n".getBytes(StandardCharsets.UTF_8));

        ScheduledTaskService.ExportFile file = service.exportFile(runId);
        assertEquals("orders_1.csv", file.filename());
        assertEquals(12, file.content().length);
    }

    @Test
    void legacyLocalPathStillReadable() throws Exception {
        Path local = tempDir.resolve("legacy.csv");
        Files.write(local, "a,b\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        runId = insertRun(local.toString());
        Mockito.when(storage.info(anyString())).thenReturn(null);

        ScheduledTaskService.ExportFile file = service.exportFile(runId);
        assertEquals("legacy.csv", file.filename());
        assertEquals("a,b\n", new String(file.content(), StandardCharsets.UTF_8));
    }

    @Test
    void missingExportReportsReadableError() {
        runId = insertRun("/nonexistent/path/x.csv");
        Mockito.when(storage.info(anyString())).thenReturn(null);

        assertThrows(com.xiongx9527.askdata.common.web.BadRequestException.class,
                () -> service.exportFile(runId));
    }
}
