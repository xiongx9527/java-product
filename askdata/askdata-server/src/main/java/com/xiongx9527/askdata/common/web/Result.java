package com.xiongx9527.askdata.common.web;

import com.xiongx9527.fw.plus.common.model.JsonResult;

/**
 * 统一响应包装，继承框架的 {@link JsonResult}，与 SQLBot ResponseMiddleware 的包装契约一致：
 * {@code {"code":0,"data":<payload>,"msg":null}}；前端 request.ts 消费 code===0 → data。
 *
 * <p>基类 {@link JsonResult} 提供 6 个可重命名的基础 key（code / success / message / data /
 * traceId / spanId）与链式设值能力（本身是 {@code HashMap}，可被 Jackson 直接序列化）。
 * 本类在其之上把消息 key 重命名为 {@code msg}（SQLBot 契约用 msg 而非 message）
 * 并去掉 {@code success}，使序列化结果与既有 {@code {"code","data","msg"}} 三键结构<b>完全一致</b>，
 * 不引入多余字段。</p>
 *
 * <p><b>关于静态工厂</b>：基类的 {@code ok(String)} / {@code ok(String, Object)} 是「消息」语义，
 * 而本类的 {@code ok(T)} 是「数据」语义（既有 180+ 处调用点均按数据语义使用）。
 * 故本类显式隐藏基类同名静态方法，保证 {@code Result.ok("x")} 的含义与改造前一致（data=字符串），
 * 避免继承后语义漂移。</p>
 */
public class Result<T> extends JsonResult<T> {

    public Result() {
        super();
        // SQLBot 契约：消息 key 为 msg；且响应体只有 code / data / msg 三个键
        renameMessage("msg");
        remove("success");
        data(null);
        message(null);
    }

    /** 成功响应（code=0，msg=null，data 为入参）。 */
    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code(0);
        result.data(data);
        result.message(null);
        return result;
    }

    /** 成功响应（无 data）。 */
    public static Result<Void> ok() {
        Result<Void> result = new Result<>();
        result.code(0);
        result.data(null);
        result.message(null);
        return result;
    }

    /**
     * 成功响应（字符串数据）——隐藏基类同名的「消息」语义方法，保持改造前的 data 语义。
     */
    public static Result<String> ok(String data) {
        Result<String> result = new Result<>();
        result.code(0);
        result.data(data);
        result.message(null);
        return result;
    }

    /**
     * 成功响应 + 自定义消息 + 数据（对齐基类语义；本项目暂无调用点）。
     */
    public static Result<Object> ok(String message, Object data) {
        Result<Object> result = new Result<>();
        result.code(0);
        result.message(message);
        result.data(data);
        return result;
    }

    /** 取消息文本（对应基类的 message，键名为 msg）。 */
    public String getMsg() {
        return getMessage();
    }

    /** 设消息文本（对应基类的 message，键名为 msg）。 */
    public void setMsg(String msg) {
        message(msg);
    }
}
