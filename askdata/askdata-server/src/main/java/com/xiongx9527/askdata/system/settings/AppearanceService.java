package com.xiongx9527.askdata.system.settings;

import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.system.repository.SysArgRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外观定制（对齐 SQLBot xpack 的 {@code /system/appearance/*}）。
 *
 * <p>SQLBot 的外观配置存在 {@code sys_arg} 表中，key 以 {@code appearance.} 开头；
 * {@code apps/system/crud/parameter_manage.py} 的通用参数列表会过滤掉这些 key
 * （{@code get_group_args} 排除 {@code appearance.} 前缀），因此外观端点需要<b>自己的</b>查询。</p>
 *
 * <p>前端契约（{@code web/src/stores/appearance.ts} 与 {@code views/system/appearance/index.vue}）：</p>
 * <ul>
 *   <li>{@code GET /system/appearance/ui} → 键值数组 {@code [{id, pkey, pval, ptype, sort_no}]}，
 *       前端按 {@code item.pkey} / {@code item.pval} 逐项消费；</li>
 *   <li>{@code GET /system/appearance/picture/{file_id}} → 外观图片二进制。</li>
 * </ul>
 */
@Service
public class AppearanceService {

    /** 外观参数前缀（SQLBot 语义：{@code appearance.}）。 */
    public static final String APPEARANCE_PREFIX = "appearance.";

    private final SysArgRepository sysArgRepository;

    public AppearanceService(SysArgRepository sysArgRepository) {
        this.sysArgRepository = sysArgRepository;
    }

    /**
     * 外观键值数组。
     *
     * <p>SQLBot 侧外观配置以 {@code appearance.} 前缀存储，但前端消费的 key 是<strong>去掉前缀后</strong>的
     * 名字（{@code navigateBg} / {@code themeColor} / {@code logo} …）。SQLBot xpack 的
     * {@code get_group_args('appearance')} 正是把前缀剥掉后返回，因此这里同样剥掉前缀。</p>
     */
    public List<Map<String, Object>> ui() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : sysArgRepository.listAppearance()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Rows.lng(row, "id", 0L));
            item.put("pkey", stripPrefix(Rows.str(row, "pkey")));
            item.put("pval", Rows.str(row, "pval"));
            item.put("ptype", Rows.str(row, "ptype"));
            item.put("sort_no", Rows.intg(row, "sort_no", 1));
            result.add(item);
        }
        return result;
    }

    /** 剥掉 {@code appearance.} 前缀。 */
    static String stripPrefix(String pkey) {
        if (pkey == null) {
            return null;
        }
        return pkey.startsWith(APPEARANCE_PREFIX) ? pkey.substring(APPEARANCE_PREFIX.length()) : pkey;
    }
}
