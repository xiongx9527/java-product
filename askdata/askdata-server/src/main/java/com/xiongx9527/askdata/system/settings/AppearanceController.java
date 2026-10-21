package com.xiongx9527.askdata.system.settings;

import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.embedded.AssistantFileStorage;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 外观定制读取端点（对齐 SQLBot xpack 的 {@code /system/appearance/*}；
 * SQLBot 的 {@code common/utils/whitelist.py} 将 {@code /system/appearance/ui} 与
 * {@code /system/appearance/picture/*} 列为公共路径，登录页/嵌入页无需登录即可读取品牌外观）。
 *
 * <p>本类只提供<b>读取</b>；写入（{@code POST /system/appearance}）属于管理动作，
 * 由 {@code ParameterService} 一侧的参数保存路径承担（前端 {@code views/system/appearance/index.vue}
 * 以 multipart 提交 data+files），此处不重复实现。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class AppearanceController {

    private final AppearanceService appearanceService;

    private final AssistantFileStorage fileStorage;

    public AppearanceController(AppearanceService appearanceService, AssistantFileStorage fileStorage) {
        this.appearanceService = appearanceService;
        this.fileStorage = fileStorage;
    }

    /**
     * GET /system/appearance/ui：外观键值数组。
     *
     * <p>前端 {@code web/src/stores/appearance.ts} 逐项读 {@code item.pkey} / {@code item.pval}，
     * key 为去掉 {@code appearance.} 前缀后的名字（navigateBg / themeColor / logo …）。</p>
     */
    @GetMapping({"/system/appearance/ui"})
    public Result<List<Map<String, Object>>> ui() {
        return Result.ok(appearanceService.ui());
    }

    /** GET /system/appearance/picture/{file_id}：外观图片。 */
    @GetMapping({"/system/appearance/picture/{file_id}"})
    public ResponseEntity<Resource> picture(@PathVariable("file_id") String fileId) throws IOException {
        Resource resource = fileStorage.load(fileId);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, AssistantFileStorage.contentTypeOf(fileId))
                .body(resource);
    }
}
