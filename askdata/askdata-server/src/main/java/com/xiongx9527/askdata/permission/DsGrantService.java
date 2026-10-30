package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.util.Maps;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.i18n.MessageResolver;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源使用授权服务（对齐 SQLBot {@code @require_permissions(type='ds')} 的资源级授权）。
 *
 * <p>语义（白名单覆盖）：某数据源在当前工作空间存在任一授权记录时，仅被授权成员可用该
 * 数据源建会话/问数；完全没有授权记录时维持工作空间内共享（向后兼容，避免存量用户
 * 因引入授权层而失效）。管理员始终放行。与 {@link DsPermissionService} 的行/列权限
 * 规则职责正交：本服务只回答「该用户能否使用该数据源」。</p>
 */
@Service
public class DsGrantService {

    private final DsGrantRepository dsGrantRepository;

    private final DatasourceRepository datasourceRepository;

    private final CurrentUserService currentUserService;

    private final MessageResolver messageResolver;

    public DsGrantService(DsGrantRepository dsGrantRepository,
                          DatasourceRepository datasourceRepository,
                          CurrentUserService currentUserService,
                          MessageResolver messageResolver) {
        this.dsGrantRepository = dsGrantRepository;
        this.datasourceRepository = datasourceRepository;
        this.currentUserService = currentUserService;
        this.messageResolver = messageResolver;
    }

    /** 授权列表（工作空间管理端）：按数据源查看。 */
    public List<Map<String, Object>> list(long dsId) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        requireOwnedsDatasource(oid, dsId);
        return dsGrantRepository.listByDs(oid, dsId);
    }

    /** 保存授权（覆盖式：{ds_id, user_ids: []}；user_ids 为空表示清空授权回到共享语义）。 */
    public void save(Map<String, Object> body) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        long dsId = Rows.lng(body, "ds_id", 0L);
        if (dsId <= 0) {
            throw new BadRequestException(messageResolver.get("i18n_miss_args", Maps.of("key", "ds_id")));
        }
        requireOwnedsDatasource(oid, dsId);
        long operator = currentUserService.uid();
        List<Object> userIds = toObjectList(body.get("user_ids"));
        if (userIds == null || userIds.isEmpty()) {
            // 清空授权：回到工作空间共享语义
            dsGrantRepository.deleteByDs(oid, dsId);
            return;
        }
        List<Long> valid = new ArrayList<>();
        for (Object raw : userIds) {
            long userId = toUserId(raw);
            if (userId <= 0) {
                continue;
            }
            if (!dsGrantRepository.userInWorkspace(oid, userId)) {
                throw new BadRequestException(messageResolver.get("ds.grant.user.invalid",
                        Maps.of("id", userId)));
            }
            valid.add(userId);
        }
        dsGrantRepository.deleteByDs(oid, dsId);
        for (long userId : valid) {
            dsGrantRepository.insert(oid, dsId, userId, operator);
        }
    }

    /** 删除单条授权。 */
    public void delete(long id) {
        currentUserService.requireWsAdmin();
        long oid = currentUserService.oidOf(currentUserService.currentUser());
        dsGrantRepository.delete(oid, id);
    }

    /**
     * 问数/建会话入口校验：当前用户能否使用该数据源。
     * 管理员放行；该数据源存在授权记录（白名单模式）时须命中授权，否则拒绝。
     */
    public void checkUsable(long oid, long userId, boolean admin, long dsId) {
        if (admin || dsId <= 0) {
            return;
        }
        if (dsGrantRepository.hasAnyGrant(oid, dsId) && !dsGrantRepository.isGranted(oid, dsId, userId)) {
            throw new BadRequestException(messageResolver.get("ds.not.authorized", Maps.of("id", dsId)));
        }
    }

    /** user_ids 元素兼容 Number / {"id": n} 两种形态。 */
    private static long toUserId(Object raw) {
        if (raw instanceof Map) {
            return Rows.lng((Map<?, ?>) raw, "id", 0L);
        }
        return raw instanceof Number ? ((Number) raw).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toObjectList(Object raw) {
        if (raw instanceof List) {
            return (List<Object>) raw;
        }
        return null;
    }

    private void requireOwnedsDatasource(long oid, long dsId) {
        Map<String, Object> ds = datasourceRepository.findById(dsId)
                .orElseThrow(() -> new BadRequestException(messageResolver.get("ds.not.found", Maps.of("id", dsId))));
        if (Rows.lng(ds, "oid", 0L) != oid) {
            throw new BadRequestException(messageResolver.get("ds.not.found", Maps.of("id", dsId)));
        }
    }
}
