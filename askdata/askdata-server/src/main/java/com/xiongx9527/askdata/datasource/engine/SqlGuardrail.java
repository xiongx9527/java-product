package com.xiongx9527.askdata.datasource.engine;

import java.util.Map;

/**
 * SQL 确定性护栏（产品侧兼容入口）。
 *
 * <p>通用实现已下沉到框架 {@code com.xiongx9527.fw.plus.ai.askdata.postprocess.SqlGuardrail}；
 * 本类保留原包名与方法签名，仅做委托，避免改动既有调用方（如 ChatQuestionService）。
 * 新增代码请直接调用框架类。</p>
 */
public final class SqlGuardrail {

    private SqlGuardrail() {
    }

    /** 尝试确定性修复；无法安全修复时返回 null。 */
    public static String repair(String sql, String error, Map<String, Map<String, String>> schema) {
        return com.xiongx9527.fw.plus.ai.askdata.postprocess.SqlGuardrail.repair(sql, error, schema);
    }
}
