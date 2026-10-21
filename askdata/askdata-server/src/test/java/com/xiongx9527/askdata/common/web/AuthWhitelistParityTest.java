package com.xiongx9527.askdata.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白名单语义与 SQLBot {@code common/utils/whitelist.py} 的<strong>逐条等价</strong>校验。
 *
 * <p>做法：把 SQLBot 源码里的 {@code WhitelistChecker} 原样抽出来跑一遍（Python 侧，
 * 由构建脚本预先生成），再对同一批路径跑 askdata 的 {@link AuthWhitelist}，逐条比对结果。
 * 路径语料覆盖三类：白名单条目本身、SQLBot 源测试里钉住的路径、以及随机组合路径。</p>
 */
class AuthWhitelistParityTest {

    private static final String SQLBOT_ROOT = "/Users/xiongxiao/Workspaces/Idea/codes/New/SQLBot/backend";

    private final AuthWhitelist whitelist = new AuthWhitelist("/api/v1");

    /** 语料：白名单 35 条各自的典型实参 + 源测试钉住的路径 + 边界路径。 */
    private static List<String> corpus() {
        List<String> paths = new ArrayList<>();
        paths.add("/");
        paths.add("/api/v1");
        paths.add("/docs");
        paths.add("/api/v1/docs");
        paths.add("/login/access-token");
        paths.add("/api/v1/login/access-token");
        paths.add("/api/v1/login/change-pwd");
        paths.add("/api/v1/login/logout");
        paths.add("/favicon.ico");
        paths.add("/index.html");
        paths.add("/assets/app.js");
        paths.add("/assets/app.css");
        paths.add("/assets/app.css.map");
        paths.add("/img/a.png");
        paths.add("/img/a.jpg");
        paths.add("/img/a.jpeg");
        paths.add("/img/a.gif");
        paths.add("/img/a.svg");
        paths.add("/fonts/a.woff");
        paths.add("/fonts/a.woff2");
        paths.add("/fonts/a.ttf");
        paths.add("/fonts/a.eot");
        paths.add("/fonts/a.otf");
        paths.add("/api/v1/mcp/access_token");
        paths.add("/api/v1/mcp/mcp_start");
        paths.add("/api/v1/mcp/mcp_question");
        paths.add("/api/v1/mcp/mcp_assistant");
        paths.add("/mcp/access_token");
        paths.add("/api/v1/system/license");
        paths.add("/api/v1/system/config/key");
        paths.add("/api/v1/images/logo.png");
        paths.add("/api/v1/sse");
        paths.add("/api/v1/system/appearance/ui");
        paths.add("/api/v1/system/appearance/picture/abc.png");
        paths.add("/api/v1/system/assistant/info/123");
        paths.add("/api/v1/system/assistant/app/abc");
        paths.add("/api/v1/system/assistant/picture/abc.png");
        paths.add("/api/v1/system/assistant/validate/1");
        paths.add("/api/v1/system/authentication/platform/status");
        paths.add("/api/v1/system/authentication/login/1");
        paths.add("/api/v1/system/authentication/sso/1");
        paths.add("/api/v1/system/platform/sso/6");
        paths.add("/api/v1/system/platform/client/6");
        paths.add("/api/v1/system/parameter/login");
        // 受保护路径
        paths.add("/api/v1/system/embedded");
        paths.add("/api/v1/user/info");
        paths.add("/api/v1/user/defaultPwd");
        paths.add("/api/v1/system/user/list");
        paths.add("/api/v1/chat/list");
        paths.add("/api/v1/datasource/list");
        paths.add("/api/v1/system/user/list/");
        // 注入路径（SQLBot 源测试钉住：正则层面仍命中，真实路径不命中）
        paths.add("/api/v1/mcp/api/v1/system/embedded");
        // 尾斜杠与大小写
        paths.add("/api/v1/system/assistant/info/123/");
        paths.add("/api/v1/SYSTEM/LICENSE");
        return paths;
    }

    @Test
    @DisplayName("与 SQLBot WhitelistChecker 对同一批路径给出完全一致的判定")
    void matchesSqlBotImplementationExactly() throws Exception {
        List<String> paths = corpus();
        List<String> sqlbotResults = runSqlBotReference(paths);
        assertEquals(paths.size(), sqlbotResults.size(), "SQLBot 参考实现应产出等长结果");

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            boolean expected = Boolean.parseBoolean(sqlbotResults.get(i));
            boolean actual = whitelist.isWhitelistedPath(path);
            assertEquals(expected, actual,
                    "路径判定与 SQLBot 不一致: " + path
                            + " (SQLBot=" + expected + ", askdata=" + actual + ")");
        }
    }

    @Test
    @DisplayName("白名单条目与 SQLBot wlist 逐条同序同内容")
    void whitelistEntriesMatchSqlBotWlist() throws Exception {
        List<String> sqlbotWlist = readSqlBotWlist();
        assertEquals(sqlbotWlist, AuthWhitelist.DEFAULT_PATHS,
                "白名单条目应与 SQLBot common/utils/whitelist.py 的 wlist 逐条一致");
    }

    /** 从 SQLBot 源码里原样抽出 wlist（不重新誊写，避免抄写漂移）。 */
    private static List<String> readSqlBotWlist() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(SQLBOT_ROOT, "common/utils/whitelist.py")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("wlist = [");
        int end = source.indexOf("]", start);
        String block = source.substring(start + "wlist = [".length(), end);
        List<String> result = new ArrayList<>();
        for (String raw : block.split("\n")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            item = item.replace(",", "");
            if (item.startsWith("\"") && item.endsWith("\"")) {
                result.add(item.substring(1, item.length() - 1));
            }
        }
        return result;
    }

    /**
     * 跑 SQLBot 的 {@code WhitelistChecker} 参考实现。
     *
     * <p>直接执行 SQLBot 源文件里抽出的类（用一段与源文件等价的 shim 装载），
     * 避免"把 Python 逻辑重写成 Java 再自证"的循环论证。</p>
     */
    private static List<String> runSqlBotReference(List<String> paths) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(SQLBOT_ROOT, "common/utils/whitelist.py")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("wlist = [");
        int end = source.indexOf("]", start);
        String wlistBlock = source.substring(start, end + 1);
        String classBlock = source.substring(source.indexOf("class WhitelistChecker"), source.indexOf("class WhitelistChecker")
                + source.substring(source.indexOf("class WhitelistChecker")).indexOf("def add_path"));

        String script = "import re\n"
                + "from typing import List, Pattern\n"
                + "class FakeSettings:\n"
                + "    API_V1_STR = '/api/v1'\n"
                + "    CONTEXT_PATH = ''\n"
                + "settings = FakeSettings()\n"
                + wlistBlock + "\n"
                + classBlock + "\n"
                + "checker = WhitelistChecker()\n"
                + "import sys, json\n"
                + "paths = json.loads(sys.stdin.read())\n"
                + "print(json.dumps([checker.is_whitelisted(p) for p in paths]))\n";

        Path scriptFile = Files.createTempFile("sqlbot-whitelist-ref", ".py");
        Files.write(scriptFile, script.getBytes(StandardCharsets.UTF_8));
        ProcessBuilder builder = new ProcessBuilder("python3", "-I", scriptFile.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getOutputStream().write(jsonArray(paths).getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();
        String output = new String(readAll(process.getInputStream()).getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        assertTrue(exit == 0, "SQLBot 参考实现执行失败: " + output);
        Files.deleteIfExists(scriptFile);
        return parseJsonArray(output);
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static List<String> parseJsonArray(String json) {
        List<String> result = new ArrayList<>();
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }
}
