package com.xiongx9527.askdata.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常输出对齐 SQLBot：非 2xx 时返回纯文本 detail（前端 request.ts 直接展示 response 文本）；
 * 401 触发前端清除 token 并回到登录页。
 */
@RestControllerAdvice(basePackages = "com.xiongx9527.askdata")
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<String> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<String> notLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("authenticate invalid: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> serverError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(String.valueOf(e.getMessage()));
    }
}
