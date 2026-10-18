package com.xiongx9527.askdata.datasource;

/**
 * 数据源模块业务异常：HTTP 500 + 纯文本 detail（对齐 SQLBot HTTPException(status_code=500, detail=...)，
 * 前端 request.ts 直接展示响应文本）。
 */
public class DatasourceException extends RuntimeException {

    public DatasourceException(String detail) {
        super(detail);
    }

    public DatasourceException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
