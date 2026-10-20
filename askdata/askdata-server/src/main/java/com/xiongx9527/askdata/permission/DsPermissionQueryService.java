package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.FieldRepository;
import com.xiongx9527.askdata.datasource.repository.TableRepository;
import com.xiongx9527.askdata.system.repository.VariableRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 数据权限查询面（对齐 SQLBot {@code datasource/crud/permission.py} + {@code row_permission.py}）：
 * 问数执行前按当前用户 + 数据源 + 表，计算行权限 WHERE 片段与列权限隐藏字段。
 *
 * <p>行权限：把每条 {@code ds_permission(type=row)} 的 {@code expression_tree} 翻译为方言正确的
 * WHERE 片段（列名按方言引用符包裹，运算符/枚举/普通值/系统变量 与 SQLBot 一致），
 * 供问数主链路交给 LLM 重写 SQL（见 {@code ChatQuestionService}）。</p>
 *
 * <p>列权限：把 {@code type=column} 中 {@code enable=false} 的字段从 schema 上下文剔除，
 * 使 LLM 不会 SELECT 无权限列（对齐 SQLBot {@code get_column_permission_fields}）。</p>
 */
@Service
public class DsPermissionQueryService {

    private final DsPermissionRepository permRepo;
    private final TableRepository tableRepo;
    private final FieldRepository fieldRepo;
    private final VariableRepository variableRepo;
    private final DatasourceRepository dsRepo;
    private final CurrentUserService currentUserService;

    public DsPermissionQueryService(DsPermissionRepository permRepo, TableRepository tableRepo,
                                    FieldRepository fieldRepo, VariableRepository variableRepo,
                                    DatasourceRepository dsRepo, CurrentUserService currentUserService) {
        this.permRepo = permRepo;
        this.tableRepo = tableRepo;
        this.fieldRepo = fieldRepo;
        this.variableRepo = variableRepo;
        this.dsRepo = dsRepo;
        this.currentUserService = currentUserService;
    }

    // ------------------------------------------------------------------
    // 行权限
    // ------------------------------------------------------------------

    /**
     * 计算问数 SQL 涉及各表的行权限 WHERE 片段。
     *
     * @param oid     工作空间
     * @param userId  当前用户（仅非管理员需过滤）
     * @param dsId    数据源
     * @param tables  生成的 SQL 中涉及的表名
     * @return [{table, filter}]；用户无对应权限或管理员时返回空列表
     */
    public List<Map<String, Object>> rowFilters(long oid, long userId, long dsId, List<String> tables) {
        if (tables == null || tables.isEmpty() || currentUserService.isAdmin(currentUserService.currentUser())) {
            return Collections.emptyList();
        }
        String dsType = dsTypeOf(dsId);
        if (dsType == null) {
            return Collections.emptyList();
        }
        DatasourceType dtype;
        try {
            dtype = DatasourceType.fromType(dsType);
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String tableName : tables) {
            Optional<Map<String, Object>> table = tableRepo.findByDsAndName(dsId, tableName);
            if (!table.isPresent()) {
                continue;
            }
            long tableId = Rows.lng(table.get(), "id", 0L);
            Map<Long, String> fieldName = new HashMap<>();
            Map<Long, String> fieldType = new HashMap<>();
            for (Map<String, Object> f : fieldRepo.listByTableId(tableId)) {
                fieldName.put(Rows.lng(f, "id", 0L), Rows.str(f, "field_name"));
                fieldType.put(Rows.lng(f, "id", 0L), Rows.str(f, "field_type"));
            }
            List<Map<String, Object>> perms = permissionsFor(oid, userId, tableId, "row");
            if (perms.isEmpty()) {
                continue;
            }
            List<String> conditions = new ArrayList<>();
            for (Map<String, Object> perm : perms) {
                String treeJson = Rows.str(perm, "expression_tree");
                if (treeJson == null || treeJson.isEmpty() || "{}".equals(treeJson)) {
                    continue;
                }
                Object tree = permRepo.fromJson(treeJson, Object.class, null);
                String where = translateTree(tree, dtype, fieldName, fieldType);
                if (where != null && !where.isEmpty()) {
                    conditions.add(where);
                }
            }
            if (!conditions.isEmpty()) {
                result.add(Rows.of("table", tableName,
                        "filter", "(" + String.join(" AND ", conditions) + ")"));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 列权限
    // ------------------------------------------------------------------

    /**
     * 当前用户在某表上无权限（enable=false）的列名集合，用于 schema 上下文剔除。
     */
    public Set<String> hiddenColumns(long oid, long userId, long dsId, String tableName) {
        Set<String> hidden = new HashSet<>();
        if (tableName == null || currentUserService.isAdmin(currentUserService.currentUser())) {
            return hidden;
        }
        Optional<Map<String, Object>> table = tableRepo.findByDsAndName(dsId, tableName);
        if (!table.isPresent()) {
            return hidden;
        }
        long tableId = Rows.lng(table.get(), "id", 0L);
        List<Map<String, Object>> perms = permissionsFor(oid, userId, tableId, "column");
        Map<Long, String> fieldName = new HashMap<>();
        for (Map<String, Object> f : fieldRepo.listByTableId(tableId)) {
            fieldName.put(Rows.lng(f, "id", 0L), Rows.str(f, "field_name"));
        }
        for (Map<String, Object> perm : perms) {
            List<Map<String, Object>> items = permRepo.jsonArray(Rows.str(perm, "permissions"));
            for (Map<String, Object> item : items) {
                if (!Rows.bool(item, "enable", true)) {
                    Long fieldId = Rows.lngOrNull(item, "field_id");
                    if (fieldId != null) {
                        String name = fieldName.get(fieldId);
                        if (name != null) {
                            hidden.add(name);
                        }
                    }
                }
            }
        }
        return hidden;
    }

    // ------------------------------------------------------------------
    // 规则 + 权限条目匹配
    // ------------------------------------------------------------------

    private List<Map<String, Object>> permissionsFor(long oid, long userId, long tableId, String type) {
        List<Map<String, Object>> all = permRepo.listPermissionsByTableAndType(tableId, type);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> perm : all) {
            long ruleId = Rows.lng(perm, "auth_target_id", 0L);
            long permId = Rows.lng(perm, "id", 0L);
            if (ruleContainsUser(ruleId, oid, userId, permId)) {
                matched.add(perm);
            }
        }
        return matched;
    }

    private boolean ruleContainsUser(long ruleId, long oid, long userId, long permissionId) {
        Optional<Map<String, Object>> rule = permRepo.findRule(ruleId);
        if (!rule.isPresent()) {
            return false;
        }
        Map<String, Object> r = rule.get();
        long ruleOid = Rows.lng(r, "oid", 0L);
        if (ruleOid != 0L && ruleOid != oid) {
            return false;
        }
        List<Long> permList = permRepo.longArray(Rows.str(r, "permission_list"));
        List<Long> userList = permRepo.longArray(Rows.str(r, "user_list"));
        if (permList.isEmpty() || !permList.contains(permissionId)) {
            return false;
        }
        if (userList.isEmpty() || !userList.contains(userId)) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // expression_tree → WHERE（搬运 SQLBot row_permission.py）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String translateTree(Object tree, DatasourceType dtype, Map<Long, String> fieldName,
                                 Map<Long, String> fieldType) {
        if (!(tree instanceof Map)) {
            return null;
        }
        Map<String, Object> node = (Map<String, Object>) tree;
        Object logicObj = node.get("logic");
        if (!(logicObj instanceof String)) {
            return null;
        }
        String logic = ((String) logicObj).toUpperCase();
        if (!"AND".equals(logic) && !"OR".equals(logic)) {
            return null;
        }
        Object itemsObj = node.get("items");
        if (!(itemsObj instanceof List)) {
            return null;
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String type = Rows.str(item, "type");
            String exp = null;
            if ("item".equals(type)) {
                exp = translateItem(item, dtype, fieldName, fieldType);
            } else if ("tree".equals(type)) {
                exp = translateTree(item.get("sub_tree"), dtype, fieldName, fieldType);
            }
            if (exp != null && !exp.isEmpty()) {
                parts.add(exp);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "(" + String.join(" " + logic + " ", parts) + ")";
    }

    @SuppressWarnings("unchecked")
    private String translateItem(Map<String, Object> item, DatasourceType dtype, Map<Long, String> fieldName,
                                Map<Long, String> fieldType) {
        Long fieldId = Rows.lngOrNull(item, "field_id");
        if (fieldId == null || !fieldName.containsKey(fieldId)) {
            return null;
        }
        String column = DatasourceType.quote(dtype, fieldName.get(fieldId));
        String fieldTypeStr = fieldType.getOrDefault(fieldId, "");
        String term = Rows.str(item, "term");
        String termOp = translateTerm(term);

        String filterType = Rows.str(item, "filter_type");
        if ("enum".equals(filterType)) {
            List<Object> enumValue = (List<Object>) item.getOrDefault("enum_value",
                    Collections.emptyList());
            List<String> escaped = new ArrayList<>();
            for (Object v : enumValue) {
                if (v != null) {
                    escaped.add(escapeSqlValue(String.valueOf(v)));
                }
            }
            if (escaped.isEmpty()) {
                return null;
            }
            return column + inClause(escaped, isNType(dtype, fieldTypeStr));
        }

        String whereValue;
        String valueType = Rows.str(item, "value_type");
        if ("variable".equals(valueType)) {
            whereValue = resolveVariable(item, term, dtype, fieldTypeStr);
        } else {
            whereValue = resolveNormal(item, term, dtype, fieldTypeStr);
        }
        if (whereValue == null) {
            return null;
        }
        return column + termOp + whereValue;
    }

    private String resolveNormal(Map<String, Object> item, String term, DatasourceType dtype, String fieldTypeStr) {
        if ("null".equals(term) || "not_null".equals(term) || "empty".equals(term)
                || "not_empty".equals(term)) {
            return "";
        }
        Object valueObj = item.get("value");
        String value = valueObj == null ? "" : String.valueOf(valueObj);
        if ("in".equals(term) || "not in".equals(term)) {
            List<String> escaped = new ArrayList<>();
            for (String v : value.split(",")) {
                if (!v.trim().isEmpty()) {
                    escaped.add(escapeSqlValue(v.trim()));
                }
            }
            if (escaped.isEmpty()) {
                return null;
            }
            return inClause(escaped, isNType(dtype, fieldTypeStr));
        }
        if ("like".equals(term) || "not like".equals(term)) {
            return "'" + escapeSqlValue(value) + "'";
        }
        return "'" + escapeSqlValue(value) + "'";
    }

    @SuppressWarnings("unchecked")
    private String resolveVariable(Map<String, Object> item, String term, DatasourceType dtype,
                                  String fieldTypeStr) {
        Long variableId = Rows.lngOrNull(item, "variable_id");
        if (variableId == null) {
            return null;
        }
        Map<String, Object> sysVar = variableRepo.findById(variableId).orElse(null);
        if (sysVar == null) {
            return null;
        }
        String varType = Rows.str(sysVar, "type");
        if ("system".equals(varType)) {
            List<Object> value = (List<Object>) sysVar.getOrDefault("value", Collections.emptyList());
            String attr = value.isEmpty() ? null : String.valueOf(value.get(0));
            String v = resolveSystemAttr(attr);
            return buildVariableWhereValue(term, v, dtype, fieldTypeStr);
        }
        List<Map<String, Object>> userVars = currentUserVariables();
        for (Map<String, Object> uv : userVars) {
            if (variableId.equals(Rows.lngOrNull(uv, "variableId"))
                    || variableId.equals(Rows.lngOrNull(uv, "variable_id"))) {
                List<Object> values = (List<Object>) uv.getOrDefault("variableValues",
                        uv.getOrDefault("variable_values", Collections.emptyList()));
                return buildUserVariableWhereValue(term, Rows.str(sysVar, "var_type"), values,
                        dtype, fieldTypeStr);
            }
        }
        return null;
    }

    private String resolveSystemAttr(String attr) {
        Map<String, Object> user = currentUserService.currentUser();
        if ("name".equals(attr)) {
            return Rows.str(user, "name");
        }
        if ("account".equals(attr)) {
            return Rows.str(user, "account");
        }
        if ("email".equals(attr)) {
            return Rows.str(user, "email");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> currentUserVariables() {
        Map<String, Object> user = currentUserService.currentUser();
        Object raw = user.get("system_variables");
        if (raw == null) {
            return Collections.emptyList();
        }
        try {
            return (List<Map<String, Object>>) raw;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String buildVariableWhereValue(String term, String v, DatasourceType dtype, String fieldTypeStr) {
        if (v == null) {
            return null;
        }
        if ("null".equals(term) || "not_null".equals(term) || "empty".equals(term)
                || "not_empty".equals(term)) {
            return "";
        }
        if ("in".equals(term) || "not in".equals(term)) {
            List<String> escaped = new ArrayList<>();
            for (String s : v.split(",")) {
                if (!s.trim().isEmpty()) {
                    escaped.add(escapeSqlValue(s.trim()));
                }
            }
            if (escaped.isEmpty()) {
                return null;
            }
            return inClause(escaped, isNType(dtype, fieldTypeStr));
        }
        if ("like".equals(term) || "not like".equals(term)) {
            return "'" + escapeSqlValue(v) + "'";
        }
        return "'" + escapeSqlValue(v) + "'";
    }

    private String buildUserVariableWhereValue(String term, String varType, List<Object> values,
                                              DatasourceType dtype, String fieldTypeStr) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if ("null".equals(term) || "not_null".equals(term) || "empty".equals(term)
                || "not_empty".equals(term)) {
            return "";
        }
        List<String> stringValues = new ArrayList<>();
        for (Object o : values) {
            stringValues.add(String.valueOf(o));
        }
        if ("in".equals(term) || "not in".equals(term)) {
            List<String> escaped = new ArrayList<>();
            for (String s : stringValues) {
                escaped.add(escapeSqlValue(s));
            }
            return inClause(escaped, isNType(dtype, fieldTypeStr));
        }
        if ("like".equals(term) || "not like".equals(term)) {
            return "'" + escapeSqlValue(stringValues.get(0)) + "'";
        }
        return "'" + escapeSqlValue(stringValues.get(0)) + "'";
    }

    private static String inClause(List<String> escaped, boolean nType) {
        if (nType) {
            return "(N'" + String.join("',N'", escaped) + "')";
        }
        return "('" + String.join("','", escaped) + "')";
    }

    private static boolean isNType(DatasourceType dtype, String fieldTypeStr) {
        if (dtype != DatasourceType.SQLSERVER) {
            return false;
        }
        String t = fieldTypeStr == null ? "" : fieldTypeStr.toLowerCase();
        return t.contains("nchar") || t.contains("nvarchar");
    }

    private static String translateTerm(String term) {
        if ("eq".equals(term)) {
            return " = ";
        }
        if ("not_eq".equals(term)) {
            return " <> ";
        }
        if ("lt".equals(term)) {
            return " < ";
        }
        if ("le".equals(term)) {
            return " <= ";
        }
        if ("gt".equals(term)) {
            return " > ";
        }
        if ("ge".equals(term)) {
            return " >= ";
        }
        if ("in".equals(term)) {
            return " IN ";
        }
        if ("not in".equals(term)) {
            return " NOT IN ";
        }
        if ("like".equals(term)) {
            return " LIKE ";
        }
        if ("not like".equals(term)) {
            return " NOT LIKE ";
        }
        if ("null".equals(term)) {
            return " IS NULL ";
        }
        if ("not_null".equals(term)) {
            return " IS NOT NULL ";
        }
        if ("empty".equals(term)) {
            return " = ";
        }
        if ("not_empty".equals(term)) {
            return " <> ";
        }
        if ("between".equals(term)) {
            return " BETWEEN ";
        }
        return "";
    }

    private static String escapeSqlValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    private String dsTypeOf(long dsId) {
        Optional<Map<String, Object>> ds = dsRepo.findById(dsId);
        return ds.isPresent() ? Rows.str(ds.get(), "type") : null;
    }
}
