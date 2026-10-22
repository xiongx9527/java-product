package com.xiongx9527.askdata.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * JSON 长整型安全序列化（雪花 ID）。
 *
 * <p>askdata 的主键是 64 位雪花 ID（约 7.5e18），超出 JavaScript 的
 * {@code Number.MAX_SAFE_INTEGER}（2^53-1）。若按 JSON number 下发，浏览器解析时会被
 * 四舍五入，前端再用该 id 调 delete/get 就会命中不存在的记录（表现为「数据源不存在」）。
 * 因此超过安全整数范围的 Long 一律按字符串下发；时间戳等仍在安全范围内，保持 number 不变。</p>
 */
@Configuration
public class JsonLongConfig {

    /** JavaScript Number 可精确表示的最大整数（2^53 - 1）。 */
    static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    static final long JS_MIN_SAFE_INTEGER = -9007199254740991L;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer safeLongCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("askdata-safe-long");
            JsonSerializer<Long> serializer = new JsonSerializer<Long>() {
                @Override
                public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    if (value == null) {
                        gen.writeNull();
                    } else if (value > JS_MAX_SAFE_INTEGER || value < JS_MIN_SAFE_INTEGER) {
                        gen.writeString(value.toString());
                    } else {
                        gen.writeNumber(value);
                    }
                }
            };
            module.addSerializer(Long.class, serializer);
            module.addSerializer(Long.TYPE, serializer);
            builder.modulesToInstall(module);
        };
    }
}

