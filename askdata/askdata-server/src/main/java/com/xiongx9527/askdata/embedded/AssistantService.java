package com.xiongx9527.askdata.embedded;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.audit.AuditModule;
import com.xiongx9527.askdata.common.audit.AuditLog;
import com.xiongx9527.askdata.common.audit.AuditOperationType;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.service.DatasourceService;
import com.xiongx9527.askdata.system.audit.AuditRecorder;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final DatasourceRepository datasourceRepository;

    private final CurrentUserService currentUserService;

    private final AssistantFileStorage fileStorage;

    private final AuditRecorder auditRecorder;

    private final ObjectMapper objectMapper;

    private final DatasourceService datasourceService;

    private final DynamicDatasourceResolver dynamicDatasourceResolver;

    private final SecureRandom random = new SecureRandom();

    public AssistantService(AssistantRepository assistantRepository, DatasourceRepository datasourceRepository,
                            CurrentUserService currentUserService, AssistantFileStorage fileStorage,
                            AuditRecorder auditRecorder, ObjectMapper objectMapper,
                            DatasourceService datasourceService,
                            DynamicDatasourceResolver dynamicDatasourceResolver) {
        this.assistantRepository = assistantRepository;
        this.datasourceRepository = datasourceRepository;
        this.currentUserService = currentUserService;
        this.fileStorage = fileStorage;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.datasourceService = datasourceService;
        this.dynamicDatasourceResolver = dynamicDatasourceResolver;
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

    /**
     * GET /system/assistant/ds（对齐 SQLBot assistant.py {@code /ds}）：
     * 返回当前助手上下文可用的数据源列表（字段与 SQLBot 一致：id/name/description/type/type_name/num）。
     *
     * <p>assistantId 指定目标助手；为空时取当前用户的第一个普通助手（type=0，参考 {@link #list()}）。
     * 数据源归属由助手 configuration(JSON) 中的 {@code oid} 决定：
     * <ul>
     *   <li>type=0（普通小助手）：{@code online} 为 true（默认）时返回该 oid 下全部数据源；
     *       未上线({@code online=false})且 public_list 非空时仅返回 public_list 内的数据源；
     *       未上线且 public_list 为空时返回空数组。</li>
     *   <li>type=1（高级应用）：返回该 oid 下全部数据源（best-effort，对齐 SQLBot 调宿主 API）。</li>
     * </ul>
     *
     * <p>注意：sys_assistant 表中没有 {@code online} 列，SQLBot 的 online 是模型字段；
     * 此处从 configuration(JSON) 读取 {@code online}，缺失时默认 true（返回该 oid 全部数据源）。
     */
    public List<Map<String, Object>> datasources(Long assistantId) {
        currentUserService.requireWsAdmin();
        Map<String, Object> assistant;
        if (assistantId != null) {
            assistant = assistantRepository.findById(assistantId)
                    .orElseThrow(() -> new BadRequestException("Assistant with id " + assistantId + " not found"));
        } else {
            // 默认：当前用户的第一个普通助手（type=0）
            long oid = currentUserService.oidOf(currentUserService.currentUser());
            assistant = assistantRepository.list(oid).stream()
                    .filter(row -> Rows.intg(row, "type", 0) == 0)
                    .findFirst().orElse(null);
            if (assistant == null) {
                return new ArrayList<>();
            }
        }
        int type = Rows.intg(assistant, "type", 0);
        Map<String, Object> config = parseConfig(Rows.str(assistant, "configuration"));
        long oid = Rows.lng(config, "oid", 0L);
        // sys_assistant 无 online 列；从 configuration 读取，默认 true
        boolean online = Rows.bool(config, "online", true);
        List<Long> publicList = Rows.longList(config.get("public_list"));

        List<Map<String, Object>> all = datasourceRepository.listByOid(oid);
        List<Map<String, Object>> result = new ArrayList<>();
        if (type == 0) {
            if (!online) {
                if (publicList.isEmpty()) {
                    return result;
                }
                Set<Long> allowed = new HashSet<>(publicList);
                for (Map<String, Object> ds : all) {
                    if (allowed.contains(Rows.lng(ds, "id", 0L))) {
                        result.add(datasourceView(ds));
                    }
                }
                return result;
            }
            for (Map<String, Object> ds : all) {
                result.add(datasourceView(ds));
            }
            return result;
        } else if (type == 1) {
            for (Map<String, Object> ds : all) {
                result.add(datasourceView(ds));
            }
            return result;
        }
        return result;
    }

    /**
     * 高级应用（assistant type 1/3）按请求从宿主拉取动态数据源（A8）：解析宿主下发的连接配置并
     * 校验连接可用性，但<b>不持久化</b>（不写入 core_datasource，也不返回连接密钥）。
     *
     * @param assistantId 高级应用 id（type 必须为 1 或 3）
     * @param hostConfig  宿主下发配置：{@code {type: "mysql", name?: "...", configuration: {...}}}
     * @return 动态数据源视图 {@code {id:null, name, type, type_name, connected}}
     */
    public Map<String, Object> checkDynamicDatasource(long assistantId, Map<String, Object> hostConfig) {
        currentUserService.requireWsAdmin();
        Map<String, Object> assistant = assistantRepository.findById(assistantId)
                .orElseThrow(() -> new BadRequestException("Assistant with id " + assistantId + " not found"));
        int type = Rows.intg(assistant, "type", 0);
        DynamicDatasource dynamic = dynamicDatasourceResolver.resolve(type, hostConfig);
        boolean connected = datasourceService.checkConnectionTransient(dynamic.getType(), dynamic.getConf());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", null);
        view.put("name", dynamic.getName());
        view.put("type", dynamic.typeName());
        view.put("type_name", dynamic.getType().getDbName());
        view.put("connected", connected);
        return view;
    }

    /** POST /system/assistant（创建；生成 app_id/app_secret 嵌入密钥）。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.CREATE)
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
        return view(assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.not.found.simple"))));
    }

    /** PUT /system/assistant（更新；非 admin 不允许跨工作空间移动）。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
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
    }

    /** DELETE /system/assistant/{id}。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.DELETE)
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
    }

    /**
     * PATCH /system/assistant/ui（外观配置；对齐 SQLBot ui 端点）：
     * multipart 表单 data（JSON）+ files（logo/float_icon，文件名以 {@code ,<flag>} 结尾）。
     */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
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
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.CREATE)
    public Map<String, Object> createEmbedded(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> user = currentUserService.currentUser();
        String name = requiredText(body, "name");
        String domain = requiredText(body, "domain");
        long id = assistantRepository.insert(name, 4, domain, Rows.str(body, "description"),
                Rows.str(body, "configuration"), newAppId(), newAppSecret(),
                currentUserService.oidOf(user), Rows.bool(body, "enable_custom_model", false),
                Rows.str(body, "custom_model"), System.currentTimeMillis() / 1000);
        return view(assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found.simple"))));
    }

    /** PUT /system/embedded（更新页面嵌入应用）。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
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
    }

    /** DELETE /system/embedded（body 为 id 数组）。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.DELETE)
    public void deleteEmbedded(List<Long> ids) {
        currentUserService.requireWsAdmin();
        assistantRepository.deleteByIds(ids);
        List<String> strIds = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                strIds.add(String.valueOf(id));
            }
        }
    }

    /** PATCH /system/embedded/secret/{id}（重置 app_secret）。 */
    @Transactional
    @AuditLog(module = AuditModule.APPLICATION, operation = AuditOperationType.UPDATE)
    public String resetSecret(long id) {
        currentUserService.requireWsAdmin();
        assistantRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("assistant.embedded.not.found", id)));
        String secret = newAppSecret();
        assistantRepository.updateAppSecret(id, secret);
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

    /** 数据源行 → 对外视图（对齐 SQLBot /ds 返回字段：id/name/description/type/type_name/num）。 */
    private Map<String, Object> datasourceView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", Rows.lng(row, "id", 0L));
        view.put("name", Rows.str(row, "name"));
        view.put("description", Rows.str(row, "description"));
        view.put("type", Rows.str(row, "type"));
        view.put("type_name", Rows.str(row, "type_name"));
        view.put("num", Rows.str(row, "num"));
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
