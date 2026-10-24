package com.xiongx9527.askdata.system.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统参数模块（对齐 SQLBot apps/system/api/parameter.py + crud/parameter_manage.py）。
 * 参数存储于 sys_arg；login/chat 分组按 pkey 前缀（SQLBot xpack get_group_args(flag) 语义）。
 */
@Service
public class ParameterService {

    private final SysArgRepository sysArgRepository;

    private final CurrentUserService currentUserService;

    private final IdGenerator idGenerator;

    private final ObjectMapper objectMapper;

    public ParameterService(SysArgRepository sysArgRepository, CurrentUserService currentUserService,
                            IdGenerator idGenerator, ObjectMapper objectMapper,
                            com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver) {
        this.sysArgRepository = sysArgRepository;
        this.currentUserService = currentUserService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.messageResolver = messageResolver;
    }

    private final com.xiongx9527.askdata.common.i18n.MessageResolver messageResolver;

    /** GET /system/parameter：全量参数（排除 appearance.*），仅管理员。 */
    public List<Map<String, Object>> list() {
        currentUserService.requireAdmin();
        return toItems(sysArgRepository.listWithoutAppearance());
    }

    /** GET /system/parameter/{login|chat}：分组读取（登录即可，页面/聊天初始化用）。 */
    public List<Map<String, Object>> group(String flag) {
        return toItems(sysArgRepository.listByPrefix(flag));
    }

    /** POST /system/parameter：multipart 的 data 字段为 JSON 数组 [{pkey, pval}]。 */
    @Transactional
    public void save(String jsonData) {
        currentUserService.requireAdmin();
        if (jsonData == null || jsonData.isEmpty()) {
            return;
        }
        List<Map<String, Object>> args;
        try {
            args = objectMapper.readValue(jsonData, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BadRequestException(messageResolver.get("parameter.invalid"));
        }
        for (Map<String, Object> arg : args) {
            String pkey = Rows.str(arg, "pkey");
            if (pkey == null || pkey.isEmpty()) {
                continue;
            }
            Object pval = Rows.get(arg, "pval");
            String text = pval == null ? null : String.valueOf(pval);
            sysArgRepository.upsert(idGenerator.nextId(), pkey, text);
        }
    }

    /** SQLBot 侧仅允许已知文件位；askdata 无外观文件位，任何上传都拒绝。 */
    public void rejectUploads(List<String> fileNames) {
        if (fileNames != null && !fileNames.isEmpty()) {
            throw new BadRequestException(messageResolver.get("parameter.file.not.allowed", fileNames.get(0)));
        }
    }

    private List<Map<String, Object>> toItems(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("pkey", Rows.str(row, "pkey"));
            item.put("pval", Rows.str(row, "pval"));
            item.put("ptype", Rows.str(row, "ptype"));
            item.put("sort_no", Rows.intg(row, "sort_no", 1));
            result.add(item);
        }
        return result;
    }
}
