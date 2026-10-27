package com.xiongx9527.askdata.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.system.repository.VariableRepository;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统变量模块（对齐 SQLBot apps/system/api/variable_api.py + crud/system_variable.py）。
 * save/delete/listPage 仅管理员；listAll 为 ws_admin（管理员或空间管理员）。
 */
@Service
public class VariableService {

    private final VariableRepository variableRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    private final MessageResolver messageResolver;

    public VariableService(VariableRepository variableRepository, CurrentUserService currentUserService,
                           IdGenerator idGenerator, ObjectMapper objectMapper, MessageResolver messageResolver) {
        this.variableRepository = variableRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    public List<Map<String, Object>> listAll(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        String name = body == null ? null : Rows.str(body, "name");
        return toItems(variableRepository.listAll(name));
    }

    public PageResult<Map<String, Object>> listPage(int pageNum, int pageSize, Map<String, Object> body) {
        currentUserService.requireAdmin();
        String name = body == null ? null : Rows.str(body, "name");
        int page = Math.max(pageNum, 1);
        int size = Math.max(pageSize, 1);
        List<Map<String, Object>> rows = variableRepository.listPage(name, (page - 1) * size, size);
        long total = variableRepository.listPageCount(name);
        return new PageResult<>(toItems(rows), total, page, size);
    }

    public boolean save(Map<String, Object> body) {
        currentUserService.requireAdmin();
        String name = Rows.str(body, "name");
        if (name == null || name.isEmpty()) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", java.util.Map.of("key", "name")));
        }
        Long id = Rows.lngOrNull(body, "id");
        if (variableRepository.existsName(name, id)) {
            throw new BadRequestException(messageResolver.get("i18n_variable.name_exist"));
        }
        String varType = Rows.str(body, "var_type") == null ? "text" : Rows.str(body, "var_type");
        String value = valueJson(body);
        if (id == null || !variableRepository.findById(id).isPresent()) {
            variableRepository.insert(idGenerator.nextId(), name, varType, value, new Timestamp(System.currentTimeMillis()),
                    currentUserService.uid());
        } else {
            variableRepository.update(id, name, varType, value);
        }
        return true;
    }

    /**
     * 删除系统变量（A24）。
     *
     * <p>对齐 SQLBot {@code crud/system_variable.py delete}：按 id 直接删除，<b>不区分</b>
     * 内置（system）与自定义（custom）。内置变量被删除后 {@code /system/variable/list} 不再返回该项，
     * 依赖该变量的问数流程按「未绑定」处理（与 SQLBot 一致）。</p>
     */
    public void delete(List<Long> ids) {
        currentUserService.requireAdmin();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        variableRepository.delete(new ArrayList<>(ids));
    }

    private List<Map<String, Object>> toItems(List<Map<String, Object>> rows) {
        String language = Rows.str(currentUserService.currentUser(), "language");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String type = Rows.str(row, "type");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("name", displayName(Rows.str(row, "name"), type, language));
            item.put("var_type", Rows.str(row, "var_type"));
            item.put("type", type);
            item.put("value", parseValue(Rows.str(row, "value")));
            item.put("create_time", formatTime(Rows.get(row, "create_time")));
            item.put("create_by", Rows.lngOrNull(row, "create_by"));
            result.add(item);
        }
        return result;
    }

    /**
     * 内置变量名按当前用户语言翻译（对齐 SQLBot crud/system_variable.py 的 trans(data.name)；
     * 文案取自 SQLBot backend/locales/*.json 的 i18n_variable.* 键）。
     */
    private static String displayName(String name, String type, String language) {
        if (!"system".equals(type) || name == null) {
            return name;
        }
        switch (name) {
            case "i18n_variable.name":
                return localized(language, "Name", "이름", "ชื่อ", "姓名", "姓名");
            case "i18n_variable.account":
                return localized(language, "Account", "계정", "บัญชี", "账号", "帳號");
            case "i18n_variable.email":
                return localized(language, "Email", "이메일", "อีเมล", "邮箱", "郵箱");
            default:
                return name;
        }
    }

    private static String localized(String language, String en, String ko, String th, String zhCn, String zhTw) {
        if (language == null) {
            return en;
        }
        switch (language) {
            case "ko-KR":
                return ko;
            case "th-TH":
                return th;
            case "zh-CN":
                return zhCn;
            case "zh-TW":
                return zhTw;
            default:
                return en;
        }
    }

    private Object parseValue(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return json;
        }
    }

    private String valueJson(Map<String, Object> body) {
        Object value = Rows.get(body, "value");
        try {
            return value == null ? "[]" : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("i18n_format_invalid", java.util.Map.of("key", "value")));
        }
    }

    private static Object formatTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().toString();
        }
        return String.valueOf(value);
    }
}
