package com.xiongx9527.askdata.permission;

import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源使用授权管理端点（对齐 SQLBot 数据源授权治理）。
 * 工作空间管理端操作：把成员授权到数据源（白名单覆盖语义，见 {@link DsGrantService}）。
 */
@RestController
@RequestMapping({"api/v1"})
public class DsGrantController {

    private final DsGrantService dsGrantService;

    public DsGrantController(DsGrantService dsGrantService) {
        this.dsGrantService = dsGrantService;
    }

    /** POST /ds_grant/list：{ds_id} → 授权明细（含用户展示名）。 */
    @PostMapping({"/ds_grant/list"})
    public Result<List<Map<String, Object>>> list(@RequestBody Map<String, Object> body) {
        return Result.ok(dsGrantService.list(com.xiongx9527.askdata.common.util.Rows.lng(body, "ds_id", 0L)));
    }

    /** POST /ds_grant/save：{ds_id, user_ids: []} 覆盖式保存；空列表清空授权。 */
    @PostMapping({"/ds_grant/save"})
    public Result<Void> save(@RequestBody Map<String, Object> body) {
        dsGrantService.save(body);
        return Result.ok();
    }

    /** POST /ds_grant/delete/{id}：删除单条授权。 */
    @PostMapping({"/ds_grant/delete/{id}"})
    public Result<Void> delete(@PathVariable("id") long id) {
        dsGrantService.delete(id);
        return Result.ok();
    }
}
