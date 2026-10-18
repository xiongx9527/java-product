package com.xiongx9527.askdata.common.web;

/** 业务拒绝：HTTP 400 + 文本 detail（对齐 SQLBot HTTPException(status_code=400, detail=msg)）。 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String detail) {
        super(detail);
    }
}
