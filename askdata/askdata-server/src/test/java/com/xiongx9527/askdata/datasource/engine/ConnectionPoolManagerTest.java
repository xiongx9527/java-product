package com.xiongx9527.askdata.datasource.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接池管理器单测（对齐 SQLBot {@code apps/db/db.py} 的 ConnectionPoolManager）。
 *
 * <p>覆盖：连接复用、LRU 淘汰并 dispose 底层连接、移除池时关闭连接、
 * closeAll 关闭全部池、坏连接不入池。</p>
 */
class ConnectionPoolManagerTest {

    /** 记录创建/关闭次数的假连接。 */
    private static class FakeConnection implements java.lang.reflect.InvocationHandler {

        final boolean valid;

        boolean closed;

        FakeConnection(boolean valid) {
            this.valid = valid;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("isValid".equals(name)) {
                return valid && !closed;
            }
            if ("toString".equals(name)) {
                return "FakeConnection";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return null;
        }

        static Connection create(boolean valid) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    FakeConnection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    new FakeConnection(valid));
        }

        /** 记录型连接（供断言 close 次数与有效性）。 */
        static Connection tracked(List<FakeConnection> registry, boolean valid) {
            FakeConnection handler = new FakeConnection(valid);
            registry.add(handler);
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    FakeConnection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }
    }

    @Test
    void borrowedConnectionIsReusedOnSecondBorrow() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        AtomicInteger created = new AtomicInteger();
        ConnectionPoolManager.ConnectionFactory factory = () -> {
            created.incrementAndGet();
            return FakeConnection.create(true);
        };

        Connection first = manager.borrow("ds:1", factory);
        first.close();
        Connection second = manager.borrow("ds:1", factory);

        assertNotNull(second);
        assertEquals(1, created.get(), "归还后应复用同一条连接，不应新建");
        assertEquals(1, manager.poolCount());
        second.close();
    }

    @Test
    void lruEvictionDisposesOldestPool() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(2);
        List<FakeConnection> created = new ArrayList<>();
        ConnectionPoolManager.ConnectionFactory factory = () -> FakeConnection.tracked(created, true);

        Connection a = manager.borrow("ds:1", factory);
        Connection b = manager.borrow("ds:2", factory);
        a.close();
        b.close();
        // 第三个池触发 LRU 淘汰（ds:1 最久未用）
        Connection c = manager.borrow("ds:3", factory);
        c.close();

        assertEquals(2, manager.poolCount());
        assertTrue(created.get(0).closed, "被淘汰的池的底层连接必须被关闭（dispose）");
        assertTrue(!created.get(1).closed, "未被淘汰的连接不应被关闭");
    }

    @Test
    void removePoolClosesUnderlyingConnection() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        List<FakeConnection> created = new ArrayList<>();
        ConnectionPoolManager.ConnectionFactory factory = () -> FakeConnection.tracked(created, true);

        Connection connection = manager.borrow("ds:1", factory);
        connection.close();
        manager.removePool("ds:1");

        assertEquals(0, manager.poolCount());
        assertTrue(created.get(0).closed, "移除池时必须关闭底层连接");
    }

    @Test
    void removeDatasourceClosesPool() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        List<FakeConnection> created = new ArrayList<>();
        ConnectionPoolManager.ConnectionFactory factory = () -> FakeConnection.tracked(created, true);
        Connection connection = manager.borrow(ConnectionPoolManager.poolKeyOf(42L), factory);
        connection.close();
        manager.removeDatasource(42L);
        assertEquals(0, manager.poolCount());
        assertTrue(created.get(0).closed);
    }

    @Test
    void closeAllClosesEveryPool() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        List<FakeConnection> created = new ArrayList<>();
        ConnectionPoolManager.ConnectionFactory factory = () -> FakeConnection.tracked(created, true);
        Connection a = manager.borrow("ds:1", factory);
        Connection b = manager.borrow("ds:2", factory);
        a.close();
        b.close();
        manager.closeAll();

        assertEquals(0, manager.poolCount());
        for (FakeConnection connection : created) {
            assertTrue(connection.closed);
        }
    }

    @Test
    void invalidConnectionIsNotReturnedToPool() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        AtomicInteger created = new AtomicInteger();
        ConnectionPoolManager.ConnectionFactory factory = () -> {
            created.incrementAndGet();
            return FakeConnection.create(false);
        };
        Connection connection = manager.borrow("ds:1", factory);
        connection.close();
        // 坏连接被丢弃 → 下次借出时重建
        Connection again = manager.borrow("ds:1", factory);
        again.close();
        assertEquals(2, created.get());
    }

    @Test
    void proxyCloseDoesNotReallyCloseUnderlyingConnection() throws Exception {
        ConnectionPoolManager manager = new ConnectionPoolManager(10);
        List<FakeConnection> created = new ArrayList<>();
        ConnectionPoolManager.ConnectionFactory factory = () -> FakeConnection.tracked(created, true);
        Connection connection = manager.borrow("ds:1", factory);
        connection.close();
        assertTrue(!created.get(0).closed, "代理的 close() 表示归还，不应真正关闭底层连接");
    }

    @Test
    void poolKeysAreDistinctPerDatasourceAndConfig() {
        assertEquals("ds:1", ConnectionPoolManager.poolKeyOf(1L));
        assertEquals("cfg:jdbc:mysql://h/db#root",
                ConnectionPoolManager.poolKeyOfConfig("jdbc:mysql://h/db", "root"));
    }

    @Test
    void defaultMaxPoolsIs500() {
        assertEquals(500, ConnectionPoolManager.DEFAULT_MAX_POOLS);
        assertEquals(500, new ConnectionPoolManager().maxPoolsForTest());
    }
}
