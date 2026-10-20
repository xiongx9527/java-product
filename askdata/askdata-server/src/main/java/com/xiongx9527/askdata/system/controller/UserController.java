package com.xiongx9527.askdata.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.service.UserImportService;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.askdata.system.web.UserInfoVO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户模块（对齐 SQLBot apps/system/api/user.py）：
 * info/ws/language/pwd(own) 登录即可；pager/CRUD/pwd 重置/status/defaultPwd 仅管理员。
 * /user/template、/user/batchImport、/user/errorRecord/{fileId} 为 Excel 批量导入能力，仅管理员。
 */
@RestController
@RequestMapping({"api/v1"})
public class UserController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UserRepository userRepository;

    private final UserService userService;

    private final UserImportService userImportService;

    public UserController(UserRepository userRepository, UserService userService,
                          UserImportService userImportService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.userImportService = userImportService;
    }

    // ------------------------------------------------------------------
    // 当前用户
    // ------------------------------------------------------------------

    @GetMapping({"/user/info"})
    public Result<UserInfoVO> info() {
        long uid = toLong(StpUtil.getLoginId());
        long exp = System.currentTimeMillis() / 1000L + StpUtil.getTokenTimeout();
        long now = System.currentTimeMillis() / 1000L;
        return Result.ok(toUserInfo(userRepository.findById(uid), exp, now));
    }

    @GetMapping({"/user/ws"})
    public Result<List<Map<String, Object>>> wsOptions() {
        return Result.ok(userService.wsOptions());
    }

    @PutMapping({"/user/ws/{oid}"})
    public Result<Void> switchWorkspace(@PathVariable long oid) {
        userService.switchWorkspace(oid);
        return Result.ok();
    }

    @PutMapping({"/user/language"})
    public Result<Void> changeLanguage(@RequestBody Map<String, Object> editor) {
        userService.changeLanguage(Rows.str(editor, "language"));
        return Result.ok();
    }

    @PutMapping({"/user/pwd"})
    public Result<Void> changeOwnPassword(@RequestBody Map<String, Object> editor) {
        userService.changeOwnPassword(Rows.str(editor, "pwd"), Rows.str(editor, "new_pwd"));
        return Result.ok();
    }

    // ------------------------------------------------------------------
    // 管理端
    // ------------------------------------------------------------------

    @GetMapping({"/user/defaultPwd"})
    public Result<String> defaultPwd() {
        return Result.ok(userService.defaultPwd());
    }

    @GetMapping({"/user/pager/{pageNum}/{pageSize}"})
    public Result<PageResult<Map<String, Object>>> pager(@PathVariable int pageNum, @PathVariable int pageSize,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Integer status,
                                                         @RequestParam(required = false) List<Integer> origins,
                                                         @RequestParam(required = false) List<Integer> oidlist,
                                                         @RequestParam(required = false) String order_by,
                                                         @RequestParam(required = false, defaultValue = "false")
                                                                 boolean desc) {
        return Result.ok(userService.pager(pageNum, pageSize, keyword, status, origins, oidlist, order_by, desc));
    }

    @GetMapping({"/user/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(userService.get(id));
    }

    @PostMapping({"/user"})
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> creator) {
        return Result.ok(userService.create(creator));
    }

    @PutMapping({"/user"})
    public Result<Void> update(@RequestBody Map<String, Object> editor) {
        userService.update(editor);
        return Result.ok();
    }

    @DeleteMapping({"/user/{id}"})
    public Result<Void> delete(@PathVariable long id) {
        userService.delete(id);
        return Result.ok();
    }

    @DeleteMapping({"/user"})
    public Result<Void> deleteBatch(@RequestBody List<Long> idList) {
        userService.deleteBatch(idList);
        return Result.ok();
    }

    @PatchMapping({"/user/pwd/{id}"})
    public Result<Void> resetPassword(@PathVariable long id) {
        userService.resetPassword(id);
        return Result.ok();
    }

    @PatchMapping({"/user/status"})
    public Result<Void> changeStatus(@RequestBody Map<String, Object> statusDto) {
        userService.changeStatus(Rows.lng(statusDto, "id", 0L), Rows.intg(statusDto, "status", 1));
        return Result.ok();
    }

    /** GET /user/template：下载用户导入模板 xlsx（对齐 SQLBot user_template）。 */
    @GetMapping({"/user/template"})
    public ResponseEntity<ByteArrayResource> template() throws UnsupportedEncodingException {
        return xlsxResponse(userImportService.importTemplate(), "user_import_template.xlsx");
    }

    /** POST /user/batchImport：上传用户 Excel 批量建号，返回 {fileId,total,success,failed}。 */
    @PostMapping({"/user/batchImport"})
    public Result<Map<String, Object>> batchImport(@RequestParam("file") MultipartFile file) {
        return Result.ok(userImportService.batchImport(file));
    }

    /** GET /user/errorRecord/{fileId}：下载失败记录 xlsx；fileId 不存在返回 400 明确错误。 */
    @GetMapping({"/user/errorRecord/{fileId}"})
    public ResponseEntity<ByteArrayResource> errorRecord(@PathVariable String fileId)
            throws UnsupportedEncodingException {
        return xlsxResponse(userImportService.errorRecord(fileId), "user_import_errors_" + fileId + ".xlsx");
    }

    /** xlsx 字节流下载响应（与 DatasourceController.exportDsSchema 写法一致）。 */
    private static ResponseEntity<ByteArrayResource> xlsxResponse(byte[] content, String filename)
            throws UnsupportedEncodingException {
        String encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    // ------------------------------------------------------------------

    private UserInfoVO toUserInfo(Optional<Map<String, Object>> row, long exp, long now) {
        if (!row.isPresent()) {
            throw new BadRequestException("Login user does not exist");
        }
        Map<String, Object> user = row.get();
        long id = toLong(user.get("id"));
        String account = String.valueOf(user.get("account"));
        long oid = toLong(user.get("oid"));
        UserInfoVO vo = new UserInfoVO();
        vo.setId(id);
        vo.setAccount(account);
        vo.setName(String.valueOf(user.get("name")));
        vo.setEmail(String.valueOf(user.get("email")));
        vo.setOid(oid);
        String language = user.get("language") == null ? "zh-CN" : String.valueOf(user.get("language"));
        vo.setLanguage(language);
        vo.setStatus(toLong(user.get("status")));
        vo.setOrigin(toLong(user.get("origin")));
        vo.setWeight(userRepository.weightOf(id, oid));
        vo.setExp(exp);
        vo.setTime(now);
        vo.setIsAdmin(id == 1L && "admin".equals(account));
        return vo;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number
                ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value).trim());
    }
}
