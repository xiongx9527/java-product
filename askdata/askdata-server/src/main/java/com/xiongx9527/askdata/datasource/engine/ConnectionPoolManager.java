package com.xiongx9527.askdata.datasource.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源连接池管理器（对齐 SQLBot {@code apps/db/db.py} 的 ConnectionPoolManager）。
 *
 * <p><b>语义</b>：按数据源 id 缓存连接池，LRU 淘汰（上限可配，默认 500），
 * 淘汰/移除时安全关闭（dispose）底层连接；数据源更新或删除时移除对应池；
 * 应用关闭时关闭全部池。</p>
 *
 * <p><b>实现取舍</b>：SQLBot 用 SQLAlchemy Engine（池内含连接池 + 会话工厂）。
 * askdata 用裸 JDBC（{@link java.sql.Connection}），故此处实现等价的
 * <b>单连接池</b>：每个池键缓存一条已建立的连接。借出时连接被「代理」包装，
 * 调用方的 {@code close()}（try-with-resources）被拦截为<b>归还</b>而非真正关闭，
 * 因此既兼容现有 try-with-resources 写法，又能让连接在多次查询间复用。</p>
 *
 * <p><b>连接失效</b>：归还时校验有效性（{@code isClosed} / {@code isValid}），
 * 坏连接直接丢弃，下一次借出时重建，不把坏连接放回池中。</p>
 *
 * <p><b>池键</b>：已入库数据源用 {@code "ds:<id>"}（数据源更新/删除时可精确移除）；
 * 尚未入库的临时配置（连通检查等）用 {@code "cfg:<url>#<user>"} 派生键。</p>
 */
public class ConnectionPoolManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolManager.class);

    /** 默认池上限（对齐 SQLBot max_pools=500）。 */
    public static final int DEFAULT_MAX_POOLS = 500;

    private final int maxPools;

    private final Object lock = new Object();

    /** 池键 → 连接池（LRU：accessOrder=true，最近使用在末尾）。 */
    private final Map<String, PooledConnection> pools = new LinkedHashMap<>(16, 0.75F, true);

    /** 构造（默认上限 500）。 */
    public ConnectionPoolManager() {
        this.maxPools = DEFAULT_MAX_POOLS;
    }

    /** 构造（显式上限）。 */
    public ConnectionPoolManager(int maxPools) {
        this.maxPools = maxPools <= 0 ? DEFAULT_MAX_POOLS : maxPools;
    }

    // ------------------------------------------------------------------
    // 借出 / 归还
    // ------------------------------------------------------------------

    /**
     * 从池中借出一条连接（无可用连接时新建）。
     *
     * @param poolKey 池键（数据源 id 派生或配置派生）
     * @param factory 连接工厂（新建连接时调用；已有可用连接时不调用）
     * @return 代理连接（其 {@code close()} 表示归还到池）
     * @throws SQLException 连接创建失败
     */
    public Connection borrow(String poolKey, ConnectionFactory factory) throws SQLException {
        PooledConnection pool;
        Connection raw;
        synchronized (lock) {
            pool = pools.get(poolKey);
            if (pool != null) {
                raw = pool.take();
                if (raw != null) {
                    // 命中：LinkedHashMap(accessOrder) 的 get() 已刷新 LRU 位置
                    return wrap(poolKey, pool, raw);
                }
            }
            // 无可用连接：新建
            raw = factory.create();
            if (pool == null) {
                pool = new PooledConnection();
                pools.put(poolKey, pool);
                evictIfNeeded();
            }
            pool.give(raw);
            Connection handed = pool.take();
            if (handed == null) {
                throw new SQLException("Unable to acquire connection for pool " + poolKey);
            }
            return wrap(poolKey, pool, handed);
        }
    }

    /**
     * 显式归还连接（代理 {@code close()} 也会走这里）。
     *
     * <p>归还时校验有效性；坏连接直接丢弃（不缓存），下次借出时重建。</p>
     */
    public void release(String poolKey, Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            PooledConnection pool = pools.get(poolKey);
            if (pool == null) {
                closeQuietly(connection);
                return;
            }
            if (!isUsable(connection)) {
                pool.discard(connection);
                return;
            }
            pool.give(connection);
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 移除指定池并关闭其底层连接（数据源更新/删除时调用）。 */
    public void removePool(String poolKey) {
        List<Connection> toClose = new ArrayList<>();
        synchronized (lock) {
            PooledConnection pool = pools.remove(poolKey);
            if (pool != null) {
                toClose.addAll(pool.drain());
            }
        }
        for (Connection connection : toClose) {
            closeQuietly(connection);
        }
        if (!toClose.isEmpty()) {
            log.info("连接池已关闭并移除：pool={}", poolKey);
        }
    }

    /** 移除数据源对应的池（数据源更新/删除时调用）。 */
    public void removeDatasource(long datasourceId) {
        removePool(poolKeyOf(datasourceId));
    }

    /** 关闭全部池（应用关闭时调用）。 */
    public void closeAll() {
        List<Connection> toClose = new ArrayList<>();
        synchronized (lock) {
            for (PooledConnection pool : pools.values()) {
                toClose.addAll(pool.drain());
            }
            pools.clear();
        }
        for (Connection connection : toClose) {
            closeQuietly(connection);
        }
        if (!toClose.isEmpty()) {
            log.info("连接池全部关闭：{} 条连接", toClose.size());
        }
    }

    /** 当前池数量（供测试/诊断）。 */
    public int poolCount() {
        synchronized (lock) {
            return pools.size();
        }
    }

    /** 池上限（供测试/诊断）。 */
    public int maxPoolsForTest() {
        return maxPools;
    }

    /** 当前缓存的连接总数（供测试/诊断）。 */
    public int connectionCount() {
        synchronized (lock) {
            int total = 0;
            for (PooledConnection pool : pools.values()) {
                total += pool.size();
            }
            return total;
        }
    }

    /** 数据源 id → 池键。 */
    public static String poolKeyOf(long datasourceId) {
        return "ds:" + datasourceId;
    }

    /** 临时配置 → 池键（未入库的数据源用配置派生，保证同一配置复用同一连接）。 */
    public static String poolKeyOfConfig(String jdbcUrl, String username) {
        return "cfg:" + jdbcUrl + "#" + (username == null ? "" : username);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 代理连接：拦截 close() 改为归还；其余方法透传。 */
    private Connection wrap(String poolKey, PooledConnection pool, Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new PooledHandler(this, poolKey, pool, raw));
    }

    /** 代理调用处理���（拦截 close / isClosed / isValid / unwrap）。 */
    private static final class PooledHandler implements InvocationHandler {

        private final ConnectionPoolManager manager;

        private final String poolKey;

        private final PooledConnection pool;

        private final Connection raw;

        private boolean released;

        private PooledHandler(ConnectionPoolManager manager, String poolKey, PooledConnection pool,
                              Connection raw) {
            this.manager = manager;
            this.poolKey = poolKey;
            this.pool = pool;
            this.raw = raw;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                if (!released) {
                    released = true;
                    manager.release(poolKey, raw);
                }
                return null;
            }
            if ("isClosed".equals(name)) {
                // 归还后连接仍可用（未真正关闭）
                return released ? Boolean.TRUE : Boolean.FALSE;
            }
            if ("isValid".equals(name)) {
                if (released) {
                    return Boolean.FALSE;
                }
                try {
                    return raw.isValid(args == null || args.length == 0 ? 2 : (Integer) args[0]);
                } catch (SQLException e) {
                    return Boolean.FALSE;
                }
            }
            if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] == Connection.class) {
                return raw;
            }
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            try {
                return method.invoke(raw, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    /** LRU 淘汰：池数超限时移除最久未用的池并关闭其连接。 */
    private void evictIfNeeded() {
        while (pools.size() > maxPools) {
            String oldestKey = null;
            for (String key : pools.keySet()) {
                oldestKey = key;
                break;
            }
            if (oldestKey == null) {
                return;
            }
            PooledConnection oldest = pools.remove(oldestKey);
            List<Connection> toClose = oldest.drain();
            for (Connection connection : toClose) {
                closeQuietly(connection);
            }
            log.info("连接池 LRU 淘汰：pool={}，关闭 {} 条连接", oldestKey, toClose.size());
        }
    }

    /** 连接是否仍可用（已关闭或校验失败即视为不可用）。 */
    private static boolean isUsable(Connection connection) {
        try {
            if (connection.isClosed()) {
                return false;
            }
        } catch (SQLException e) {
            return false;
        }
        try {
            return connection.isValid(2);
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            // 部分驱动不支持 isValid：退化为「未关闭即视为可用」
            return true;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.debug("关闭连接失败（忽略）: {}", e.getMessage());
        }
    }

    /** 连接工厂（新建底层连接）。 */
    public interface ConnectionFactory {
        Connection create() throws SQLException;
    }

    /**
     * 单个池键对应的连接池。
     *
     * <p>空闲连接以栈（后进先出）保存；借出时移出，归还时放回。
     * 同一池键同一时刻只允许一条连接被借出。</p>
     */
    private static final class PooledConnection {

        private final List<Connection> idle = new ArrayList<>(2);

        private Connection inUse;

        /** 借出一条空闲连接；无空闲连接返回 null。 */
        synchronized Connection take() {
            if (idle.isEmpty()) {
                return null;
            }
            Connection connection = idle.remove(idle.size() - 1);
            inUse = connection;
            return connection;
        }

        /** 归还一条连接（放在栈顶，优先复用最近归还的）。 */
        synchronized void give(Connection connection) {
            if (inUse == connection) {
                inUse = null;
            }
            idle.add(connection);
        }

        /** 丢弃一条坏连接（不缓存）。 */
        synchronized void discard(Connection connection) {
            if (inUse == connection) {
                inUse = null;
            }
            idle.remove(connection);
        }

        /** 取出并清空全部连接（供关闭时释放）。 */
        synchronized List<Connection> drain() {
            List<Connection> all = new ArrayList<>(idle);
            idle.clear();
            if (inUse != null) {
                all.add(inUse);
                inUse = null;
            }
            return all;
        }

        synchronized int size() {
            return idle.size() + (inUse == null ? 0 : 1);
        }
    }
}
