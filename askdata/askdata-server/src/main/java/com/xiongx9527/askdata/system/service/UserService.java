package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.Maps;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.system.auth.CryptoUtil;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 用户模块（对齐 SQLBot apps/system/api/user.py + crud/user.py）。
 * 管理员判定与权限：pager/CRUD/pwd/status 仅管理员；info/ws/language/pwd(own) 登录即可。
 */
@Service
public class UserService {
    @Autowired
    private MessageResolver messageResolver;

    /**
     * 默认口令：可配置（askdata.user.default-password）。F-04：不写入可预测的缺省值——
     * 未配置时回退运行期随机口令（每次进程不同），保证新用户口令不可预测；
     * F-03 改密判定因此在「未配置默认口令」的部署里自然失效：
     * 无可预测默认口令 ⇒ 需要在部署配置显式设定 askdata.user.default-password 才启用
     * 「默认口令登录 → need_change_pwd 引导 + changePwd 拒绝默认口令」。
     * 这里通过 package-private 读取提供给 LoginController 做默认口令判定对比。
     */
    @org.springframework.beans.factory.annotation.Value("${askdata.user.default-password:}")
    private String defaultPasswordConfig;

    private String resolvedDefaultPassword;

    private String resolveDefaultPassword() {
        if (resolvedDefaultPassword == null) {
            resolvedDefaultPassword = (defaultPasswordConfig == null || defaultPasswordConfig.isEmpty())
                    ? java.util.UUID.randomUUID().toString() : defaultPasswordConfig;
        }
        return resolvedDefaultPassword;
    }

    /**
     * F-03：登录改密判定与 changePwd 拒绝都用这一基准。
     * 未显式配置 {@code askdata.user.default-password} 时返回 null（无可预测基准 → 判定不命中）。
     */
    /** 导入建号复用同一口令来源（F-17：SQLBot 列集不含 password 列）。 */
    String defaultPasswordForCreate() {
        return resolveDefaultPassword();
    }

    public String defaultPasswordForCheck() {
        return (defaultPasswordConfig == null || defaultPasswordConfig.isEmpty())
                ? null : defaultPasswordConfig;
    }

    /** F-03：默认口令判定（对口令明文做 MD5 后与库中哈希比对）。defaultPasswordForCheck() 为 null 时恒 false。 */
    public boolean isDefaultPassword(String plainPwdMd5) {
        String baseline = defaultPasswordForCheck();
        return baseline != null && CryptoUtil.md5Hex(baseline).equals(plainPwdMd5);
    }

    /**
     * F-03：SQLBot {@code login.initial_pwd_disabled} 参数读取（sys_arg）。
     * 缺省 false：默认口令登录直接发主 token（不启用强制改密）。
     */
    public boolean initialPwdDisabled() {
        try {
            Optional<Map<String, Object>> row = sysArgRepository.findByPkey("login.initial_pwd_disabled");
            return row.isPresent() && "true".equalsIgnoreCase(Rows.str(row.get(), "pval").trim());
        } catch (Exception e) {
            return false;
        }
    }

    public static final List<String> SUPPORTED_LANGUAGES =
            Collections.unmodifiableList(java.util.Arrays.asList("zh-CN", "zh-TW", "en", "ko-KR", "th-TH"));

    /** 对齐 SQLBot apps/system/schemas/system_schema.py EMAIL_REGEX。 */
    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[a-zA-Z0-9]+([._-][a-zA-Z0-9]+)*@([a-zA-Z0-9]+(-[a-zA-Z0-9]+)*\\.)+[a-zA-Z]{2,}$");

    /** 对齐 SQLBot PWD_REGEX：8-20 位，含大小写、数字与特殊字符。 */
    private static final Pattern PWD_REGEX = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[~!@#$%^&*()_+\\-={}|:\"<>?`\\[\\];',./])"
                    + "[A-Za-z\\d~!@#$%^&*()_+\\-={}|:\"<>?`\\[\\];',./]{8,20}$");

    private final UserRepository userRepository;

    private final UserWsRepository userWsRepository;

    private final WorkspaceRepository workspaceRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    private final SysArgRepository sysArgRepository;

    public UserService(UserRepository userRepository, UserWsRepository userWsRepository,
                       WorkspaceRepository workspaceRepository, CurrentUserService currentUserService,
                       IdGenerator idGenerator, ObjectMapper objectMapper, SysArgRepository sysArgRepository) {
        this.userRepository = userRepository;
        this.userWsRepository = userWsRepository;
        this.workspaceRepository = workspaceRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.sysArgRepository = sysArgRepository;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> pager(int pageNum, int pageSize, String keyword, Integer status,
                                                 List<Integer> origins, List<Integer> oidlist,
                                                 String orderBy, boolean desc) {
        currentUserService.requireAdmin();
        int size = Math.max(pageSize, 1);
        int page = Math.max(pageNum, 1);
        List<Map<String, Object>> rows = userRepository.pagerRows(keyword, status, origins, oidlist, orderBy, desc,
                (page - 1) * size, size);
        long total = userRepository.pagerCount(keyword, status, origins, oidlist);

        List<Long> uids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            uids.add(Rows.lng(row, "id", 0L));
        }
        Map<Long, List<Long>> bindings = bindingsByUid(uids);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long uid = Rows.lng(row, "id", 0L);
            items.add(toItem(row, bindings.getOrDefault(uid, Collections.emptyList())));
        }
        return new PageResult<>(items, total, page, size);
    }

    public Map<String, Object> get(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> row = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("user.not.found", id)));
        return toItem(row, userRepository.oidListOf(id));
    }

    public String defaultPwd() {
        currentUserService.requireAdmin();
        // 对齐 SQLBot user.py default_pwd：login.initial_pwd_hidden=true 时返回空串
        return sysArgRepository.findByPkey("login.initial_pwd_hidden")
                .map(row -> Rows.str(row, "pval"))
                .filter(pval -> "true".equalsIgnoreCase(pval))
                .map(pval -> "")
                .orElse(resolveDefaultPassword());
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        currentUserService.requireAdmin();
        String account = required(body, "account");
        String name = required(body, "name");
        String email = required(body, "email");
        int status = Rows.intg(body, "status", 1);
        int origin = Rows.intg(body, "origin", 0);
        if (userRepository.existsAccount(account, null)) {
            throw new BadRequestException(messageResolver.get("user.account.exists", account));
        }
        if (!EMAIL_REGEX.matcher(email).matches()) {
            throw new BadRequestException(messageResolver.get("user.email.invalid", email));
        }
        List<Long> oidList = Rows.longList(Rows.get(body, "oid_list"));
        long id = idGenerator.nextId();
        String systemVariables = serializeSystemVariables(body);
        // 对齐 SQLBot create：新用户 oid=0，绑定工作空间后取首个
        // 修复：管理员创建用户时若显式传入 password，则以该口令的 MD5 存储；
        // 否则回落默认口令（保持原行为，兼容导入/SSO 等不传口令的场景）。
        // 原实现始终用默认口令，导致创建时设置的口令无法登录。
        String rawPwd = Rows.str(body, "password");
        String passwordHash = (rawPwd == null || rawPwd.isEmpty())
                ? CryptoUtil.md5Hex(resolveDefaultPassword())
                : CryptoUtil.md5Hex(rawPwd);
        userRepository.insert(id, account, name, passwordHash, email, 0L, status,
                System.currentTimeMillis(), "zh-CN", origin, systemVariables);
        long oid = 0L;
        for (Long wsId : new TreeSet<>(oidList)) {
            bindUser(id, wsId, 0);
            if (oid == 0L) {
                oid = wsId;
            }
        }
        userRepository.updateOid(id, oid);
        return get(id);
    }

    /**
     * 外部 IdP 登录自动开通/绑定本地账号（origin=2 标记外部来源，无需管理员上下文）。
     * 已存在同名账号直接返回其 id；否则创建并绑定默认工作空间（id=1）。
     */
    public long provisionExternalUser(String account, String name, String email) {
        if (account == null || account.trim().isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "account")));
        }
        String safeAccount = account.trim();
        java.util.Optional<Map<String, Object>> existing = userRepository.findByAccount(safeAccount);
        if (existing.isPresent()) {
            return ((Number) existing.get().get("id")).longValue();
        }
        String safeEmail = (email != null && !email.isEmpty() && EMAIL_REGEX.matcher(email).matches())
                ? email : safeAccount + "@sso.local";
        String safeName = (name != null && !name.isEmpty()) ? name : safeAccount;
        long id = idGenerator.nextId();
        userRepository.insert(id, safeAccount, safeName, CryptoUtil.md5Hex(resolveDefaultPassword()),
                safeEmail, 0L, 1, System.currentTimeMillis(), "zh-CN", 2, null);
        bindUser(id, 1L, 0);
        userRepository.updateOid(id, 1L);
        return id;
    }

    @Transactional
    public void update(Map<String, Object> body) {
        currentUserService.requireAdmin();
        long id = Rows.lng(body, "id", 0L);
        Map<String, Object> existing = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("user.not.found", id)));
        String account = Rows.str(body, "account");
        if (account != null && !account.equals(Rows.str(existing, "account"))) {
            throw new BadRequestException(messageResolver.get("user.account.unchanged"));
        }
        String name = valueOrExisting(body, existing, "name");
        String email = valueOrExisting(body, existing, "email");
        int status = Rows.get(body, "status") == null ? Rows.intg(existing, "status", 1)
                : Rows.intg(body, "status", 1);
        int origin = Rows.get(body, "origin") == null ? Rows.intg(existing, "origin", 0)
                : Rows.intg(body, "origin", 0);
        if (email == null || !EMAIL_REGEX.matcher(email).matches()) {
            throw new BadRequestException(messageResolver.get("user.email.invalid", email));
        }
        long originOid = Rows.lng(existing, "oid", 0L);
        String systemVariables = body.containsKey("system_variables")
                ? serializeSystemVariables(body)
                : Rows.str(existing, "system_variables");

        // 工作空间绑定同步（对齐 SQLBot update）
        List<Long> oidList = Rows.longList(Rows.get(body, "oid_list"));
        Set<Long> newOids = new TreeSet<>(oidList);
        Set<Long> existingOids = new TreeSet<>(userWsRepository.oidsOf(id));
        for (Long wsId : existingOids) {
            if (!newOids.contains(wsId)) {
                userWsRepository.delete(id, wsId);
            }
        }
        for (Long wsId : newOids) {
            if (!existingOids.contains(wsId)) {
                bindUser(id, wsId, 0);
            }
        }
        long oid = 0L;
        if (!newOids.isEmpty()) {
            oid = newOids.contains(originOid) ? originOid : newOids.iterator().next();
        }
        userRepository.updateProfile(id, name, email, status, origin, systemVariables);
        userRepository.updateOid(id, oid);
    }

    @Transactional
    public void delete(long id) {
        currentUserService.requireAdmin();
        Map<String, Object> user = userRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("user.not.found", id)));
        if (currentUserService.isAdmin(user)) {
            throw new BadRequestException(messageResolver.get("user.delete.admin.denied"));
        }
        userWsRepository.deleteAllForUser(id);
        userRepository.deleteById(id);
    }

    @Transactional
    public void deleteBatch(List<Long> ids) {
        currentUserService.requireAdmin();
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            if (id != null) {
                delete(id);
            }
        }
    }

    public void resetPassword(long id) {
        currentUserService.requireAdmin();
        userRepository.updatePassword(id, CryptoUtil.md5Hex(resolveDefaultPassword()));
    }

    public void changeOwnPassword(String pwd, String newPwd) {
        long uid = currentUserService.uid();
        Map<String, Object> user = userRepository.findById(uid)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("user.login.not.exists")));
        if (!CryptoUtil.md5Hex(pwd == null ? "" : pwd).equals(Rows.str(user, "password"))) {
            throw new BadRequestException(messageResolver.get("user.pwd.original.incorrect"));
        }
        if (newPwd == null || !PWD_REGEX.matcher(newPwd).matches()) {
            throw new BadRequestException(messageResolver.get("user.pwd.format.invalid"));
        }
        userRepository.updatePassword(uid, CryptoUtil.md5Hex(newPwd));
    }

    public void changeStatus(long id, int status) {
        currentUserService.requireAdmin();
        if (status != 0 && status != 1) {
            throw new BadRequestException(messageResolver.get("user.status.unsupported"));
        }
        userRepository.updateStatus(id, status);
    }

    public void changeLanguage(String language) {
        if (language == null || !SUPPORTED_LANGUAGES.contains(language)) {
            throw new BadRequestException(messageResolver.get("user.language.unsupported", language));
        }
        userRepository.updateLanguage(currentUserService.uid(), language);
    }

    // ------------------------------------------------------------------
    // 工作空间选择/切换
    // ------------------------------------------------------------------

    public List<Map<String, Object>> wsOptions() {
        long uid = currentUserService.uid();
        String language = Rows.str(currentUserService.currentUser(), "language");
        List<Map<String, Object>> result = new ArrayList<>();
        // 对齐 SQLBot UserWs schema：仅 {id, name}（内置名称按用户语言本地化）
        for (Map<String, Object> row : workspaceRepository.listOfUser(uid)) {
            result.add(Rows.of("id", Rows.lng(row, "id", 0L),
                    "name", WorkspaceService.localizeWorkspaceName(Rows.str(row, "name"), language)));
        }
        return result;
    }

    public void switchWorkspace(long oid) {
        long uid = currentUserService.uid();
        boolean allowed = false;
        for (Map<String, Object> ws : workspaceRepository.listOfUser(uid)) {
            if (Rows.lng(ws, "id", 0L) == oid) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            Optional<Map<String, Object>> ws = workspaceRepository.findById(oid);
            if (ws.isPresent()) {
                throw new BadRequestException(messageResolver.get("i18n_user.ws_not_available",
                        Maps.of("ws", String.valueOf(Rows.str(ws.get(), "name")))));
            }
            throw new BadRequestException(messageResolver.get("workspace.not.found", oid));
        }
        userRepository.updateOid(uid, oid);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 绑定用户到工作空间；若用户当前 oid 为 0 则同时设为其默认空间（SQLBot reset_single_user_oid）。 */
    public void bindUser(long uid, long oid, int weight) {
        if (!userWsRepository.find(uid, oid).isPresent()) {
            userWsRepository.insert(idGenerator.nextId(), uid, oid, weight);
        } else {
            userWsRepository.updateWeight(uid, oid, weight);
        }
        Map<String, Object> user = userRepository.findById(uid).orElse(null);
        if (user != null && Rows.lng(user, "oid", 0L) == 0L) {
            userRepository.updateOid(uid, oid);
        }
    }

    /** 解绑用户与工作空间；若被解绑空间是用户当前 oid，则回退到其他绑定空间或 0。 */
    public void unbindUser(long uid, long oid) {
        userWsRepository.delete(uid, oid);
        Map<String, Object> user = userRepository.findById(uid).orElse(null);
        if (user != null && Rows.lng(user, "oid", 0L) == oid) {
            List<Long> remaining = userWsRepository.oidsOf(uid);
            userRepository.updateOid(uid, remaining.isEmpty() ? 0L : remaining.get(0));
        }
    }

    private Map<String, Object> toItem(Map<String, Object> row, List<Long> oidList) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", Rows.lng(row, "id", 0L));
        item.put("account", Rows.str(row, "account"));
        item.put("name", Rows.str(row, "name"));
        item.put("email", Rows.str(row, "email"));
        item.put("status", Rows.intg(row, "status", 1));
        item.put("origin", Rows.intg(row, "origin", 0));
        item.put("oid", Rows.lng(row, "oid", 0L));
        item.put("language", Rows.str(row, "language"));
        item.put("create_time", Rows.lng(row, "create_time", 0L));
        item.put("oid_list", oidList == null ? new ArrayList<Long>() : oidList);
        item.put("system_variables", parseSystemVariables(Rows.str(row, "system_variables")));
        return item;
    }

    private Map<Long, List<Long>> bindingsByUid(List<Long> uids) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : userRepository.oidBindingsOf(uids)) {
            long uid = Rows.lng(row, "uid", 0L);
            result.computeIfAbsent(uid, key -> new ArrayList<>()).add(Rows.lng(row, "oid", 0L));
        }
        return result;
    }

    private static String valueOrExisting(Map<String, Object> body, Map<String, Object> existing, String key) {
        Object value = Rows.get(body, key);
        return value == null ? Rows.str(existing, key) : String.valueOf(value);
    }

    private String required(Map<String, Object> body, String key) {
        Object value = Rows.get(body, key);
        if (value == null || String.valueOf(value).isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", key)));
        }
        return String.valueOf(value);
    }

    private String serializeSystemVariables(Map<String, Object> body) {
        Object value = Rows.get(body, "system_variables");
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_format_invalid", Maps.of("key", "system_variables")));
        }
    }

    private Object parseSystemVariables(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
