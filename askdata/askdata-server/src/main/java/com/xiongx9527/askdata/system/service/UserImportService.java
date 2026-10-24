package com.xiongx9527.askdata.system.service;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p><b>列集（F-17）</b>：与 SQLBot {@code get_i18n_head_list()} 一致的 8 列
 * {@code account / name / email / workspace / role / status / origin / platform_user_id}，
 * 因此 SQLBot 导出的模板可直接导入。额外容忍可选的<b>第 9 列 {@code password}</b>
 * （askdata 扩展）：不提供时使用配置 {@code askdata.user.default-password} 建号，
 * 未配置该参数则退化为运行期随机口令（对齐 F-04「无可预测默认口令」）。</p>
 *
 * <ul>
 *   <li>workspace：按名称解析工作空间；为空时归属调用管理员的工作空间，并写入 sys_user_ws 绑定；</li>
 *   <li>role：{@code admin}/{@code ws_admin}/{@code 1} → 该工作空间 weight=1；{@code user}/{@code 0}/空 → weight=0；</li>
 *   <li>status：{@code 1}/{@code enabled}/{@code true} → 启用，{@code 0}/{@code disabled}/{@code false}/空 → 停用/默认启用；</li>
 *   <li>origin：整数来源标识（0=本地）；非 0 时要求 platform_user_id，并写入 sys_user_platform 绑定；</li>
 *   <li>失败行（重复账号/邮箱格式错误/必填缺失/工作空间或角色或状态或来源非法）收集行号+原因+原文，
 *       生成失败记录 Excel 经通用文件存储落地，返回 fileId 供下载。</li>
 * </ul>
 * 三个端点均仅管理员可调（对齐 SQLBot SqlbotPermission(role=['admin'])）。
 */
@Service
public class UserImportService {

    /** 导入表头（与 SQLBot 用户导入模板一致，大小写不敏感）。 */
    static final List<String> IMPORT_HEADERS = Arrays.asList(
            "account", "name", "email", "workspace", "role", "status", "origin", "platform_user_id");

    /** askdata 扩展：可选第 9 列，显式口令（缺省时用默认口令）。 */
    static final String OPTIONAL_PASSWORD_HEADER = "password";

    private static final List<ExcelUtils.Column> TEMPLATE_COLUMNS = Arrays.asList(
            new ExcelUtils.Column("account", "account"),
            new ExcelUtils.Column("name", "name"),
            new ExcelUtils.Column("email", "email"),
            new ExcelUtils.Column("workspace", "workspace"),
            new ExcelUtils.Column("role", "role"),
            new ExcelUtils.Column("status", "status"),
            new ExcelUtils.Column("origin", "origin"),
            new ExcelUtils.Column("platform_user_id", "platform_user_id"));

    private static final List<ExcelUtils.Column> ERROR_COLUMNS = buildErrorColumns();

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

    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    public UserImportService(UserRepository userRepository, UserWsRepository userWsRepository,
                             WorkspaceRepository workspaceRepository, PlatformRepository platformRepository,
                             CurrentUserService currentUserService, UserService userService,
                             IdGenerator idGenerator, AskdataFileStorage fileStorage,
                             com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
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

    /** 生成用户导入模板（8 列 SQLBot 列集 + 两行示例）。 */
    public byte[] importTemplate() {
        currentUserService.requireAdmin();
        List<ExcelUtils.MapStringArray> rows = Arrays.asList(
                sampleRow("sqlbot1", "sqlbot_employee1", "employee1@sqlbot.com", "", "user", "1", "0", ""),
                sampleRow("sqlbot2", "sqlbot_employee2", "employee2@sqlbot.com", "", "ws_admin", "1", "0", ""));
        try {
            return ExcelUtils.writeXlsx(TEMPLATE_COLUMNS, rows);
        } catch (IOException e) {
            throw new BadRequestException(messageResolver.get("user.import.template.failed", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // POST /user/batchImport
    // ------------------------------------------------------------------

    /**
     * 解析上传 Excel 并逐行建号。
     * 返回 {@code {fileId, total, success, failed}}；存在失败行时 fileId 为非空字符串
     * （失败记录已落盘），无失败行时 fileId 为 null。
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
            throw new BadRequestException(messageResolver.get("i18n_excel_import.col_num_not_match"));
        }
        boolean withPasswordColumn = rows.get(0).length > IMPORT_HEADERS.size();

        long defaultOid = currentUserService.oidOf(currentUserService.currentUser());
        Set<String> batchAccounts = new HashSet<>();
        List<ErrorRow> errorRows = new ArrayList<>();
        int success = 0;
        int total = 0;
        for (int index = 1; index < rows.size(); index++) {
            String[] row = rows.get(index);
            total++;
            String account = cell(row, 0);
            String name = cell(row, 1);
            String email = cell(row, 2);
            String workspaceName = cell(row, 3);
            String role = cell(row, 4);
            String statusText = cell(row, 5);
            String originText = cell(row, 6);
            String platformUid = cell(row, 7);
            String password = withPasswordColumn ? cell(row, IMPORT_HEADERS.size()) : "";
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

        String fileId = null;
        if (!errorRows.isEmpty()) {
            fileId = writeErrorRecord(errorRows);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", fileId);
        result.put("total", total);
        result.put("success", success);
        result.put("failed", errorRows.size());
        return result;
    }

    // ------------------------------------------------------------------
    // GET /user/errorRecord/{fileId}
    // ------------------------------------------------------------------

    /** 按 fileId 读取失败记录 Excel 字节；文件不存在时抛 BadRequestException。 */
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
        if (!workspaceName.isEmpty() && !workspaceRepository.findByName(workspaceName).isPresent()) {
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

    private long resolveWorkspaceOid(String workspaceName, long defaultOid) {
        if (workspaceName == null || workspaceName.isEmpty()) {
            return defaultOid;
        }
        return workspaceRepository.findByName(workspaceName)
                .map(row -> ((Number) row.get("id")).longValue())
                .orElse(defaultOid);
    }

    private static boolean isKnownRole(String role) {
        if (role == null || role.isEmpty()) {
            return true;
        }
        String value = role.trim().toLowerCase(Locale.ROOT);
        return "admin".equals(value) || "ws_admin".equals(value) || "1".equals(value)
                || "user".equals(value) || "member".equals(value) || "0".equals(value);
    }

    private static int roleWeight(String role) {
        if (role == null || role.isEmpty()) {
            return 0;
        }
        String value = role.trim().toLowerCase(Locale.ROOT);
        return "admin".equals(value) || "ws_admin".equals(value) || "1".equals(value) ? 1 : 0;
    }

    private static boolean isKnownStatus(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }
        String value = status.trim().toLowerCase(Locale.ROOT);
        return "1".equals(value) || "0".equals(value) || "enabled".equals(value) || "disabled".equals(value)
                || "true".equals(value) || "false".equals(value);
    }

    private static int statusOf(String status) {
        if (status == null || status.isEmpty()) {
            return 1;
        }
        String value = status.trim().toLowerCase(Locale.ROOT);
        return "0".equals(value) || "disabled".equals(value) || "false".equals(value) ? 0 : 1;
    }

    private static boolean isKnownOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        try {
            return Integer.parseInt(origin.trim()) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int originOf(String origin) {
        if (origin == null || origin.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(origin.trim());
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
                int column = IMPORT_HEADERS.indexOf(key);
                return column < 0 ? "" : cell(errorRow.rawRow, column);
            });
        }
        byte[] content;
        try {
            content = ExcelUtils.writeXlsx(ERROR_COLUMNS, rows);
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

    private static List<ExcelUtils.Column> buildErrorColumns() {
        List<ExcelUtils.Column> columns = new ArrayList<>();
        columns.add(new ExcelUtils.Column("row", "row"));
        for (String header : IMPORT_HEADERS) {
            columns.add(new ExcelUtils.Column(header, header));
        }
        columns.add(new ExcelUtils.Column("reason", "reason"));
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

    /** 表头校验：前 8 列必须逐字匹配 SQLBot 列集；可选第 9 列必须是 password。 */
    private static boolean headerMatches(String[] header) {
        if (header.length < IMPORT_HEADERS.size()) {
            return false;
        }
        for (int i = 0; i < IMPORT_HEADERS.size(); i++) {
            if (!IMPORT_HEADERS.get(i).equalsIgnoreCase(cell(header, i))) {
                return false;
            }
        }
        if (header.length > IMPORT_HEADERS.size()) {
            return OPTIONAL_PASSWORD_HEADER.equalsIgnoreCase(cell(header, IMPORT_HEADERS.size()));
        }
        return true;
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
