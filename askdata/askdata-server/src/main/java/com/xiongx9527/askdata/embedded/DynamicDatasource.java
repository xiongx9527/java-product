package com.xiongx9527.askdata.embedded;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;

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

    public DynamicDatasource(DatasourceType type, DatasourceConf conf, String name) {
        this.type = type;
        this.conf = conf == null ? new DatasourceConf() : conf;
        this.name = (name == null || name.trim().isEmpty()) ? type.getDbName() : name.trim();
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

    /** 数据源类型字符串（落 chat_record.engine_type / 对外 type 字段）。 */
    public String typeName() {
        return type.getType();
    }
}

