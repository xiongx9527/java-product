package com.xiongx9527.askdata.chat.controller;

import com.xiongx9527.askdata.chat.service.ChatQuestionService;
import com.xiongx9527.askdata.chat.service.ChatService;
import com.xiongx9527.askdata.chat.support.ChatExcel;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.PageResult;
import com.xiongx9527.askdata.common.web.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 对话（对齐 SQLBot {@code apps/chat/api/chat.py}）：会话 CRUD、记录数据/日志/用量、
 * 提问流式主链路、分析/预测、推荐问题、最近问题、Excel 导出。
 *
 * <p>流式端点直接写 {@code text/event-stream}，不进 {@link Result} 包装——
 * 与 SQLBot 前端 {@code request.fetchStream} + {@code answer/*.vue} 的逐帧解析契约一致。</p>
 */
@RestController
@RequestMapping({"api/v1"})
public class ChatController {

    private final ChatService chatService;

    private final ChatQuestionService chatQuestionService;

    public ChatController(ChatService chatService, ChatQuestionService chatQuestionService) {
        this.chatService = chatService;
        this.chatQuestionService = chatQuestionService;
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** GET /chat/list（对齐 SQLBot chats）。 */
    @GetMapping({"/chat/list"})
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(chatService.list());
    }

    /** GET /chat/question/pager/{page}/{size}：会话分页（SQLBot 保留路由）。 */
    @GetMapping({"/chat/question/pager/{page}/{size}"})
    public Result<PageResult<Map<String, Object>>> pager(@PathVariable int page, @PathVariable int size) {
        return Result.ok(chatService.pager(page, size));
    }

    /** GET /chat/{id}（对齐 SQLBot get_chat）。 */
    @GetMapping({"/chat/{id}"})
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.ok(chatService.get(id, false));
    }

    /** GET /chat/{id}/with_data（对齐 SQLBot get_chat_with_data）。 */
    @GetMapping({"/chat/{id}/with_data"})
    public Result<Map<String, Object>> getWithData(@PathVariable long id) {
        return Result.ok(chatService.get(id, true));
    }

    /** POST /chat/start（对齐 SQLBot start_chat）。 */
    @PostMapping({"/chat/start"})
    public Result<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> creator) {
        return Result.ok(chatService.start(creator == null ? new java.util.HashMap<>() : creator));
    }

    /** POST /chat/assistant/start（嵌入通道，行为同 start；origin 默认 2）。 */
    @PostMapping({"/chat/assistant/start"})
    public Result<Map<String, Object>> assistantStart(@RequestBody(required = false) Map<String, Object> creator) {
        Map<String, Object> body = creator == null ? new java.util.HashMap<>() : new java.util.HashMap<>(creator);
        if (body.get("origin") == null) {
            body.put("origin", 2);
        }
        return Result.ok(chatService.start(body));
    }

    /** POST /chat/rename（对齐 SQLBot rename → 返回新 brief 字符串）。 */
    @PostMapping({"/chat/rename"})
    public Result<String> rename(@RequestBody Map<String, Object> renamer) {
        chatService.rename(renamer);
        Object brief = renamer.get("brief");
        return Result.ok(brief == null ? null : String.valueOf(brief));
    }

    /** DELETE /chat/{id}（对齐 SQLBot delete：body 为 {id, brief}）。 */
    @DeleteMapping({"/chat/{id}"})
    public Result<String> delete(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        chatService.delete(id, body == null ? new java.util.HashMap<>() : body);
        return Result.ok("删除成功");
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /** GET /chat/record/{rid}/data（对齐 SQLBot chat_record_data）。 */
    @GetMapping({"/chat/record/{rid}/data"})
    public Result<Map<String, Object>> recordData(@PathVariable("rid") long rid) {
        return Result.ok(chatService.recordData(rid));
    }

    /** GET /chat/record/{rid}/data_live（对齐 SQLBot chat_record_data_live）。 */
    @GetMapping({"/chat/record/{rid}/data_live"})
    public Result<Map<String, Object>> recordDataLive(@PathVariable("rid") long rid) {
        return Result.ok(chatService.recordDataLive(rid));
    }

    /** GET /chat/record/{rid}/predict_data（对齐 SQLBot chat_predict_data）。 */
    @GetMapping({"/chat/record/{rid}/predict_data"})
    public Result<List<Object>> recordPredictData(@PathVariable("rid") long rid) {
        return Result.ok(chatService.recordPredictData(rid));
    }

    /** GET /chat/record/{rid}/log（对齐 SQLBot chat_record_log）。 */
    @GetMapping({"/chat/record/{rid}/log"})
    public Result<Map<String, Object>> recordLog(@PathVariable("rid") long rid) {
        return Result.ok(chatService.recordLog(rid, false));
    }

    /** GET /chat/record/{rid}/usage（对齐 SQLBot chat_record_usage，只含 token 口径）。 */
    @GetMapping({"/chat/record/{rid}/usage"})
    public Result<Map<String, Object>> recordUsage(@PathVariable("rid") long rid) {
        return Result.ok(chatService.recordLog(rid, true));
    }

    /** GET /chat/record/{rid}/excel/export/{chat_id}（对齐 SQLBot export_excel）。 */
    @GetMapping({"/chat/record/{rid}/excel/export/{chat_id}"})
    public void exportExcel(@PathVariable("rid") long rid, @PathVariable("chat_id") long chatId,
                            HttpServletResponse response) throws IOException {
        ChatExcel.ExportFile file = chatService.exportExcel(rid);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + URLEncoder.encode(file.filename, StandardCharsets.UTF_8.name()));
        try (OutputStream out = response.getOutputStream()) {
            out.write(file.content);
        }
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    /**
     * POST /chat/question：提问主链路（流式 {@code data:{json}\n\n}）。
     * 帧类型与前端 {@code views/chat/answer/ChartAnswer.vue} 的 switch 逐字对齐。
     */
    @PostMapping({"/chat/question"})
    public void question(@RequestBody Map<String, Object> question, HttpServletResponse response)
            throws IOException {
        chatQuestionService.streamQuestion(question, response);
    }

    /** POST /chat/record/{rid}/{action_type}：analysis / predict（对齐 SQLBot）。 */
    @PostMapping({"/chat/record/{rid}/{action_type}"})
    public void analysisOrPredict(@PathVariable("rid") long rid, @PathVariable("action_type") String actionType,
                                  HttpServletResponse response) throws IOException {
        if (!"analysis".equals(actionType) && !"predict".equals(actionType)) {
            throw new BadRequestException("Type " + actionType + " Not Found");
        }
        chatQuestionService.streamAnalysisOrPredict(rid, actionType, response);
    }

    /** POST /chat/recommend_questions/{rid}（对齐 SQLBot ask_recommend_questions）。 */
    @PostMapping({"/chat/recommend_questions/{rid}"})
    public void recommendQuestions(@PathVariable("rid") long rid,
                                   @RequestParam(value = "articles_number", required = false) Integer articlesNumber,
                                   HttpServletResponse response) throws IOException {
        chatQuestionService.streamRecommendQuestions(rid, articlesNumber == null || articlesNumber <= 0
                ? 4 : articlesNumber, response);
    }

    // ------------------------------------------------------------------
    // 最近问题
    // ------------------------------------------------------------------

    /** GET /chat/recent_questions/{ds}（对齐 SQLBot recommend_questions）。 */
    @GetMapping({"/chat/recent_questions/{ds}"})
    public Result<List<String>> recentQuestions(@PathVariable("ds") long ds) {
        return Result.ok(chatService.recentQuestions(ds));
    }
}
