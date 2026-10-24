package com.xiongx9527.askdata.ai.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 在线向量库实现：pgvector，基于 langchain4j 的 {@link PgVectorEmbeddingStore}（JDBC + Postgres 向量扩展）。
 *
 * <p>向量读写、候选过滤、命中 id 解析等公共逻辑见 {@link AbstractLangchain4jVectorStore}；
 * 本类处理 pgvector 专属约束：</p>
 * <ul>
 *   <li><b>id 必须是 UUID</b>：PgVectorEmbeddingStore 以 UUID 作主键，故 {@link #toPointId(long)} 由记录 id 派生稳定 UUID；
 *       真实记录 id 存于 metadata.row_id（默认 JSON 列），命中时回退解析。</li>
 *   <li><b>集合 = 表</b>：每个集合对应一张表（表名由集合名清洗得到）；首次写入自动建表（createTable=true）。</li>
 *   <li><b>候选过滤服务端生效</b>：pgvector 的 metadata handler 会把 {@code row_id in [...]} 转成 SQL 条件。</li>
 * </ul>
 *
 * <p>注意：依赖 Postgres 已安装 pgvector 扩展（{@code CREATE EXTENSION vector;}），否则建表/写入报错，自动回落主链路。</p>
 */
public class Langchain4jPgVectorVectorStore extends AbstractLangchain4jVectorStore {

    private static final Logger log = LoggerFactory.getLogger(Langchain4jPgVectorVectorStore.class);

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean useIndex;

    public Langchain4jPgVectorVectorStore(String host, int port, String database, String user, String password,
                                         String collectionPrefix, boolean useIndex) {
        super(collectionPrefix);
        this.host = (host == null ? "" : host.trim());
        this.port = port > 0 ? port : 5432;
        this.database = (database == null ? "" : database.trim());
        this.user = (user == null ? "" : user.trim());
        this.password = (password == null || password.trim().isEmpty()) ? null : password.trim();
        this.useIndex = useIndex;
        log.info("pgvector 后端初始化：{}:{}/{} (useIndex={})", this.host, this.port, this.database, useIndex);
    }

    /** 记录 id -> 稳定 UUID（nameUUIDFromBytes），保证 upsert（ON CONFLICT）覆盖。 */
    @Override
    protected String toPointId(long id) {
        return UUID.nameUUIDFromBytes(("" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 集合名 -> 合法 SQL 表名（非字母数字/下划线转下划线）。 */
    private static String toTableName(String collection) {
        String s = collection.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "askdata_vectors" : s;
    }

    @Override
    public boolean health() {
        // 用 JDBC URL + DriverManager：仅依赖运行时驱动，不要求编译期引入 postgresql 包。
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, password);
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select 1")) {
            return rs.next();
        } catch (Exception e) {
            log.info("pgvector 健康检查失败（{}:{}/{}）: {}", host, port, database, e.getMessage());
            return false;
        }
    }

    @Override
    protected EmbeddingStore<TextSegment> createStore(String collection, int dimension) {
        PgVectorEmbeddingStore.PgVectorEmbeddingStoreBuilder builder = PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .table(toTableName(collection))
                .dimension(dimension)
                .createTable(true)
                .useIndex(useIndex);
        if (password != null) {
            builder.password(password);
        }
        return builder.build();
    }
}
