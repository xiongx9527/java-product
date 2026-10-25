package com.xiongx9527.askdata.system.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.Result;
import com.xiongx9527.askdata.embedded.AssistantFileStorage;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外观定制读取端点（对齐 SQLBot xpack 的 {@code /system/appearance/*}；
 * SQLBot 的 {@code common/utils/whitelist.py} 将 {@code /system/appearance/ui} 与
 * {@code /system/appearance/picture/*} 列为公共路径，登录页/嵌入页无需登录即可读取品牌外观）。
 *
 * <p><b>A5 写入</b>：{@code POST /system/appearance}（ws admin）接收前端
 * {@code views/system/appearance/index.vue} 的 multipart 提交（{@code data} 变更项 JSON 数组 +
 * {@code files} 图片，文件名形如 {@code <name>,<pkey>}），图片先落库再把文件 id 写入对应键值。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class AppearanceController {

    private final AppearanceService appearanceService;

    private final AssistantFileStorage fileStorage;

    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper;

    public AppearanceController(AppearanceService appearanceService, AssistantFileStorage fileStorage,
                                CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this.appearanceService = appearanceService;
        this.fileStorage = fileStorage;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
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

    /**
     * POST /system/appearance：保存外观配置（A5，ws admin）。
     *
     * <p>请求为 multipart/form-data：{@code data} 为 JSON 数组
     * {@code [{pkey, pval, ptype, sort}]}；{@code files} 为图片part，part 文件名
     * {@code <原始名>,<pkey>}，落库后把返回的文件 id 写进该 pkey 的 pval。</p>
     */
    @PostMapping(value = "/system/appearance", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Integer> save(@RequestPart("data") String data,
                                @RequestPart(value = "files", required = false) List<MultipartFile> files)
            throws IOException {
        currentUserService.requireWsAdmin();
        List<Map<String, Object>> items = new ArrayList<>();
        if (data != null && !data.trim().isEmpty()) {
            items = objectMapper.readValue(data, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        // 图片part：文件名 "<name>,<pkey>" → 落库 → 覆盖对应 pkey 的 pval
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
                int comma = original.lastIndexOf(',');
                String pkey = comma >= 0 ? original.substring(comma + 1).trim() : "";
                if (pkey.isEmpty()) {
                    continue;
                }
                String fileId = fileStorage.save(file, original.substring(0, Math.max(comma, 0)));
                boolean replaced = false;
                for (Map<String, Object> item : items) {
                    if (pkey.equals(Rows.str(item, "pkey"))) {
                        item.put("pval", fileId);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pkey", pkey);
                    item.put("pval", fileId);
                    item.put("ptype", "file");
                    item.put("sort", 1);
                    items.add(item);
                }
            }
        }
        return Result.ok(appearanceService.save(items));
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
