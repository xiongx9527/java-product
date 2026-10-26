package com.xiongx9527.askdata.mcp;

import com.xiongx9527.askdata.common.debounce.DebounceProperties;
import com.xiongx9527.askdata.common.i18n.TestMessageResolver;
import com.xiongx9527.askdata.common.util.IdGenerator;
import com.xiongx9527.askdata.common.util.Rows;
import com.xiongx9527.askdata.common.web.ApiExceptionHandler;
import com.xiongx9527.askdata.common.web.BadRequestException;
import com.xiongx9527.askdata.common.web.ForbiddenException;
import com.xiongx9527.askdata.system.repository.UserRepository;
import com.xiongx9527.askdata.system.repository.UserWsRepository;
import com.xiongx9527.askdata.system.repository.WorkspaceRepository;
import com.xiongx9527.askdata.system.service.CurrentUserService;
import com.xiongx9527.askdata.system.service.UserService;
import com.xiongx9527.askdata.system.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP gap closure 的列表形状与错误映射（A8/A10）：
 * - mcp_ws_list 只返回 {@code {id,name}} 并按翻译后的名称升序；
 * - Web workspace list 仍保留 create_time（MCP 裁剪不影响 REST 契约）；
 * - MCP 路由的 400/403 使用 {@code {"detail":...}}，非 MCP 路由保持纯文本。
 */
class McpSqlbotGapClosureTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @SuppressWarnings("unchecked")
    void workspaceOptionsOnlyIdAndNameSortedByLocalizedName() {
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        UserWsRepository userWsRepository = mock(UserWsRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.currentUser())
                .thenReturn(Rows.of("id", 1L, "account", "admin", "oid", 1L, "language", "en"));
        when(currentUserService.isAdmin(any())).thenReturn(true);
        when(workspaceRepository.list()).thenReturn(Arrays.asList(
                Rows.of("id", 10L, "name", "Zeta", "create_time", 100L),
                Rows.of("id", 20L, "name", "i18n_default_workspace", "create_time", 200L),
                Rows.of("id", 30L, "name", "Alpha", "create_time", 300L)));

        WorkspaceService service = new WorkspaceService(workspaceRepository, userWsRepository,
                mock(UserRepository.class), mock(UserService.class), currentUserService, new IdGenerator());
        List<Map<String, Object>> options = service.listOptions();

        assertEquals(3, options.size(), options.toString());
        assertEquals("Alpha", options.get(0).get("name"));
        assertEquals("Default Workspace", options.get(1).get("name"), options.toString());
        assertEquals("Zeta", options.get(2).get("name"));
        for (Map<String, Object> option : options) {
            assertEquals(2, option.size(), "MCP 工作空间选项只含 id/name: " + option);
            assertTrue(option.containsKey("id") && option.containsKey("name"), option.toString());
        }

        // REST 列表契约不变：仍包含 create_time
        List<Map<String, Object>> restList = service.list();
        assertEquals(3, restList.get(0).size(), restList.get(0).toString());
        assertTrue(restList.get(0).containsKey("create_time"), restList.get(0).toString());
    }

    @Test
    void mcpRouteErrorsUseJsonDetailWhileWebRouteStaysPlainText() {
        ApiExceptionHandler handler = new ApiExceptionHandler(
                TestMessageResolver.create(), new DebounceProperties());

        useRequestUri("/api/v1/mcp/mcp_question");
        ResponseEntity<String> bad = handler.badRequest(new BadRequestException("bad arg"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("{\"detail\":\"bad arg\"}", bad.getBody());

        ResponseEntity<String> forbidden = handler.forbidden(new ForbiddenException("invalid token"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals("{\"detail\":\"invalid token\"}", forbidden.getBody());

        useRequestUri("/api/v1/chat/list");
        ResponseEntity<String> webBad = handler.badRequest(new BadRequestException("chat bad"));
        assertEquals(HttpStatus.BAD_REQUEST, webBad.getStatusCode());
        assertEquals("chat bad", webBad.getBody());
    }

    private static void useRequestUri(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
