package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;

import java.util.Collections;
import java.util.Map;

/**
 * 动态数据源（A8）：高级应用（assistant type 1/3）按请求从宿主拉取的数据源连接配置。
 *
 * <p>与 core_datasource 中的静态数据源不同，动态数据源<b>不持久化</b>：仅在本次请求内
 * 以 {@link DatasourceType} + {@link DatasourceConf} 形式存在，执行时交由
 * {@code JdbcEngine} 按连接配置派生池键（datasourceId=null），与静态数据源走同一条
 * 查询/元数据链路，因此问数行为保持一致。</p>
 */
public class DynamicDatasource {

    private final DatasourceType type;

    private final DatasourceConf conf;

    private final String name;

    /** §2.4: 表级 SQL 模板（SQLBot dynamic_sql）——宿主按表下发的子查询模板，
     * 键为表名、值为子查询模板（可含占位符，由二次 LLM 改写为具体 SQL）。
     * 原 askdata 动态数据源只有 type/conf/name，缺少该维度，整条子查询改写链路不存在。 */
    private final Map<String, String> tableSqlTemplates;

    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name) {
        this(type, conf, name, Collections.<String, String>emptyMap());
    }

    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name,
                            Map<String, String> tableSqlTemplates) {
        this.type = type;
        this.conf = conf == null ? new DatasourceConf() : conf;
        this.name = (name == null || name.trim().isEmpty()) ? type.getDbName() : name.trim();
        this.tableSqlTemplates = tableSqlTemplates == null ? Collections.<String, String>emptyMap()
                : tableSqlTemplates;
    }

    public DatasourceType getType() {
        return type;
    }

    public DatasourceConf getConf() {
        return conf;
    }

    /** 展示名（宿主未提供时回落到类型展示名）。 */
    public String getName() {
        return name;
    }

    /** §2.4: 表级 SQL 模板（表名 → 子查询模板）。 */
    public Map<String, String> getTableSqlTemplates() {
        return tableSqlTemplates;
    }

    /** 数据源类型字符串（落 chat_record.engine_type / 对外 type 字段）。 */
    public String typeName() {
        return type.getType();
    }
}

