package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side image service (acceptance A4): renders a Q&A result into a PNG and returns an
 * accessible image URL.
 *
 * <p>Flow: Q&A result (rows) -> ChatChartBuilder normalized config -> ChartImageRenderer raster
 * -> 持久化到文件存储（x-file-storage，默认本地）-> 返回 {baseUrl}/{token} URL，供 MCP / 非交互 /
 * 导出场景回访。</p>
 *
 * <p>图片字节经 {@link AskdataFileStorage} 落文件存储（重启不丢、可切 OSS/MinIO）；进程内仅保留
 * token -> url 元数据映射，不再驻留字节。token 为不可猜测的随机 UUID。base URL 可配
 * （askdata.chart.image-base-url，默认 /api/chart/image）。</p>
 */
@Service
public class ChartImageService {

    private static final Logger log = LoggerFactory.getLogger(ChartImageService.class);
    private static final int MAX_RENDER_ROWS = 200;

    private final ChatChartBuilder chartBuilder;
    private final ChartImageRenderer renderer;
    private final ObjectMapper objectMapper;
    private final AskdataFileStorage fileStorage;
    private final Map<String, String> tokenToUrl = new ConcurrentHashMap<>();

    @Value("${askdata.chart.image-base-url:/api/chart/image}")
    private String imageBaseUrl = "/api/chart/image";

    public ChartImageService(ChatChartBuilder chartBuilder, ChartImageRenderer renderer,
                             ObjectMapper objectMapper, AskdataFileStorage fileStorage) {
        this.chartBuilder = chartBuilder;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
        this.fileStorage = fileStorage;
    }

    public String renderFromResult(Map<String, Object> result, String question) {
        if (result == null) {
            return null;
        }
        List<Map<String, Object>> rows = asRows(result.get("rows"));
        if (rows.isEmpty()) {
            rows = asRows(result.get("data"));
        }
        if (rows.isEmpty()) {
            return null;
        }
        try {
            // 主链路 result 已含 fields/fields_info/data（直接复用）；MCP/AskdataNl2Sql 仅含 rows 时再归一化
            Map<String, Object> shape = result.containsKey("fields") ? result : toResultShape(rows);
            Map<String, Object> chart = chartBuilder.build(shape, question);
            if (chart == null) {
                return null;
            }
            byte[] png = renderer.renderPng(chart, rows);
            String token = UUID.randomUUID().toString().replace("-", "");
            // 持久化到文件存储（重启不丢、可切 OSS/MinIO）；进程内仅保留 token -> url 元数据
            StoredFile stored = fileStorage.save(png, token + ".png", "chart", "image/png");
            tokenToUrl.put(token, stored.url());
            String base = imageBaseUrl.endsWith("/") ? imageBaseUrl : imageBaseUrl + "/";
            return base + token;
        } catch (Exception e) {
            log.warn("chart render failed (fallback no image): {}", e.getMessage());
            return null;
        }
    }

    public byte[] get(String token) {
        if (token == null) {
            return null;
        }
        String url = tokenToUrl.get(token);
        if (url == null) {
            return null;
        }
        try {
            return fileStorage.read(url);
        } catch (Exception e) {
            log.warn("chart image read failed for token {}: {}", token, e.getMessage());
            return null;
        }
    }

    public int cachedCount() {
        return tokenToUrl.size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List) {
            for (Object item : (List<?>) rows) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private Map<String, Object> toResultShape(List<Map<String, Object>> rows) {
        Map<String, Object> shape = new LinkedHashMap<>();
        List<String> fields = new ArrayList<>();
        if (!rows.isEmpty()) {
            fields.addAll(rows.get(0).keySet());
        }
        List<Map<String, Object>> fieldsInfo = new ArrayList<>();
        for (String f : fields) {
            boolean numeric = true;
            for (Map<String, Object> row : rows) {
                Object v = row.get(f);
                if (v == null) continue;
                try {
                    Double.parseDouble(String.valueOf(v).trim());
                } catch (NumberFormatException e) {
                    numeric = false;
                    break;
                }
            }
            fieldsInfo.add(Map.of("name", f, "is_numeric", numeric));
        }
        List<Map<String, Object>> limited = rows.size() > MAX_RENDER_ROWS
                ? new ArrayList<>(rows.subList(0, MAX_RENDER_ROWS)) : rows;
        shape.put("fields", fields);
        shape.put("fields_info", fieldsInfo);
        shape.put("data", limited);
        return shape;
    }
}
