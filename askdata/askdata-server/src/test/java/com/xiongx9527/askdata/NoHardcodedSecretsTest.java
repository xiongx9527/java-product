package com.xiongx9527.askdata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 验收 A5 静态检查：主源码与 Flyway 迁移脚本中不得出现硬编码口令/密钥字面量。
 *
 * <p>白名单：SQLBot 产品默认管理员口令（MD5 种子，测试依赖，非新增密钥）——
 * 仅允许该已知哈希，新增明文口令/密钥一律禁止。</p>
 */
class NoHardcodedSecretsTest {

    private static final Pattern SECRET_LITERAL = Pattern.compile(
            "(?i)(password|passwd|secret|secret_key|api_key|apikey|token|access_key|accesskey)\s*=\s*\"[A-Za-z0-9!@#$%^&*()_+=\\-]{4,}\"");

    private static final List<String> DENY = List.of("Root57@123456", "SQLBot@123456");

    /**
     * A5 补充：{@code @Value} 注解中 secret/password/key/token 类配置不得带非空缺省值，
     * 否则等于把密钥写进版本库（历史漏检：askdata.secret-key 缺省 AskDataSecret001）。
     */
    private static final Pattern VALUE_DEFAULT_SECRET = Pattern.compile(
            "@Value\\(\\s*\"\\$\\{[^}\"]*(?:secret|password|passwd|token|key)[^}\"]*:[^}\"]+\\}\"\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noHardcodedSecretsInMainSource() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Paths.get("src/main/java");
        if (Files.exists(root)) {
            walkAndCheck(root, offenders);
        }
        Path migrations = Paths.get("src/main/resources/db/migration");
        if (Files.exists(migrations)) {
            try (var paths = Files.walk(migrations)) {
                paths.filter(p -> p.toString().endsWith(".sql")).forEach(p -> checkFile(p, offenders));
            }
        }
        if (!offenders.isEmpty()) {
            fail("发现硬编码口令/密钥字面量:\n" + String.join("\n", offenders));
        }
    }

    private void walkAndCheck(Path dir, List<String> offenders) throws IOException {
        Files.walk(dir).filter(p -> p.toString().endsWith(".java")).forEach(p -> checkFile(p, offenders));
    }

    private void checkFile(Path p, List<String> offenders) {
        try {
            String content = Files.readString(p);
            for (String deny : DENY) {
                if (content.contains(deny)) {
                    offenders.add(p + " : 含禁用字面量 " + deny);
                }
            }
            var m = SECRET_LITERAL.matcher(content);
            while (m.find()) {
                // 白名单：产品默认管理员口令哈希（MD5 of SQLBot@123456）仅作种子，允许
                if (m.group().contains("8f32d1e371702c1b1b7346f4b07a701d")) {
                    continue;
                }
                offenders.add(p + " : 疑似硬编码密钥赋值 " + m.group());
            }
            var vd = VALUE_DEFAULT_SECRET.matcher(content);
            while (vd.find()) {
                offenders.add(p + " : @Value 缺省值疑似密钥 " + vd.group());
            }
        } catch (IOException e) {
            fail("读取文件失败: " + p);
        }
    }
}
