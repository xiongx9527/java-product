package com.xiongx9527.askdata.dashboard;

import java.util.Map;

/**
 * 仪表盘画布图表数据执行器（对齐 SQLBot load_resource 中 get_chart_data_ds 的调用契约）：
 * 输入数据源 id 与 SQL，输出 {@code {"status": "success|failed", "data": [...], "message": "..."}}。
 *
 * <p>数据源连接由数据源模块（core_datasource）承担；本接口供仪表盘加载时调用，
 * 具体实现见 {@link JdbcChartDataExecutor}（直连 JDBC 的只读执行）。</p>
 */
public interface ChartDataExecutor {

    Map<String, Object> execute(Long datasourceId, String sql);
}
