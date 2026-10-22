package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.ExcelUtils;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用户 Excel 批量导入（对齐 SQLBot apps/system/crud/user_excel.py 的
 * downTemplate/batchUpload/generate_error_file/download_error_file）：
 * <ul>
 *   <li>模板列：account/name/password/email（SQLBot 用户导入的核心字段子集）；</li>
 *   <li>逐行建号：MD5 口令、oid 归属当前管理员工作空间并写入 sys_user_ws 绑定，
 *       使导入用户可立即登录（login 要求 oid≠0 且 status=1/origin=0）；</li>
 *   <li>失败行（重复账号/邮箱格式错误/必填缺失/口令过短）收集行号+原因+原文，
 *       生成失败记录 Excel 落盘到 {@code askdata.excel.path}，返回 fileId 供下载；</li>
 *   <li>errorRecord(fileId) 按 fileId 读盘，不存在时抛 BadRequestException。</li>
 * </ul>
 * 三个端点均仅管理员可调（对齐 SQLBot SqlbotPermission(role=['admin'])）。
 */
@Service
public class UserImportService {

    /** 导入表头（与模板一致，大小写不敏感）。 */
    static final List<String> IMPORT_HEADERS = Arrays.asList("account", "name", "password", "email");

    private static final List<ExcelUtils.Column> TEMPLATE_COLUMNS = Arrays.asList(
            new ExcelUtils.Column("account", "account"),
            new ExcelUtils.Column("name", "name"),
            new ExcelUtils.Column("password", "password"),
            new ExcelUtils.Column("email", "email"));

    private static final List<ExcelUtils.Column> ERROR_COLUMNS = Arrays.asList(
            new ExcelUtils.Column("row", "row"),
            new ExcelUtils.Column("account", "account"),
            new ExcelUtils.Column("name", "name"),
            new ExcelUtils.Column("password", "password"),
            new ExcelUtils.Column("email", "email"),
            new ExcelUtils.Column("reason", "reason"));

    private static final String ERROR_FILE_PREFIX = "user_import_error_";

    private static final long ERROR_FILE_TTL_MILLIS = 24L * 3600 * 1000;

    private static final int MIN_PASSWORD_LENGTH = 6;

    /** 对齐 SQLBot apps/system/schemas/system_schema.py EMAIL_REGEX。 */
    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[a-zA-Z0-9]+([._-][a-zA-Z0-9]+)*@([a-zA-Z0-9]+(-[a-zA-Z0-9]+)*\\.)+[a-zA-Z]{2,}$");

    /** fileId 仅允许安全字符（UUID hex 去横线），避免路径穿越。 */
    private static final Pattern FILE_ID_REGEX = Pattern.compile("[0-9a-zA-Z-]{1,64}");

    private final UserRepository userRepository;

    private final UserWsRepository userWsRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final Path excelDir;

    public UserImportService(UserRepository userRepository, UserWsRepository userWsRepository,
                             CurrentUserService currentUserService, IdGenerator idGenerator,
                             @Value("${askdata.excel.path:./data/excel}") String excelPath) {
        this.userRepository = userRepository;
        this.userWsRepository = userWsRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.excelDir = Paths.get(excelPath == null || excelPath.isEmpty() ? "./data/excel" : excelPath)
                .toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // GET /user/template
    // ------------------------------------------------------------------

    /** 生成用户导入模板（表头 + 两行示例，对齐 SQLBot downTemplate 的样例风格）。 */
    public byte[] importTemplate() {
        currentUserService.requireAdmin();
        List<ExcelUtils.MapStringArray> rows = Arrays.asList(
                sampleRow("sqlbot1", "sqlbot_employee1", "TemplatePwd123", "employee1@sqlbot.com"),
                sampleRow("sqlbot2", "sqlbot_employee2", "TemplatePwd123", "employee2@sqlbot.com"));
        try {
            return ExcelUtils.writeXlsx(TEMPLATE_COLUMNS, rows);
        } catch (IOException e) {
            throw new BadRequestException("Generate user import template failed: " + e.getMessage());
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
            throw new BadRequestException("Upload file is empty");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!originalName.endsWith(".xlsx") && !originalName.endsWith(".xls")) {
            throw new BadRequestException("Only support .xlsx/.xls");
        }
        List<String[]> rows;
        try (InputStream inputStream = file.getInputStream()) {
            rows = readRows(inputStream);
        } catch (IOException e) {
            throw new BadRequestException("Read excel failed: " + e.getMessage());
        }
        if (rows.isEmpty() || !headerMatches(rows.get(0))) {
            throw new BadRequestException("Excel header validation failed");
        }

        long oid = currentUserService.oidOf(currentUserService.currentUser());
        Set<String> batchAccounts = new HashSet<>();
        List<ErrorRow> errorRows = new ArrayList<>();
        int success = 0;
        int total = 0;
        for (int index = 1; index < rows.size(); index++) {
            String[] row = rows.get(index);
            total++;
            String account = cell(row, 0);
            String name = cell(row, 1);
            String password = cell(row, 2);
            String email = cell(row, 3);
            String reason = validateRow(account, name, password, email, batchAccounts);
            if (reason != null) {
                errorRows.add(new ErrorRow(index + 1, row, reason));
                continue;
            }
            try {
                createUser(account, name, password, email, oid);
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
            throw new BadRequestException("Error record file not found: [" + fileId + "]");
        }
        Path file = excelDir.resolve(ERROR_FILE_PREFIX + fileId + ".xlsx").normalize();
        if (!file.startsWith(excelDir) || !Files.isRegularFile(file)) {
            throw new BadRequestException("Error record file not found: [" + fileId + "]");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BadRequestException("Error record file not found: [" + fileId + "]");
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 建号：复用 sys_user 插入与 MD5 口令（对齐 UserService.create 的字段语义），oid 归属当前管理员空间。 */
    private void createUser(String account, String name, String password, String email, long oid) {
        long id = idGenerator.nextId();
        userRepository.insert(id, account, name, CryptoUtil.md5Hex(password), email, oid, 1,
                System.currentTimeMillis(), "zh-CN", 0, "[]");
        if (oid != 0L && !userWsRepository.find(id, oid).isPresent()) {
            userWsRepository.insert(idGenerator.nextId(), id, oid, 0);
        }
    }

    /** 行校验：返回 null 表示通过，否则返回失败原因（必填缺失/格式错误/重复）。 */
    private String validateRow(String account, String name, String password, String email,
                               Set<String> batchAccounts) {
        if (account.isEmpty()) {
            return "account is required";
        }
        if (name.isEmpty()) {
            return "name is required";
        }
        if (password.isEmpty()) {
            return "password is required";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        }
        if (email.isEmpty()) {
            return "email is required";
        }
        if (!EMAIL_REGEX.matcher(email).matches()) {
            return "Email format is invalid! [" + email + "]";
        }
        if (batchAccounts.contains(account.toLowerCase(Locale.ROOT))
                || userRepository.existsAccount(account, null)) {
            return "Account [" + account + "] already exists";
        }
        return null;
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
            throw new BadRequestException("Generate error record failed: " + e.getMessage());
        }
        try {
            Files.createDirectories(excelDir);
            Files.write(excelDir.resolve(ERROR_FILE_PREFIX + fileId + ".xlsx"), content);
            cleanupExpiredErrorFiles();
        } catch (IOException e) {
            throw new BadRequestException("Save error record failed: " + e.getMessage());
        }
        return fileId;
    }

    /** 清理超过 24 小时的失败记录文件（对齐 SQLBot 进程退出时清理临时文件的语义）。 */
    private void cleanupExpiredErrorFiles() {
        long deadline = System.currentTimeMillis() - ERROR_FILE_TTL_MILLIS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(excelDir, ERROR_FILE_PREFIX + "*")) {
            for (Path path : stream) {
                if (Files.getLastModifiedTime(path).toMillis() < deadline) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // 清理失败不影响导入结果
        }
    }

    private static boolean headerMatches(String[] header) {
        if (header.length < IMPORT_HEADERS.size()) {
            return false;
        }
        for (int i = 0; i < IMPORT_HEADERS.size(); i++) {
            if (!IMPORT_HEADERS.get(i).equalsIgnoreCase(cell(header, i))) {
                return false;
            }
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

    private static ExcelUtils.MapStringArray sampleRow(String account, String name, String password, String email) {
        return key -> {
            if ("account".equals(key)) {
                return account;
            }
            if ("name".equals(key)) {
                return name;
            }
            if ("password".equals(key)) {
                return password;
            }
            return email;
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
