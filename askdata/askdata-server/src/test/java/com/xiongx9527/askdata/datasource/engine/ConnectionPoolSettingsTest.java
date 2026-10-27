package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A21 连接池参数证据：数据源配置的 poolSize / max_overflow / pool_timeout 生效。
 *
 * <ul>
 *   <li>poolSize：常驻连接数，归还后保留在池内复用；</li>
 *   <li>max_overflow：并发高峰可额外创建的连接数，归还超出 poolSize 的连接被关闭；</li>
 *   <li>pool_timeout：池满时等待空闲连接的最长时间，超时抛错而非无限阻塞。</li>
 * </ul>
 */
class ConnectionPoolSettingsTest {

    /** 计数工厂：记录真实创建的底层连接数与关闭数（Mockito 空心连接）。 */
    private static final class CountingFactory implements ConnectionPoolManager.ConnectionFactory {

        private final AtomicInteger created = new AtomicInteger();

        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public Connection create() {
            created.incrementAndGet();
            Connection connection = Mockito.mock(Connection.class);
            try {
                Mockito.when(connection.isClosed()).thenReturn(false);
                Mockito.when(connection.isValid(Mockito.anyInt())).thenReturn(true);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            try {
                Mockito.doAnswer(invocation -> {
                    closed.incrementAndGet();
                    Mockito.when(connection.isClosed()).thenReturn(true);
                    return null;
                }).when(connection).close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return connection;
        }
    }

    @Test
    void poolSizeLimitsResidentConnections() throws SQLException {
        ConnectionPoolManager manager = new ConnectionPoolManager();
        CountingFactory factory = new CountingFactory();
        ConnectionPoolManager.PoolSettings settings =
                new ConnectionPoolManager.PoolSettings(2, 0, 1000L);

        Connection first = manager.borrow("ds:1", factory, settings);
        Connection second = manager.borrow("ds:1", factory, settings);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, factory.created.get(), "poolSize=2 时最多常驻 2 条连接");
        // 第三条借出应在超时后失败（poolSize=2, maxOverflow=0）
        assertThrows(SQLException.class, () -> manager.borrow("ds:1", factory, settings));

        first.close();
        second.close();
        // 归还后连接保留在池内复用（不再新建）
        Connection reused = manager.borrow("ds:1", factory, settings);
        assertNotNull(reused);
        assertEquals(2, factory.created.get(), "归还后应复用池内连接");
        reused.close();
    }

    @Test
    void maxOverflowAllowsBurstAndClosesExcessOnRelease() throws SQLException {
        ConnectionPoolManager manager = new ConnectionPoolManager();
        CountingFactory factory = new CountingFactory();
        ConnectionPoolManager.PoolSettings settings =
                new ConnectionPoolManager.PoolSettings(1, 2, 1000L);

        Connection core = manager.borrow("ds:2", factory, settings);
        Connection overflowA = manager.borrow("ds:2", factory, settings);
        Connection overflowB = manager.borrow("ds:2", factory, settings);
        assertEquals(3, factory.created.get(), "poolSize + maxOverflow = 3 条");
        // 第 4 条借出超时失败
        assertThrows(SQLException.class, () -> manager.borrow("ds:2", factory, settings));

        overflowA.close();
        overflowB.close();
        core.close();
        // 超出 poolSize 的溢出连接在归还时被关闭，只保留 1 条常驻
        assertEquals(2, factory.closed.get(), "两条溢出连接归还时应被关闭");
        Connection again = manager.borrow("ds:2", factory, settings);
        assertNotNull(again);
        assertEquals(3, factory.created.get(), "常驻连接仍在池内，无需新建");
        again.close();
    }

    @Test
    void poolTimeoutWaitsForRelease() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager();
        CountingFactory factory = new CountingFactory();
        // poolSize=1, maxOverflow=0, 等待 5 秒：另一线程 300ms 后归还，应能借到
        ConnectionPoolManager.PoolSettings settings =
                new ConnectionPoolManager.PoolSettings(1, 0, 5000L);

        Connection first = manager.borrow("ds:3", factory, settings);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                first.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        releaser.start();
        long start = System.currentTimeMillis();
        Connection second = manager.borrow("ds:3", factory, settings);
        long waited = System.currentTimeMillis() - start;
        releaser.join(2000L);
        assertNotNull(second);
        assertTrue(waited >= 250L, "应等待归还而不是立即失败，实际等待 " + waited + "ms");
        assertEquals(1, factory.created.get(), "等待后复用同一条连接");
        second.close();
    }

    @Test
    void recycleClosesExpiredIdleConnectionOnBorrow() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager();
        CountingFactory factory = new CountingFactory();
        // recycle=50ms：归还后等待超过该年龄，下次借出必须重建连接
        ConnectionPoolManager.PoolSettings settings =
                new ConnectionPoolManager.PoolSettings(1, 0, 1000L, 50L);

        Connection first = manager.borrow("ds:4", factory, settings);
        first.close();
        assertEquals(1, factory.created.get());
        Thread.sleep(120L);

        Connection recycled = manager.borrow("ds:4", factory, settings);
        assertNotNull(recycled);
        assertEquals(2, factory.created.get(), "超龄空闲连接应被关闭并重建");
        assertEquals(1, factory.closed.get(), "超龄连接应在借出时关闭");
        recycled.close();
    }

    @Test
    void settingsDerivedFromDatasourceConf() {
        DatasourceConf conf = new DatasourceConf();
        conf.setPoolSize(3);
        conf.setMaxOverflow(7);
        conf.setPoolTimeout(12);
        ConnectionPoolManager.PoolSettings settings = ConnectionPoolManager.PoolSettings.of(conf);
        assertEquals(3, settings.coreSize());
        assertEquals(7, settings.maxOverflow());
        assertEquals(12_000L, settings.timeoutMs());
        assertEquals(3_600_000L, settings.recycleMs());
        conf.setRecycle(600);
        assertEquals(600_000L, ConnectionPoolManager.PoolSettings.of(conf).recycleMs());

        // 缺省/非法值回落默认
        DatasourceConf empty = new DatasourceConf();
        empty.setPoolSize(0);
        empty.setMaxOverflow(-1);
        empty.setPoolTimeout(0);
        ConnectionPoolManager.PoolSettings fallback = ConnectionPoolManager.PoolSettings.of(empty);
        assertEquals(ConnectionPoolManager.DEFAULT_POOL_SIZE, fallback.coreSize());
        assertEquals(ConnectionPoolManager.DEFAULT_MAX_OVERFLOW, fallback.maxOverflow());
        assertEquals(ConnectionPoolManager.DEFAULT_POOL_TIMEOUT_MS, fallback.timeoutMs());
        assertEquals(ConnectionPoolManager.PoolSettings.defaults(),
                ConnectionPoolManager.PoolSettings.of(null));
    }

    @Test
    void snakeCaseJsonKeysAreAccepted() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        DatasourceConf conf = mapper.readValue(
                "{\"poolSize\":4,\"max_overflow\":6,\"pool_timeout\":9}", DatasourceConf.class);
        ConnectionPoolManager.PoolSettings settings = ConnectionPoolManager.PoolSettings.of(conf);
        assertEquals(4, settings.coreSize());
        assertEquals(6, settings.maxOverflow());
        assertEquals(9_000L, settings.timeoutMs());
    }
}
