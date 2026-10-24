package com.xiongx9527.askdata.common.web;

/**
 * 统一响应包装，与 SQLBot ResponseMiddleware 的包装契约一致：
 * {@code {"code":0,"data":<payload>,"msg":null}}；前端 request.ts 消费 code===0 → data。
 */
public class Result<T> {

    private int code;
    private T data;
    private String msg;

    public Result() {
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.data = data;
        result.msg = null;
        return result;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
}
