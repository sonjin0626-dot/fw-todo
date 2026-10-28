package com.example.todoapp;

import com.example.todoapp.api.HolidayApiController;
import com.example.todoapp.api.HolidayClient;
import com.example.todoapp.api.TodoApiController;
import com.example.todoapp.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({HomeController.class, TodoApiController.class, HolidayApiController.class, CalendarController.class})
@WithMockUser
@Import(AuthenticatedMvcTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ExceptionHandlingTests {
    private static final String INTERNAL_DETAIL = "jdbc:mysql://internal-db/private-table";
    private static final String VALID_TODO = "{\"title\":\"test\",\"category\":\"資格\",\"priority\":2}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TodoService todos;

    @MockitoBean
    private HolidayClient holidays;

    @ParameterizedTest
    @ValueSource(strings = {"edit", "delete"})
    void missingTodoRenders404WithRecoveryLink(String action) throws Exception {
        mvc.perform(get("/todos/123/" + action))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"))
                .andExpect(model().attribute("todoId", 123L))
                .andExpect(content().string(containsString("指定されたToDoが見つかりません")))
                .andExpect(content().string(containsString("href=\"/todos\"")));
    }

    @Test
    void missingApiTodoReturnsProblemJsonForReadsUpdatesAndDeletes() throws Exception {
        for (var request : java.util.List.of(get("/api/todos/123"), delete("/api/todos/123"),
                put("/api/todos/123").contentType(MediaType.APPLICATION_JSON).content(VALID_TODO))) {
            mvc.perform(request).andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Not Found"))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("ID 123 のやることは見つかりませんでした。"))
                    .andExpect(jsonPath("$.instance").value("/api/todos/123"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    void apiValidationKeepsFieldErrorsAndAddsCode() throws Exception {
        mvc.perform(post("/api/todos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].message").value("やることを入力してください"))
                .andExpect(jsonPath("$.errors[?(@.field == 'category')].message").value("ジャンルを選んでください"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"priority\":\"private-value\"}"})
    void malformedJsonReturns400WithoutParserDetails(String body) throws Exception {
        mvc.perform(post("/api/todos").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HTTP_400"))
                .andExpect(content().string(not(containsString("private-value"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void invalidApiDateAndIdStay400AndUnsupportedMethodStays405() throws Exception {
        mvc.perform(get("/api/todos?from=invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/todos/not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(patch("/api/todos/123"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405))
                .andExpect(header().exists("Allow"));
    }

    @Test
    void screenBusinessErrorIsEscapedAndReturns400() throws Exception {
        when(todos.count("", "", false)).thenThrow(new BusinessException("E001", "操作できません<script>alert(1)</script>"));
        mvc.perform(get("/todos"))
                .andExpect(status().isBadRequest()).andExpect(view().name("error/business"))
                .andExpect(model().attribute("errorCode", "E001"))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    void apiBusinessErrorReturnsJson() throws Exception {
        doThrow(new BusinessException("E001", "この操作は許可されていません")).when(todos).create(any());
        mvc.perform(post("/api/todos").contentType(MediaType.APPLICATION_JSON).content(VALID_TODO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("E001"))
                .andExpect(jsonPath("$.detail").value("この操作は許可されていません"));
    }

    @Test
    void unexpectedScreenErrorIsLoggedButHiddenInResponse(CapturedOutput output) throws Exception {
        when(todos.count("", "", false)).thenThrow(new IllegalStateException(INTERNAL_DETAIL));
        mvc.perform(get("/todos"))
                .andExpect(status().isInternalServerError()).andExpect(view().name("error/500"))
                .andExpect(content().string(containsString("時間をおいて")))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
        assertThat(output.getOut()).contains("システムエラー", INTERNAL_DETAIL, "IllegalStateException");
    }

    @Test
    void unexpectedApiErrorIsLoggedButHiddenInResponse(CapturedOutput output) throws Exception {
        when(todos.findById(anyLong())).thenThrow(new IllegalStateException(INTERNAL_DETAIL));
        mvc.perform(get("/api/todos/123"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
        assertThat(output.getOut()).contains("APIシステムエラー", INTERNAL_DETAIL);
    }

    @Test
    void commonApiHandlerAlsoAppliesToHolidayController() throws Exception {
        when(holidays.fetchHolidays()).thenThrow(new IllegalStateException(INTERNAL_DETAIL));
        mvc.perform(get("/api/holidays"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
    }

    @Test
    void accessDeniedKeeps403ForScreensAndApi() throws Exception {
        when(todos.findById(anyLong())).thenThrow(new AccessDeniedException(INTERNAL_DETAIL));
        mvc.perform(get("/todos/123/edit"))
                .andExpect(status().isForbidden()).andExpect(view().name("error/403"))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
        mvc.perform(get("/api/todos/123"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
    }

    @Test
    void springHttpErrorsKeepTheirStatusAndHideInternalReason() throws Exception {
        when(todos.count("", "", false)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, INTERNAL_DETAIL));
        mvc.perform(get("/todos"))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
        when(todos.findById(anyLong())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, INTERNAL_DETAIL));
        mvc.perform(get("/api/todos/123"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
        mvc.perform(get("/calendar?month=13"))
                .andExpect(status().isBadRequest()).andExpect(view().name("error/business"));
    }

    @Test
    void unknownUrlsUse404PageOrJsonAccordingToPath() throws Exception {
        mvc.perform(get("/missing-page"))
                .andExpect(status().isNotFound()).andExpect(view().name("error/404"));
        mvc.perform(get("/api/missing-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
