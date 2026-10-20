package com.xiongx9527.askdata.embedded;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 小助手/嵌入应用（对齐 SQLBot apps/system/api/assistant.py 与前端 api/embedded.ts；
 * SQLBot embedded 组路由位于 xpack，此处按 D4「行为可用」重写）：
 * assistant 列表/CRUD/ui 配置，embedded（type=4 页面嵌入）分页/CRUD/secret 重置。
 */
@Service
public class AssistantService {
    @Autowired
    private MessageResolver messageResolver;

    private final AssistantRepository assistantRepository;

    private final CurrentUserService currentUserService;

    private final AssistantFileStorage fileStorage;

    private final AuditRecorder auditRecorder;

    private final ObjectMapper objectMapper;

    private final SecureRandom random = new SecureRandom();

    public AssistantService(AssistantRepository assistantRepository, CurrentUserService currentUserService,
                            AssistantFileStorage fileStorage, AuditRecorder auditRecorder,
                            ObjectMapper objectMapper) {
        this.assistantRepository = assistantRepository;
        this.currentUserService = currentUserService;
        this.fileStorage = fileStorage;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // assistant（/system/assistant）
    // ------------------------------------------------------------------

    /** GET /system/assistant（type != 4，对齐 SQLBot query）。 */
    public List<Map<String, Object>> list() {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : assistantRepository.list(oid)) {
            result.add(view(row));
        }
        return result;
    }

    /** GET /system/assistant/advanced_application（高级应用，供训练/术语/提示词选择）。 */
    public List<Map<String, Object>> advancedApplications() {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : assistantRepository.listAdvancedApplications(oid)) {
            result.add(view(row));
        }
        return result;
    }

    /** POST /system/assistant（创建；生成 app_id/app_secret 嵌入密钥）。 */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        long oid = currentUserService.oidOf(user);
        String name = requiredText(body, "name");
        String domain = requiredText(body, "domain");
        int type = intOf(body.get("type"), 0);
        long createOid = type == 4 ? 1L : oid;
        long id = assistantRepository.insert(name, type, domain, Rows.str(body, "description"),
                Rows.str(body, "configuration"), newAppId(), newAppSecret(), createOid,
                Rows.bool(body, "enable_custom_model", false), Rows.str(body, "custom_model"),
                System.currentTimeMillis() / 1000);
        auditRecorder.success(AuditOperationType.CREATE, AuditModule.APPLICATION, String.valueOf(id), name);
        return view(assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found.simple"))));
    }

    /** PUT /system/assistant（更新；非 admin 不允许跨工作空间移动）。 */
    @Transactional
    public void update(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        boolean admin = currentUserService.isAdmin(user);
        long id = Rows.lng(body, "id", 0L);
        Map<String, Object> existing = assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found", id)));
        if (!admin && Rows.lng(existing, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(messageResolver.get("assistant.not.found", id));
        }
        Long oid = admin ? Rows.lngOrNull(body, "oid") : Rows.lngOrNull(existing, "oid");
        assistantRepository.update(id, requiredText(body, "name"), intOf(body.get("type"), 0),
                requiredText(body, "domain"), Rows.str(body, "description"), Rows.str(body, "configuration"),
                oid, Rows.bool(body, "enable_custom_model", false), Rows.str(body, "custom_model"));
        auditRecorder.success(AuditOperationType.UPDATE, AuditModule.APPLICATION, String.valueOf(id));
    }

    /** DELETE /system/assistant/{id}。 */
    @Transactional
    public void delete(long id) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        Map<String, Object> existing = assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found", id)));
        if (!currentUserService.isAdmin(user)
                && Rows.lng(existing, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(messageResolver.get("assistant.not.found", id));
        }
        assistantRepository.delete(id);
        auditRecorder.success(AuditOperationType.DELETE, AuditModule.APPLICATION, String.valueOf(id));
    }

    /**
     * PATCH /system/assistant/ui（外观配置；对齐 SQLBot ui 端点）：
     * multipart 表单 data（JSON）+ files（logo/float_icon，文件名以 {@code ,<flag>} 结尾）。
     */
    @Transactional
    public void updateUi(String dataJson, List<MultipartFile> files) throws Exception {
        currentUserService.requireWsAdmin();
        Map<String, Object> uiSchema = objectMapper.readValue(dataJson,
                new TypeReference<Map<String, Object>>() {
                });
        long id = Rows.lng(uiSchema, "id", 0L);
        Map<String, Object> existing = assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found", id)));
        Map<String, Object> config = parseConfig(Rows.str(existing, "configuration"));
        if (files != null) {
            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
                int comma = filename.lastIndexOf(',');
                String flag = comma > 0 ? filename.substring(comma + 1).trim() : "";
                if (!"logo".equals(flag) && !"float_icon".equals(flag)) {
                    throw new BadRequestException(messageResolver.get("assistant.file.flag.unsupported", flag));
                }
                config.put(flag, fileStorage.save(file, filename));
            }
        }
        for (Map.Entry<String, Object> entry : uiSchema.entrySet()) {
            String key = entry.getKey();
            if ("id".equals(key) || key.startsWith("__")) {
                continue;
            }
            config.put(key, entry.getValue());
        }
        assistantRepository.updateConfiguration(id, objectMapper.writeValueAsString(config));
        auditRecorder.success(AuditOperationType.UPDATE, AuditModule.APPLICATION, String.valueOf(id));
    }

    // ------------------------------------------------------------------
    // embedded（/system/embedded，type=4 页面嵌入）
    // ------------------------------------------------------------------

    /** GET /system/embedded/{page_num}/{page_size}。 */
    public PageResult<Map<String, Object>> pager(int pageNum, int pageSize, String keyword) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        int size = Math.max(10, pageSize);
        List<Map<String, Object>> rows = assistantRepository.page(oid, keyword, pageNum, size);
        long total = assistantRepository.count(oid, keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(view(row));
        }
        return new PageResult<>(items, total, pageNum, size);
    }

    /** GET /system/embedded/{id}。 */
    public Map<String, Object> editor(long id) {
        currentUserService.requireWsAdmin();
        return view(assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found", id))));
    }

    /** POST /system/embedded（创建页面嵌入应用）。 */
    @Transactional
    public Map<String, Object> createEmbedded(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        String name = requiredText(body, "name");
        String domain = requiredText(body, "domain");
        long id = assistantRepository.insert(name, 4, domain, Rows.str(body, "description"),
                Rows.str(body, "configuration"), newAppId(), newAppSecret(),
                currentUserService.oidOf(user), Rows.bool(body, "enable_custom_model", false),
                Rows.str(body, "custom_model"), System.currentTimeMillis() / 1000);
        auditRecorder.success(AuditOperationType.CREATE, AuditModule.APPLICATION, String.valueOf(id), name);
        return view(assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found.simple"))));
    }

    /** PUT /system/embedded（更新页面嵌入应用）。 */
    @Transactional
    public void updateEmbedded(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        boolean admin = currentUserService.isAdmin(user);
        long id = Rows.lng(body, "id", 0L);
        Map<String, Object> existing = assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found", id)));
        if (!admin && Rows.lng(existing, "oid", 0L) != currentUserService.oidOf(user)) {
            throw new BadRequestException(messageResolver.get("assistant.embedded.not.found", id));
        }
        Long oid = admin ? Rows.lngOrNull(body, "oid") : Rows.lngOrNull(existing, "oid");
        assistantRepository.update(id, requiredText(body, "name"), intOf(body.get("type"), 4),
                requiredText(body, "domain"), Rows.str(body, "description"), Rows.str(body, "configuration"),
                oid, Rows.bool(body, "enable_custom_model", false), Rows.str(body, "custom_model"));
        auditRecorder.success(AuditOperationType.UPDATE, AuditModule.APPLICATION, String.valueOf(id));
    }

    /** DELETE /system/embedded（body 为 id 数组）。 */
    @Transactional
    public void deleteEmbedded(List<Long> ids) {
        currentUserService.requireWsAdmin();
        assistantRepository.deleteByIds(ids);
        List<String> strIds = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                strIds.add(String.valueOf(id));
            }
        }
        auditRecorder.success(AuditOperationType.DELETE, AuditModule.APPLICATION, String.join(",", strIds));
    }

    /** PATCH /system/embedded/secret/{id}（重置 app_secret）。 */
    @Transactional
    public String resetSecret(long id) {
        currentUserService.requireWsAdmin();
        assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found", id)));
        String secret = newAppSecret();
        assistantRepository.updateAppSecret(id, secret);
        auditRecorder.success(AuditOperationType.UPDATE, AuditModule.APPLICATION, String.valueOf(id));
        return secret;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Rows.lng(row, "id", 0L));
        view.put("name", Rows.str(row, "name"));
        view.put("type", Rows.intg(row, "type", 0));
        view.put("domain", Rows.str(row, "domain"));
        view.put("description", Rows.str(row, "description"));
        view.put("configuration", Rows.str(row, "configuration"));
        view.put("create_time", Rows.lng(row, "create_time", 0L));
        view.put("app_id", Rows.str(row, "app_id"));
        view.put("app_secret", Rows.str(row, "app_secret"));
        view.put("oid", Rows.lng(row, "oid", 0L));
        view.put("enable_custom_model", Rows.bool(row, "enable_custom_model", false));
        view.put("custom_model", Rows.str(row, "custom_model"));
        return view;
    }

    private Map<String, Object> parseConfig(String configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(configuration, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String newAppId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String newAppSecret() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String requiredText(Map<String, Object> body, String key) {
        String value = Rows.str(body, key);
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("assistant.param.required", key));
        }
        return value.trim();
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
