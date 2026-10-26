package com.xiongx9527.askdata.datasource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.datasource.repository.DatasourceRepository;
import com.xiongx9527.askdata.datasource.repository.RecommendedProblemRepository;
import com.xiongx9527.askdata.permission.DsGrantService;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源推荐问题（对齐 SQLBot apps/datasource/api/recommended_problem.py +
 * apps/datasource/crud/recommended_problem.py）。
 *
 * <p>配置语义：{@code recommended_config=1} 关闭（questions 为 null），
 * 其他值开启并返回问题 JSON 串（对齐 SQLBot RecommendedProblemResponse）。</p>
 *
 * <p>A6 端点授权：list/base/save 要求 {@code ws_admin} 且数据源通过资源授权
 * （{@link DsGrantService#checkUsable}，即 SQLBot {@code @require_permissions(type='ds')}），
 * 未授权用户被拒绝。</p>
 */
@Service
public class RecommendedProblemService {

    private static final String ISO_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final RecommendedProblemRepository problemRepository;

    private final DatasourceRepository datasourceRepository;

    private final CurrentUserService currentUserService;

    private final ObjectMapper objectMapper;

    private final DsGrantService dsGrantService;

    /** A6：Spring 注入完整依赖（含 ws_admin 与资源授权）。 */
    @Autowired
    public RecommendedProblemService(RecommendedProblemRepository problemRepository,
                                     DatasourceRepository datasourceRepository,
                                     CurrentUserService currentUserService, ObjectMapper objectMapper,
                                     DsGrantService dsGrantService) {
        this.problemRepository = problemRepository;
        this.datasourceRepository = datasourceRepository;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.dsGrantService = dsGrantService;
    }

    /** 兼容旧构造（测试用，不带资源授权依赖）。 */
    public RecommendedProblemService(RecommendedProblemRepository problemRepository,
                                     DatasourceRepository datasourceRepository,
                                     CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this(problemRepository, datasourceRepository, currentUserService, objectMapper, null);
    }

    /** A6：要求 ws_admin 且数据源通过资源授权（SQLBot @require_permissions(type='ds')）。 */
    private void checkAuthorized(long dsId) {
        if (dsGrantService == null) {
            return; // 测试/旧路径降级
        }
        Map<String, Object> ds = datasourceRepository.findById(dsId)
                .orElseThrow(() -> new com.xiongx9527.askdata.datasource.DatasourceException("no datasource"));
        long oid = Rows.lng(ds, "oid", 0L);
        currentUserService.requireWsAdmin();
        dsGrantService.checkUsable(oid, currentUserService.uid(),
                currentUserService.isAdmin(currentUserService.currentUser()), dsId);
    }

    /** GET /recommended_problem/get_datasource_recommended/{ds_id}：推荐问题列表（A6：要求 ws_admin + 资源授权）。 */
    public List<Map<String, Object>> list(long dsId) {
        checkAuthorized(dsId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : problemRepository.listByDsId(dsId)) {
            Object createTime = Rows.get(row, "create_time");
            result.add(Rows.of(
                    "id", Rows.lng(row, "id", 0L),
                    "datasource_id", Rows.lng(row, "datasource_id", 0L),
                    "question", Rows.str(row, "question"),
                    "remark", Rows.str(row, "remark"),
                    "sort", Rows.intg(row, "sort", 0),
                    "create_time", createTime instanceof Timestamp
                            ? new SimpleDateFormat(ISO_TIME).format((Timestamp) createTime)
                            : createTime == null ? null : String.valueOf(createTime),
                    "create_by", Rows.lng(row, "create_by", 0L)));
        }
        return result;
    }

    /** GET /recommended_problem/get_datasource_recommended_base/{ds_id}（A6：要求 ws_admin + 资源授权）。 */
    public Map<String, Object> base(long dsId) {
        checkAuthorized(dsId);
        Integer config = datasourceRepository.recommendedConfigOf(dsId);
        if (config == null) {
            return Rows.of("datasource_id", dsId, "recommended_config", 0, "questions", null);
        }
        if (config == 1) {
            return Rows.of("datasource_id", dsId, "recommended_config", 1, "questions", null);
        }
        String questions;
        try {
            questions = objectMapper.writeValueAsString(problemRepository.questionsByDsId(dsId));
        } catch (Exception e) {
            questions = "[]";
        }
        return Rows.of("datasource_id", dsId, "recommended_config", config, "questions", questions);
    }

    /** POST /recommended_problem/save_recommended_problem：整体覆盖（对齐 SQLBot save_recommended_problem）。 */
    @Transactional
    public void save(Map<String, Object> body) {
        checkAuthorized(Rows.lng(body, "datasource_id", 0L));
        long dsId = Rows.lng(body, "datasource_id", 0L);
        int recommendedConfig = Rows.intg(body, "recommended_config", 1);
        datasourceRepository.updateRecommendedConfig(dsId, recommendedConfig);
        problemRepository.deleteByDsId(dsId);
        List<Map<String, Object>> problems = Rows.mapList(Rows.get(body, "problemInfo"));
        long uid = currentUserService.uid();
        int index = 0;
        for (Map<String, Object> problem : problems) {
            String question = Rows.str(problem, "question");
            if (question == null || question.isEmpty()) {
                continue;
            }
            problemRepository.insert(dsId, question, Rows.str(problem, "remark"),
                    Rows.get(problem, "sort") == null ? index : Rows.intg(problem, "sort", index), uid);
            index++;
        }
    }
}
