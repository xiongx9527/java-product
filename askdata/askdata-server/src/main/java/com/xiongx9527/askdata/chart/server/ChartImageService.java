package com.xiongx9527.askdata.chart.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.chat.service.ChatChartBuilder;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Server-side image service (acceptance A4/A5): renders a Q&A result into a PNG and returns an
 * accessible image URL.
 *
 * <p>Flow: Q&A result (rows) -> ChatChartBuilder normalized/built config -> ChartImageRenderer
 * raster -> persisted to file storage (x-file-storage, local by default; switchable to OSS/MinIO)
 * -> returns {baseUrl}/{token} URL for MCP / IM / non-interactive / export consumers.</p>
 *
 * <p>A5 image URL lifecycle:</p>
 * <ul>
 *   <li>token is an unguessable 32-hex string (UUID without dashes); the image_url handed to
 *       MCP/IM/third-party is exactly this;</li>
 *   <li>token -> {file url, createdAt, expiresAt} mapping is persisted to a disk JSON
 *       (tokenStoreDir/.chart-tokens.json) so the same token still opens the image after restart;</li>
 *   <li>image bytes go through AskdataFileStorage (survives restart, switchable to remote); only a
 *       cache is held in-process;</li>
 *   <li>unauthenticated third-party can access within TTL (/api/chart/image/* is in the auth whitelist);</li>
 *   <li>token format is validated (32 hex): tampered (non-32-hex) or unknown tokens return null -> 404;</li>
 *   <li>expired tokens return null -> 404 and are cleaned (with their files) by TTL, no unbounded growth.</li>
 * </ul>
 */
@Service
public class ChartImageService {

    private static final Logger log = LoggerFactory.getLogger(ChartImageService.class);
    private static final int MAX_RENDER_ROWS = 200;
    private static final String TOKEN_FILE_NAME = ".chart-tokens.json";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final long DEFAULT_TTL_MS = 86_400_000L; // 24h

    private final ChatChartBuilder chartBuilder;
    private final ChartImageRenderer renderer;
    private final ObjectMapper objectMapper;
    private final AskdataFileStorage fileStorage;

    /** token -> metadata (url + createdAt + expiresAt); in-process cache, truth in persisted JSON. */
    private final Map<String, TokenEntry> store = new ConcurrentHashMap<>();
    private final Object storeLock = new Object();

    @Value("${askdata.chart.image-base-url:/api/chart/image}")
    private String imageBaseUrl = "/api/chart/image";

    @Value("${askdata.chart.image-ttl-ms:86400000}")
    private long ttlMs = DEFAULT_TTL_MS;

    @Value("${askdata.chart.token-store-dir:}")
    private String tokenStoreDirProp = "";

    private Path tokenStoreDir;

    @Autowired
    public ChartImageService(ChatChartBuilder chartBuilder, ChartImageRenderer renderer,
                             ObjectMapper objectMapper, AskdataFileStorage fileStorage) {
        this.chartBuilder = chartBuilder;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
        this.fileStorage = fileStorage;
        // non-Spring / unit-test default token store dir (persisted to temp, no @Value needed).
        this.tokenStoreDir = Path.of(System.getProperty("java.io.tmpdir"), "askdata-chart-tokens");
        initStore();
    }

    /** Test/explicit constructor: injectable token store dir and TTL (bypasses @Value). */
    public ChartImageService(ChatChartBuilder chartBuilder, ChartImageRenderer renderer,
                             ObjectMapper objectMapper, AskdataFileStorage fileStorage,
                             Path tokenStoreDir, long ttlMs) {
        this(chartBuilder, renderer, objectMapper, fileStorage);
        this.tokenStoreDir = tokenStoreDir;
        this.ttlMs = ttlMs;
        initStore();
    }

    /** Load persisted token mapping after @Value injection (Spring instances only). */
    @PostConstruct
    public void init() {
        if (tokenStoreDir == null) {
            tokenStoreDir = tokenStoreDirProp == null || tokenStoreDirProp.isBlank()
                    ? Path.of(System.getProperty("user.home", "."), ".askdata", "chart-tokens")
                    : Path.of(tokenStoreDirProp);
        }
        initStore();
    }

    private void initStore() {
        synchronized (storeLock) {
            store.clear();
            Path file = tokenStoreDir.resolve(TOKEN_FILE_NAME);
            if (Files.exists(file)) {
                try {
                    Map<String, TokenEntry> loaded = objectMapper.readValue(file.toFile(),
                            objectMapper.getTypeFactory().constructMapType(ConcurrentHashMap.class,
                                    String.class, TokenEntry.class));
                    if (loaded != null) {
                        store.putAll(loaded);
                    }
                } catch (Exception e) {
                    log.warn("chart token store load failed (start empty): {}", e.getMessage());
                }
            }
        }
    }

    private void saveStore() {
        try {
            Files.createDirectories(tokenStoreDir);
            Path file = tokenStoreDir.resolve(TOKEN_FILE_NAME);
            objectMapper.writeValue(file.toFile(), store);
        } catch (IOException e) {
            log.warn("chart token store persist failed: {}", e.getMessage());
        }
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
            Map<String, Object> shape = result.containsKey("fields") ? result : toResultShape(rows);
            Map<String, Object> chart = chartBuilder.build(shape, question);
            if (chart == null) {
                return null;
            }
            byte[] png = renderer.renderPng(chart, rows);
            String token = UUID.randomUUID().toString().replace("-", "");
            StoredFile stored = fileStorage.save(png, token + ".png", "chart", "image/png");
            long now = System.currentTimeMillis();
            TokenEntry entry = new TokenEntry(stored.url(), now, now + ttlMs);
            synchronized (storeLock) {
                store.put(token, entry);
                saveStore();
            }
            String base = imageBaseUrl.endsWith("/") ? imageBaseUrl : imageBaseUrl + "/";
            return base + token;
        } catch (Exception e) {
            log.warn("chart render failed (fallback no image): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve image bytes by token.
     *
     * @return bytes; null (-> 404 by controller) when token is null, malformed (tampered), unknown,
     *         or expired.
     */
    public byte[] get(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        TokenEntry entry;
        synchronized (storeLock) {
            entry = store.get(token);
        }
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            removeExpired(token, entry);
            return null;
        }
        try {
            return fileStorage.read(entry.url());
        } catch (Exception e) {
            log.warn("chart image read failed for token {}: {}", token, e.getMessage());
            return null;
        }
    }

    private void removeExpired(String token, TokenEntry entry) {
        synchronized (storeLock) {
            store.remove(token);
            saveStore();
        }
        try {
            fileStorage.delete(entry.url());
        } catch (Exception e) {
            log.debug("chart image file delete failed (token cleared): {}", e.getMessage());
        }
    }

    /**
     * Clean all expired tokens and their image files (A5: TTL-based cleanup, no unbounded growth).
     *
     * @return number of expired entries removed this pass
     */
    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        synchronized (storeLock) {
            for (Map.Entry<String, TokenEntry> e : store.entrySet()) {
                if (now > e.getValue().expiresAt()) {
                    expired.add(e.getKey());
                }
            }
            if (expired.isEmpty()) {
                return 0;
            }
            for (String t : expired) {
                TokenEntry en = store.remove(t);
                if (en != null) {
                    try {
                        fileStorage.delete(en.url());
                    } catch (Exception ignored) {
                        // file may already be removed externally
                    }
                }
            }
            saveStore();
        }
        if (!expired.isEmpty()) {
            log.info("chart image TTL cleanup removed {} expired token(s)", expired.size());
        }
        return expired.size();
    }

    /** Periodic cleanup (relies on ScheduledTaskConfig's @EnableScheduling; never breaks main flow). */
    @Scheduled(fixedDelayString = "${askdata.chart.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        try {
            cleanupExpired();
        } catch (Exception e) {
            log.debug("chart image scheduled cleanup error: {}", e.getMessage());
        }
    }

    public int cachedCount() {
        return store.size();
    }

    /** token metadata (JSON-serializable). */
    public record TokenEntry(String url, long createdAt, long expiresAt) {
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
