package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.util.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源使用授权（ds_grant）JDBC 访问层。
 *
 * <p>白名单覆盖语义：某数据源在当前工作空间存在任一授权记录时，只有被授权用户可用该
 * 数据源建会话/问数；完全无记录时维持工作空间内共享（向后兼容）。与 ds_permission
 * （行/列权限规则）职责不同：本表只回答「该用户能否使用该数据源」。</p>
 */
@Repository
public class DsGrantRepository {

    private final JdbcTemplate jdbc;

    private final IdGenerator idGenerator;

    public DsGrantRepository(JdbcTemplate jdbc, IdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    /** 某数据源在指定工作空间的全部授权（管理端展示）。 */
    public List<Map<String, Object>> listByDs(long oid, long dsId) {
        return jdbc.queryForList(
                "SELECT g.id, g.oid, g.ds_id, g.user_id, g.create_by, g.create_time, u.account, u.name "
                        + "FROM ds_grant g LEFT JOIN sys_user u ON u.id = g.user_id "
                        + "WHERE g.oid = ? AND g.ds_id = ? ORDER BY g.create_time DESC",
                oid, dsId);
    }

    /** 某数据源在指定工作空间是否已有授权记录（白名单模式开关）。 */
    public boolean hasAnyGrant(long oid, long dsId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ds_grant WHERE oid = ? AND ds_id = ?", Integer.class, oid, dsId);
        return count != null && count > 0;
    }

    /** 用户是否已被授权该数据源。 */
    public boolean isGranted(long oid, long dsId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ds_grant WHERE oid = ? AND ds_id = ? AND user_id = ?",
                Integer.class, oid, dsId, userId);
        return count != null && count > 0;
    }

    /** 授权（幂等：先清后插，兼容 H2 测试库与 MySQL 生产库）。 */
    public void insert(long oid, long dsId, long userId, long createBy) {
        jdbc.update("DELETE FROM ds_grant WHERE oid = ? AND ds_id = ? AND user_id = ?", oid, dsId, userId);
        jdbc.update("INSERT INTO ds_grant (id, oid, ds_id, user_id, create_by, create_time) VALUES (?, ?, ?, ?, ?, ?)",
                idGenerator.nextId(), oid, dsId, userId, createBy, System.currentTimeMillis());
    }

    /** 删除单条授权。 */
    public int delete(long oid, long id) {
        return jdbc.update("DELETE FROM ds_grant WHERE oid = ? AND id = ?", oid, id);
    }

    /** 按数据源清空授权（回到工作空间共享语义）。 */
    public void deleteByDs(long oid, long dsId) {
        jdbc.update("DELETE FROM ds_grant WHERE oid = ? AND ds_id = ?", oid, dsId);
    }

    /** 保存前校验：授权对象须为同工作空间的存量用户。 */
    public boolean userInWorkspace(long oid, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = ? AND oid = ?", Integer.class, userId, oid);
        return count != null && count > 0;
    }

    /** 列表元素转视图（含展示名）。 */
    public List<Map<String, Object>> toViews(List<Map<String, Object>> rows) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            views.add(row);
        }
        return views;
    }
}
