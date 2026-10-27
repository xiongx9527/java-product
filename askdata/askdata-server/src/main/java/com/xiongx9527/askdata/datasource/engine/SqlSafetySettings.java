package com.xiongx9527.askdata.datasource.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A27：{@link SqlSafety} 的配置装配。
 *
 * <p>只读校验是静态工具（调用方遍布 JDBC 引擎、问数主链路与定时任务），
 * 这里把 {@code askdata.sql.allow-metadata-queries}（缺省 false，对齐 SQLBot
 * {@code ALLOW_METADATA_QUERIES=False}）在启动期写入其静态开关；未配置时保持缺省拒绝。</p>
 */
@Component
public class SqlSafetySettings {

    public SqlSafetySettings(@Value("${askdata.sql.allow-metadata-queries:false}") boolean allowMetadataQueries) {
        SqlSafety.setAllowMetadataQueries(allowMetadataQueries);
    }
}
