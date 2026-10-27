package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.platform.PlatformRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.common.storage.AskdataFileStorage;
import com.xiongx9527.askdata.common.storage.StoredFile;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 用户 Excel 批量导入（对齐 SQLBot apps/system/crud/user_excel.py 的
 * downTemplate/batchUpload/generate_error_file/download_error_file）。
 *
 * <p><b>列集（F-17 / A23）</b>：与 SQLBot {@code get_i18n_head_list()} 一致的 8 列
 * {@code account / name / email / workspace / role / status / origin / platform_user_id}。
 * SQLBot 的模板表头是<b>本地化文案</b>（{@code trans('i18n_user.account')} 等），
 * 因此这里同样按请求 locale 从消息包取 {@code i18n_user.*} 文案作为表头，
 * 解析时同时接受 5 个 locale 的本地化表头与英文列名（历史模板可继续导入）。
 * 额外容忍可选的<b>第 9 列 {@code password}</b>（askdata 扩展）：不提供时使用配置
 * {@code askdata.user.default-password} 建号，未配置该参数则退化为运行期随机口令
 * （对齐 F-04「无可预测默认口令」）。</p>
 *
 * <ul>
 *   <li>workspace：按名称解析工作空间；为空或填写「默认工作空间」本地化文案时归属调用管理员的工作空间，
 *       并写入 sys_user_ws 绑定；</li>
 *   <li>role：本地化「管理员/普通成员」（及 {@code admin}/{@code ws_admin}/{@code 1}、
 *       {@code user}/{@code 0}/空）→ 工作空间 weight 1/0；</li>
 *   <li>status：本地化「已启用/已禁用」（及 {@code 1}/{@code enabled}/{@code true}、
 *       {@code 0}/{@code disabled}/{@code false}）→ 1/0，空值按启用；</li>
 *   <li>origin：本地化「本地创建」（及 {@code 0}/{@code local}）→ 0；非 0 整数来源要求
 *       platform_user_id，并写入 sys_user_platform 绑定；</li>
 *   <li>失败行（重复账号/邮箱格式错误/必填缺失/工作空间或角色或状态或来源非法）收集行号+原因+原文，
 *       生成失败记录 Excel 经通用文件存储落地，返回 dataKey 供下载。</li>
 * </ul>
 *
 * <p><b>返回契约（A23）</b>：{@code {successCount, errorCount, dataKey}}——与 SQLBot
 * {@code UploadResultDTO} 逐字一致，前端 {@code UserImport.vue} / {@code User.vue} 直接消费。</p>
 *
 * <p>三个端点均仅管理员可调（对齐 SQLBot SqlbotPermission(role=['admin'])）。</p>
 */
@Service
public class UserImportService {

    /** 表头消息键（顺序即列序，对齐 SQLBot {@code get_i18n_head_list()}）。 */
    public static final List<String> IMPORT_HEADER_KEYS = Collections.unmodifiableList(Arrays.asList(
            "i18n_user.account", "i18n_user.name", "i18n_user.email", "i18n_user.workspace",
            "i18n_user.role", "i18n_user.status", "i18n_user.origin", "i18n_user.platform_user_id"));

    /** 历史（英文列名）表头：askdata 早期模板与 SQLBot 之外的直连脚本继续可导入。 */
    private static final List<String> LEGACY_HEADERS = Collections.unmodifiableList(Arrays.asList(
            "account", "name", "email", "workspace", "role", "status", "origin", "platform_user_id"));

    /** askdata 扩展：可选第 9 列，显式口令（缺省时用默认口令）。 */
    static final String OPTIONAL_PASSWORD_HEADER = "password";

    /** 支持的语言（模板与枚举文案覆盖这 5 个 locale）。 */
    private static final List<Locale> SUPPORTED_LOCALES = Collections.unmodifiableList(Arrays.asList(
            Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.TRADITIONAL_CHINESE, Locale.KOREAN,
            new Locale("th", "TH")));

    /** 本地化「默认工作空间」文案（模板示例行使用；解析时映射为调用者的工作空间）。 */
    private static final String DEFAULT_WORKSPACE_KEY = "i18n_default_workspace";

    private static final String ERROR_FILE_PREFIX = "user_import_error_";

    private static final long ERROR_FILE_TTL_MILLIS = 24L * 3600 * 1000;

    /** 对齐 SQLBot apps/system/schemas/system_schema.py EMAIL_REGEX。 */
    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[a-zA-Z0-9]+([._-][a-zA-Z0-9]+)*@([a-zA-Z0-9]+(-[a-zA-Z0-9]+)*\\.)+[a-zA-Z]{2,}$");

    /** fileId 仅允许安全字符（UUID hex 去横线），避免路径穿越。 */
    private static final Pattern FILE_ID_REGEX = Pattern.compile("[0-9a-zA-Z-]{1,64}");

    private final UserRepository userRepository;

    private final UserWsRepository userWsRepository;

    private final WorkspaceRepository workspaceRepository;

    private final PlatformRepository platformRepository;

    private final CurrentUserService currentUserService;

    private final UserService userService;

    private final IdGenerator idGenerator;

    private final AskdataFileStorage fileStorage;

    /** fileId -> 失败记录文件在通用存储中的 url（临时文件，24h TTL）。 */
    private final Map<String, String> errorFileUrls = new ConcurrentHashMap<>();

    /** fileId -> 写入时间戳（毫秒），用于 TTL 清理。 */
    private final Map<String, Long> errorFileTimes = new ConcurrentHashMap<>();

    private final MessageResolver messageResolver;

    public UserImportService(UserRepository userRepository, UserWsRepository userWsRepository,
                             WorkspaceRepository workspaceRepository, PlatformRepository platformRepository,
                             CurrentUserService currentUserService, UserService userService,
                             IdGenerator idGenerator, AskdataFileStorage fileStorage,
                             MessageResolver messageResolver) {
        this.userRepository = userRepository;
        this.userWsRepository = userWsRepository;
        this.workspaceRepository = workspaceRepository;
        this.platformRepository = platformRepository;
        this.currentUserService = currentUserService;
        this.userService = userService;
        this.idGenerator = idGenerator;
        this.fileStorage = fileStorage;
        this.messageResolver = messageResolver;
    }

    // ------------------------------------------------------------------
    // GET /user/template
    // ------------------------------------------------------------------

    /**
     * 生成用户导入模板（8 列本地化表头 + 两行本地化示例）。
     *
     * <p>对齐 SQLBot {@code downTemplate}：表头与示例值都走 {@code trans()}，
     * 因此中文环境下载到「账号/姓名/…」与「管理员/普通成员」「已启用/已禁用」「本地创建」，
     * 直接回传导入即可通过校验。</p>
     */
    public byte[] importTemplate() {
        currentUserService.requireAdmin();
        String admin = label("i18n_user.administrator");
        String member = label("i18n_user.ordinary_member");
        String enabled = label("i18n_user.status_enabled");
        String disabled = label("i18n_user.status_disabled");
        String local = label("i18n_user.local_creation");
        String defaultWorkspace = label(DEFAULT_WORKSPACE_KEY);
        List<ExcelUtils.MapStringArray> rows = Arrays.asList(
                sampleRow("sqlbot1", "sqlbot_employee1", "employee1@sqlbot.com", defaultWorkspace, member,
                        enabled, local, ""),
                sampleRow("sqlbot2", "sqlbot_employee2", "employee2@sqlbot.com", defaultWorkspace, admin,
                        disabled, local, ""));
        try {
            return ExcelUtils.writeXlsx(templateColumns(), rows);
        } catch (IOException e) {
            throw new BadRequestException(messageResolver.get("user.import.template.failed", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // POST /user/batchImport
    // ------------------------------------------------------------------

    /**
     * 解析上传 Excel 并逐行建号。
     * 返回 {@code {successCount, errorCount, dataKey}}（对齐 SQLBot UploadResultDTO）；
     * 存在失败行时 dataKey 为非空字符串（失败记录已落盘），无失败行时 dataKey 为 null。
     */
    public Map<String, Object> batchImport(MultipartFile file) {
        currentUserService.requireAdmin();
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(messageResolver.get("user.import.file.empty"));
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!originalName.endsWith(".xlsx") && !originalName.endsWith(".xls")) {
            throw new BadRequestException(messageResolver.get("ds.only.xlsx"));
        }
        List<String[]> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = readRows(inputStream);
        } catch (IOException e) {
            throw new BadRequestException(messageResolver.get("user.import.read.failed", e.getMessage()));
        }
        if (rows.isEmpty() || !headerMatches(rows.get(0))) {
            throw new BadRequestException(messageResolver.get("i18n_excel_col_num_not_match"));
        }
        boolean withPasswordColumn = rows.get(0).length > IMPORT_HEADER_KEYS.size();

        long defaultOid = currentUserService.oidOf(currentUserService.currentUser());
        Set<String> batchAccounts = new HashSet<>();
        List<ErrorRow> errorRows = new ArrayList<>();
        int success = 0;
        for (int index = 1; index < rows.size(); index++) {
            String[] row = rows.get(index);
            String account = cell(row, 0);
            String name = cell(row, 1);
            String email = cell(row, 2);
            String workspaceName = cell(row, 3);
            String role = cell(row, 4);
            String statusText = cell(row, 5);
            String originText = cell(row, 6);
            String platformUid = cell(row, 7);
            String password = withPasswordColumn ? cell(row, IMPORT_HEADER_KEYS.size()) : "";
            String reason = validateRow(account, name, email, workspaceName, role, statusText, originText,
                    platformUid, batchAccounts);
            if (reason != null) {
                errorRows.add(new ErrorRow(index + 1, row, reason));
                continue;
            }
            try {
                long oid = resolveWorkspaceOid(workspaceName, defaultOid);
                createUser(account, name, password, email, oid, roleWeight(role), statusOf(statusText),
                        originOf(originText), platformUid);
                batchAccounts.add(account.toLowerCase(Locale.ROOT));
                success++;
            } catch (RuntimeException e) {
                errorRows.add(new ErrorRow(index + 1, row, e.getMessage()));
            }
        }

        String dataKey = errorRows.isEmpty() ? null : writeErrorRecord(errorRows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("successCount", success);
        result.put("errorCount", errorRows.size());
        result.put("dataKey", dataKey);
        return result;
    }

    // ------------------------------------------------------------------
    // GET /user/errorRecord/{fileId}
    // ------------------------------------------------------------------

    /** 按 dataKey（失败记录 fileId）读取失败记录 Excel 字节；文件不存在时抛 BadRequestException。 */
    public byte[] errorRecord(String fileId) {
        currentUserService.requireAdmin();
        if (fileId == null || !FILE_ID_REGEX.matcher(fileId).matches()) {
            throw new BadRequestException(messageResolver.get("user.import.error_file.not_found", fileId));
        }
        String url = errorFileUrls.get(fileId);
        if (url == null) {
            throw new BadRequestException(messageResolver.get("user.import.error_file.not_found", fileId));
        }
        try {
            return fileStorage.read(url);
        } catch (RuntimeException e) {
            throw new BadRequestException(messageResolver.get("user.import.error_file.not_found", fileId));
        }
    }

    // ------------------------------------------------------------------
    // 表头 / 枚举本地化
    // ------------------------------------------------------------------

    /** 当前 locale 的模板表头。 */
    private List<String> localizedHeaders() {
        List<String> headers = new ArrayList<>();
        for (String key : IMPORT_HEADER_KEYS) {
            headers.add(label(key));
        }
        return headers;
    }

    private List<ExcelUtils.Column> templateColumns() {
        List<String> headers = localizedHeaders();
        List<ExcelUtils.Column> columns = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            columns.add(new ExcelUtils.Column(headers.get(i), LEGACY_HEADERS.get(i)));
        }
        return columns;
    }

    /**
     * 表头校验：逐列匹配本地化表头（当前 locale / 其余 4 个 locale）或英文列名；
     * 可选第 9 列必须是 password。对齐 SQLBot {@code validate_head} 的「表头必须逐列对上」语义，
     * 但放宽到 5 个 locale（前端语言与模板下载语言不一致时不至于整体失败）。
     */
    private boolean headerMatches(String[] header) {
        if (header.length < IMPORT_HEADER_KEYS.size()) {
            return false;
        }
        for (int i = 0; i < IMPORT_HEADER_KEYS.size(); i++) {
            if (!acceptedHeaderLabels(i).contains(normalize(cell(header, i)))) {
                return false;
            }
        }
        if (header.length > IMPORT_HEADER_KEYS.size()) {
            return OPTIONAL_PASSWORD_HEADER.equalsIgnoreCase(cell(header, IMPORT_HEADER_KEYS.size()));
        }
        return true;
    }

    /** 第 i 列可接受的表头文案（5 个 locale 的本地化文案 + 英文列名）。 */
    private Set<String> acceptedHeaderLabels(int index) {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(normalize(LEGACY_HEADERS.get(index)));
        for (Locale locale : SUPPORTED_LOCALES) {
            labels.add(normalize(messageResolver.get(IMPORT_HEADER_KEYS.get(index), locale)));
        }
        return labels;
    }

    /** 按当前 locale 取消息包文案。 */
    private String label(String key) {
        return messageResolver.get(key);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 建号：复用 sys_user 插入与 MD5 口令（对齐 UserService.create 的字段语义），并写工作空间/平台绑定。 */
    private void createUser(String account, String name, String password, String email, long oid, int weight,
                            int status, int origin, String platformUid) {
        long id = idGenerator.nextId();
        String plain = password == null || password.isEmpty() ? userService.defaultPasswordForCreate() : password;
        userRepository.insert(id, account, name, CryptoUtil.md5Hex(plain), email, oid, status,
                System.currentTimeMillis(), "zh-CN", origin, "[]");
        if (oid != 0L && !userWsRepository.find(id, oid).isPresent()) {
            userWsRepository.insert(idGenerator.nextId(), id, oid, weight);
        }
        if (platformUid != null && !platformUid.isEmpty()) {
            platformRepository.upsertBinding(idGenerator.nextId(), id, origin, platformUid);
        }
    }

    /** 行校验：返回 null 表示通过，否则返回失败原因（必填缺失/格式错误/重复）。 */
    private String validateRow(String account, String name, String email, String workspaceName, String role,
                               String statusText, String originText, String platformUid,
                               Set<String> batchAccounts) {
        if (account.isEmpty()) {
            return messageResolver.get("i18n_miss_args", java.util.Map.of("key", "account"));
        }
        if (name.isEmpty()) {
            return messageResolver.get("i18n_miss_args", java.util.Map.of("key", "name"));
        }
        if (email.isEmpty()) {
            return messageResolver.get("i18n_miss_args", java.util.Map.of("key", "email"));
        }
        if (!EMAIL_REGEX.matcher(email).matches()) {
            return messageResolver.get("user.email.invalid", email);
        }
        if (!isDefaultWorkspaceLabel(workspaceName) && !workspaceName.isEmpty()
                && !workspaceRepository.findByName(workspaceName).isPresent()) {
            return messageResolver.get("workspace.not.found", workspaceName);
        }
        if (!isKnownRole(role)) {
            return messageResolver.get("user.import.role.invalid", role);
        }
        if (!isKnownStatus(statusText)) {
            return messageResolver.get("user.import.status.invalid", statusText);
        }
        if (!isKnownOrigin(originText)) {
            return messageResolver.get("user.import.origin.invalid", originText);
        }
        if (originOf(originText) != 0 && platformUid.isEmpty()) {
            return messageResolver.get("user.import.platform_uid.required");
        }
        if (batchAccounts.contains(account.toLowerCase(Locale.ROOT))
                || userRepository.existsAccount(account, null)) {
            return messageResolver.get("user.account.exists", account);
        }
        return null;
    }

    /**
     * workspace 单元格是否为「默认工作空间」的本地化文案（SQLBot 模板示例行的取值）。
     * 该文案不是库中真实工作空间名，按「使用调用者工作空间」处理。
     */
    private boolean isDefaultWorkspaceLabel(String workspaceName) {
        if (workspaceName == null || workspaceName.isEmpty()) {
            return false;
        }
        String value = normalize(workspaceName);
        for (Locale locale : SUPPORTED_LOCALES) {
            if (value.equals(normalize(messageResolver.get(DEFAULT_WORKSPACE_KEY, locale)))) {
                return true;
            }
        }
        // 库里默认工作空间存的是消息键本身（展示时再本地化）
        return value.equals(normalize(DEFAULT_WORKSPACE_KEY));
    }

    private long resolveWorkspaceOid(String workspaceName, long defaultOid) {
        if (workspaceName == null || workspaceName.isEmpty() || isDefaultWorkspaceLabel(workspaceName)) {
            return defaultOid;
        }
        return workspaceRepository.findByName(workspaceName)
                .map(row -> ((Number) row.get("id")).longValue())
                .orElse(defaultOid);
    }

    /** 角色合法值：本地化「管理员/普通成员」+ admin/ws_admin/user/member + 1/0 + 空。 */
    private boolean isKnownRole(String role) {
        return role == null || role.isEmpty() || roleWeight(role) >= 0;
    }

    /**
     * 角色 → 工作空间 weight：管理员 = 1，普通成员 = 0，非法 = -1。
     * 本地化文案从消息包 {@code i18n_user.administrator} / {@code i18n_user.ordinary_member} 取
     * （5 个 locale 都接受，对齐 SQLBot 模板示例值）。
     */
    private int roleWeight(String role) {
        if (role == null || role.isEmpty()) {
            return 0;
        }
        if (matchesAny(role, "i18n_user.administrator") || matchesAny(role, "admin", "ws_admin", "1")) {
            return 1;
        }
        if (matchesAny(role, "i18n_user.ordinary_member") || matchesAny(role, "user", "member", "0")) {
            return 0;
        }
        return -1;
    }

    /** 状态合法值：本地化「已启用/已禁用」+ 1/0/enabled/disabled/true/false + 空。 */
    private boolean isKnownStatus(String status) {
        return status == null || status.isEmpty() || statusOf(status) >= 0;
    }

    /** 状态 → 1/0（空值按启用），非法返回 -1。 */
    private int statusOf(String status) {
        if (status == null || status.isEmpty()) {
            return 1;
        }
        if (matchesAny(status, "i18n_user.status_enabled") || matchesAny(status, "1", "enabled", "true")) {
            return 1;
        }
        if (matchesAny(status, "i18n_user.status_disabled") || matchesAny(status, "0", "disabled", "false")) {
            return 0;
        }
        return -1;
    }

    /** 来源合法值：本地化「本地创建」+ 0/local + 非负整数。 */
    private boolean isKnownOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        if (matchesAny(origin, "i18n_user.local_creation") || matchesAny(origin, "local", "0")) {
            return true;
        }
        try {
            return Integer.parseInt(origin.trim()) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int originOf(String origin) {
        if (origin == null || origin.isEmpty()) {
            return 0;
        }
        if (matchesAny(origin, "i18n_user.local_creation") || matchesAny(origin, "local", "0")) {
            return 0;
        }
        try {
            return Integer.parseInt(origin.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 单元格文案是否命中消息键在任一 locale 的本地化文案，或给定的字面量之一。 */
    private boolean matchesAny(String cellValue, String messageKey) {
        if (cellValue == null) {
            return false;
        }
        String value = normalize(cellValue);
        for (Locale locale : SUPPORTED_LOCALES) {
            if (value.equals(normalize(messageResolver.get(messageKey, locale)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String cellValue, String... literals) {
        String value = normalize(cellValue);
        for (String literal : literals) {
            if (value.equals(normalize(literal))) {
                return true;
            }
        }
        return false;
    }

    private String writeErrorRecord(List<ErrorRow> errorRows) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        List<ExcelUtils.MapStringArray> rows = new ArrayList<>();
        for (ErrorRow errorRow : errorRows) {
            rows.add(key -> {
                if ("row".equals(key)) {
                    return String.valueOf(errorRow.rowNumber);
                }
                if ("reason".equals(key)) {
                    return errorRow.reason == null ? "" : errorRow.reason;
                }
                int column = LEGACY_HEADERS.indexOf(key);
                return column < 0 ? "" : cell(errorRow.rawRow, column);
            });
        }
        byte[] content;
        try {
            content = ExcelUtils.writeXlsx(errorColumns(), rows);
        } catch (IOException e) {
            throw new BadRequestException(messageResolver.get("user.import.generate_error.failed", e.getMessage()));
        }
        try {
            StoredFile stored = fileStorage.save(content, ERROR_FILE_PREFIX + fileId + ".xlsx",
                    "user-import-error", AskdataFileStorage.contentTypeOf("x.xlsx"));
            errorFileUrls.put(fileId, stored.url());
            errorFileTimes.put(fileId, System.currentTimeMillis());
            cleanupExpiredErrorFiles();
        } catch (RuntimeException e) {
            throw new BadRequestException(messageResolver.get("user.import.save_error.failed", e.getMessage()));
        }
        return fileId;
    }

    /** 失败记录列：行号 + 本地化表头八列 + 原因（表头随 locale 变化）。 */
    private List<ExcelUtils.Column> errorColumns() {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column(messageResolver.get("user.import.error.row"), "row"));
        List<String> headers = localizedHeaders();
        for (int i = 0; i < headers.size(); i++) {
            columns.add(new ExcelUtils.Column(headers.get(i), LEGACY_HEADERS.get(i)));
        }
        columns.add(new ExcelUtils.Column(messageResolver.get("user.import.error.reason"), "reason"));
        return columns;
    }

    /** 清理超过 24 小时的失败记录文件（对齐 SQLBot 进程退出时清理临时文件的语义）。 */
    private void cleanupExpiredErrorFiles() {
        long deadline = System.currentTimeMillis() - ERROR_FILE_TTL_MILLIS;
        for (Map.Entry<String, Long> entry : errorFileTimes.entrySet()) {
            if (entry.getValue() < deadline) {
                String url = errorFileUrls.remove(entry.getKey());
                if (url != null) {
                    fileStorage.delete(url);
                }
            }
        }
    }

    /** 读取首个工作表全部行为字符串矩阵（跳过整行空白，xls/xlsx 均可）。 */
    private static List<String[]> readRows(InputStream inputStream) throws IOException {
        List<String[]> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                int last = Math.max(row.getLastCellNum(), 0);
                String[] values = new String[last];
                boolean blank = true;
                for (int i = 0; i < last; i++) {
                    Cell cell = row.getCell(i);
                    String text = "";
                    if (cell != null && cell.getCellType() != CellType.BLANK) {
                        text = formatter.formatCellValue(cell);
                        text = text == null ? "" : text.trim();
                    }
                    values[i] = text;
                    if (!text.isEmpty()) {
                        blank = false;
                    }
                }
                if (!blank) {
                    result.add(values);
                }
            }
        }
        return result;
    }

    private static String cell(String[] row, int index) {
        return index < row.length && row[index] != null ? row[index].trim() : "";
    }

    private static ExcelUtils.MapStringArray sampleRow(String account, String name, String email, String workspace,
                                                       String role, String status, String origin, String platformUid) {
        return key -> {
            switch (key) {
                case "account":
                    return account;
                case "name":
                    return name;
                case "email":
                    return email;
                case "workspace":
                    return workspace;
                case "role":
                    return role;
                case "status":
                    return status;
                case "origin":
                    return origin;
                default:
                    return platformUid;
            }
        };
    }

    /** 失败行：Excel 行号（含表头，从 1 开始）+ 原文 + 原因。 */
    private static final class ErrorRow {

        private final int rowNumber;

        private final String[] rawRow;

        private final String reason;

        private ErrorRow(int rowNumber, String[] rawRow, String reason) {
            this.rowNumber = rowNumber;
            this.rawRow = rawRow;
            this.reason = reason;
        }
    }
}
