package com.xiongx9527.askdata.system.service;

import com.xiongx9527.askdata.common.util.Maps;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作空间模块（对齐 SQLBot apps/system/api/workspace.py + crud/workspace.py）。
 * CRUD 仅管理员；uws（成员）接口为 ws_admin（管理员或空间管理员，option 列表仅管理员）。
 */
@Service
public class WorkspaceService {
    @Autowired
    private MessageResolver messageResolver;

    private final WorkspaceRepository workspaceRepository;

    private final UserWsRepository userWsRepository;

    private final UserRepository userRepository;

    private final UserService userService;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    public WorkspaceService(WorkspaceRepository workspaceRepository, UserWsRepository userWsRepository,
                            UserRepository userRepository, UserService userService,
                            CurrentUserService currentUserService, IdGenerator idGenerator) {
        this.workspaceRepository = workspaceRepository;
        this.userWsRepository = userWsRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------
    // 工作空间 CRUD
    // ------------------------------------------------------------------

    /**
     * A8（对齐 SQLBot mcp_ws_list/user_ws_options）：管理员列全部工作空间；
     * 非管理员仅列自己所属的工作空间，不再强制 requireAdmin——MCP workspace_list
     * 与普通 API 共用同一实现。
     */
    public List<Map<String, Object>> list() {
        String language = userLanguage();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : visibleWorkspaceRows()) {
            result.add(toWorkspace(row, language));
        }
        return result;
    }

    /**
     * MCP 工作空间选项（A10 / SQLBot {@code user_ws_options}）：只返回 {@code {id,name}}，
     * 名称为当前语言翻译后的名称，按名称升序。可见性规则与 {@link #list()} 一致。
     */
    public List<Map<String, Object>> listOptions() {
        String language = userLanguage();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : visibleWorkspaceRows()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", Rows.lng(row, "id", 0L));
            option.put("name", localizeWorkspaceName(Rows.str(row, "name"), language));
            result.add(option);
        }
        result.sort((left, right) -> String.valueOf(left.get("name"))
                .compareTo(String.valueOf(right.get("name"))));
        return result;
    }

    /** 当前用户可见工作空间行：管理员全部，普通用户仅所属空间。 */
    private List<Map<String, Object>> visibleWorkspaceRows() {
        Map<String, Object> user = currentUserService.currentUser();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (currentUserService.isAdmin(user)) {
            rows.addAll(workspaceRepository.list());
        } else {
            for (Long oid : userWsRepository.oidsOf(Rows.lng(user, "id", 0L))) {
                workspaceRepository.findById(oid).ifPresent(rows::add);
            }
        }
        return rows;
    }

    public Map<String, Object> get(long id) {
        currentUserService.requireAdmin();
        return toWorkspace(workspaceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("workspace.not.found", id))), userLanguage());
    }

    public Map<String, Object> create(Map<String, Object> body) {
        currentUserService.requireAdmin();
        String name = Rows.str(body, "name");
        if (name == null || name.isEmpty()) {
            throw new BadRequestException(messageResolver.get("workspace.name.required"));
        }
        long id = idGenerator.nextId();
        long createTime = System.currentTimeMillis();
        workspaceRepository.insert(id, name, createTime);
        return Rows.of("id", id, "name", name, "create_time", createTime);
    }

    public void update(Map<String, Object> body) {
        currentUserService.requireAdmin();
        long id = Rows.lng(body, "id", 0L);
        workspaceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("workspace.not.found", id)));
        String name = Rows.str(body, "name");
        if (name == null || name.isEmpty()) {
            throw new BadRequestException(messageResolver.get("workspace.name.required"));
        }
        workspaceRepository.update(id, name);
    }

    @Transactional
    public void delete(long id) {
        currentUserService.requireAdmin();
        if (id == 1L) {
            throw new BadRequestException(messageResolver.get("workspace.delete.default.denied"));
        }
        workspaceRepository.findById(id)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("workspace.not.found", id)));

        // A21：删除空间后，用户 oid 重新选择其仍所属的空间（按空间名序取第一个），
        // 不再有归属时置 0；此前一律改成 1 会让用户落到默认空间，权限与数据归属失真。
        java.util.Set<Long> affectedUids = new java.util.LinkedHashSet<>(userWsRepository.uidsOfWorkspace(id));
        affectedUids.addAll(userRepository.uidsByOid(id));
        userWsRepository.deleteAllForWorkspace(id);
        for (Long uid : affectedUids) {
            java.util.Optional<Map<String, Object>> user = userRepository.findById(uid);
            if (!user.isPresent() || Rows.lng(user.get(), "oid", 0L) != id) {
                continue;
            }
            List<Long> remaining = userRepository.oidListOf(uid);
            userRepository.updateOid(uid, remaining.isEmpty() ? 0L : remaining.get(0));
        }
        workspaceRepository.delete(id);
    }

    // ------------------------------------------------------------------
    // 成员（uws）
    // ------------------------------------------------------------------

    public PageResult<Map<String, Object>> memberPager(int pageNum, int pageSize, String keyword, Long oid) {
        currentUserService.requireWsAdmin();
        Map<String, Object> current = currentUserService.currentUser();
        long workspaceId;
        if (currentUserService.isAdmin(current) && oid != null && oid > 0) {
            workspaceId = oid;
        } else {
            workspaceId = currentUserService.oidOf(current);
        }
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        List<Map<String, Object>> rows = userWsRepository.pagerRows(workspaceId, keyword, (page - 1) * size, size);
        long total = userWsRepository.pagerCount(workspaceId, keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(Rows.of(
                    "id", Rows.lng(row, "id", 0L),
                    "account", Rows.str(row, "account"),
                    "name", Rows.str(row, "name"),
                    "email", Rows.str(row, "email"),
                    "status", Rows.intg(row, "status", 1),
                    "create_time", Rows.lng(row, "create_time", 0L),
                    "oid", Rows.lng(row, "oid", 0L),
                    "weight", Rows.intg(row, "weight", 0)));
        }
        return new PageResult<>(items, total, page, size);
    }

    public PageResult<Map<String, Object>> optionPager(int pageNum, int pageSize, String keyword, long oid) {
        if (oid <= 0) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "oid")));
        }
        currentUserService.requireAdmin();
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        List<Map<String, Object>> rows = userWsRepository.optionPagerRows(oid, keyword, (page - 1) * size, size);
        long total = userWsRepository.optionPagerCount(oid, keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(toUserOption(row));
        }
        return new PageResult<>(items, total, page, size);
    }

    public Map<String, Object> optionUser(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "keyword")));
        }
        currentUserService.requireWsAdmin();
        Map<String, Object> current = currentUserService.currentUser();
        return userWsRepository.optionUser(keyword, currentUserService.oidOf(current))
                .map(WorkspaceService::toUserOption)
                .orElse(null);
    }

    @Transactional
    public void uwsCreate(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> current = currentUserService.currentUser();
        boolean admin = currentUserService.isAdmin(current);
        Long bodyOid = Rows.lngOrNull(body, "oid");
        Integer bodyWeight = Rows.intg(body, "weight");
        long oid = admin && bodyOid != null && bodyOid > 0 ? bodyOid : currentUserService.oidOf(current);
        int weight = admin && bodyWeight != null ? bodyWeight : 0;

        List<Long> uidList = Rows.longList(Rows.get(body, "uid_list"));
        if (uidList.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "uid_list")));
        }
        if (!workspaceRepository.findById(oid).isPresent()) {
            throw new BadRequestException(messageResolver.get("workspace.not.found", oid));
        }
        for (Long uid : new java.util.LinkedHashSet<>(uidList)) {
            if (userRepository.findById(uid).isPresent()) {
                userService.bindUser(uid, oid, weight);
            }
        }
    }

    public void uwsUpdate(Map<String, Object> body) {
        currentUserService.requireAdmin();
        long uid = Rows.lng(body, "uid", 0L);
        long oid = Rows.lng(body, "oid", 0L);
        int weight = Rows.intg(body, "weight", 0);
        if (uid <= 0 || oid <= 0) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "oid, uid")));
        }
        Map<String, Object> binding = userWsRepository.find(uid, oid)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("i18n_not_exist", Maps.of("msg", "user workspace"))));
        if (Rows.intg(binding, "weight", 0) == weight) {
            return;
        }
        userWsRepository.updateWeight(uid, oid, weight);
    }

    @Transactional
    public void uwsDelete(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        Map<String, Object> current = currentUserService.currentUser();
        boolean admin = currentUserService.isAdmin(current);
        Long bodyOid = Rows.lngOrNull(body, "oid");
        long oid = admin && bodyOid != null && bodyOid > 0 ? bodyOid : currentUserService.oidOf(current);
        List<Long> uidList = Rows.longList(Rows.get(body, "uid_list"));
        for (Long uid : uidList) {
            userService.unbindUser(uid, oid);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Map<String, Object> toWorkspace(Map<String, Object> row, String language) {
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("id", Rows.lng(row, "id", 0L));
        ws.put("name", localizeWorkspaceName(Rows.str(row, "name"), language));
        ws.put("create_time", Rows.lng(row, "create_time", 0L));
        return ws;
    }

    private String userLanguage() {
        return Rows.str(currentUserService.currentUser(), "language");
    }

    /**
     * SQLBot 后端会在返回工作空间时对本语言的内置名称做本地化（apis/workspace.py 经 trans() 转换）；
     * askdata 对齐该行为，默认空间 {@code i18n_default_workspace} 按用户语言返回。
     */
    public static String localizeWorkspaceName(String name, String language) {
        if (!"i18n_default_workspace".equals(name)) {
            return name;
        }
        String lang = language == null || language.isEmpty() ? "zh-CN" : language;
        switch (lang) {
            case "en":
                return "Default Workspace";
            case "zh-TW":
                return "默認工作空間";
            case "ko-KR":
                return "기본 작업 공간";
            case "th-TH":
                return "พื้นที่ทำงานเริ่มต้น";
            case "zh-CN":
            default:
                return "默认工作空间";
        }
    }

    private static Map<String, Object> toUserOption(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", Rows.lng(row, "id", 0L));
        user.put("name", Rows.str(row, "name"));
        user.put("account", Rows.str(row, "account"));
        return user;
    }
}
