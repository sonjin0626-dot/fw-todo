package com.example.todoapp;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

import com.example.todoapp.api.TodoApiController;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ HomeController.class, TodoApiController.class })
@org.springframework.security.test.context.support.WithMockUser
@Import({TodoService.class, AuthenticatedMvcTestConfig.class})
class TodoCsvTests {

    private static final String HEADER = "\uFEFF\"印\",\"やること\",\"ジャンル\",\"優先度\",\"期限\",\"状態\",\"メモ\"\r\n";

    @MockitoBean
    private TodoMapper todoMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void downloadsUtf8BomCsvWithExpectedFilenameHeadersAndEscaping() throws Exception {
        Todo todo = sample("日本語, \"題名\"\r\n続き", "メモ, \"引用\"\n改行");
        todo.setPinned(true);
        todo.setPriority(1);
        todo.setDueDate(LocalDate.of(2026, 10, 28));
        todo.setCompleted(true);
        todo.setCompletedAt(LocalDateTime.of(2026, 10, 27, 12, 34, 56));
        when(todoMapper.searchForExport(1L, "", "", "asc", false)).thenReturn(List.of(todo));

        byte[] bytes = mockMvc.perform(get("/api/todos.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"todos.csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
                java.util.Arrays.copyOf(bytes, 3));
        assertEquals(HEADER + "\"あり\",\"日本語, \"\"題名\"\"\r\n続き\",\"資格\",\"高\",\"2026-10-28\","
                + "\"完了（2026-10-27）\",\"メモ, \"\"引用\"\"\n改行\"\r\n",
                new String(bytes, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=1+2", "+SUM(1,2)", "-2+3", "@SUM(1,2)", "＝1+2", "＋1+2", "－1+2", "＠SUM(1,2)",
            "  =1+2", "\t=1+2", "\r=1+2", "\n=1+2", "\uFEFF=1+2", "\u00A0=1+2"
    })
    void protectsFormulaLikeTitleAndMemo(String value) throws Exception {
        Todo todo = sample(value, value);
        when(todoMapper.searchForExport(1L, "", "", "asc", false)).thenReturn(List.of(todo));
        String csv = new String(mockMvc.perform(get("/api/todos.csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String safeCell = "\"'\t" + value + "\"";
        assertEquals(HEADER + "\"なし\"," + safeCell + ",\"資格\",\"中\",\"\",\"未完了\"," + safeCell + "\r\n", csv);
        assertEquals(value, todo.getTitle());
        assertEquals(value, todo.getDetail());
    }

    @Test
    void cannotBreakOutOfQuotedCellToIntroduceFormula() throws Exception {
        Todo todo = sample("safe\",=1+2", "safe\";\r\n=1+2");
        when(todoMapper.searchForExport(1L, "", "", "asc", false)).thenReturn(List.of(todo));
        String csv = new String(mockMvc.perform(get("/api/todos.csv"))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertEquals(HEADER + "\"なし\",\"safe\"\",=1+2\",\"資格\",\"中\",\"\",\"未完了\",\"safe\"\";\r\n=1+2\"\r\n", csv);
    }

    @Test
    void exportsHeaderForEmptyResult() throws Exception {
        mockMvc.perform(get("/api/todos.csv"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(HEADER.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({ "asc, false", "asc, true", "desc, false", "desc, true" })
    void keepsFiltersAndSortWithoutApplyingPageOrTrash(String order, boolean includeCompleted) throws Exception {
        mockMvc.perform(get("/api/todos.csv").param("keyword", "勉強 & Java").param("category", "資格")
                .param("order", order).param("includeCompleted", String.valueOf(includeCompleted))
                .param("page", "2").param("trash", "1"))
                .andExpect(status().isOk());
        verify(todoMapper).searchForExport(1L, "勉強 & Java", "資格", order, includeCompleted);
        verifyNoMoreInteractions(todoMapper);
    }

    @Test
    void downloadLinkKeepsDisplayedFiltersAndOrdering() throws Exception {
        when(todoMapper.count(1L, "勉強 & Java", "資格", true)).thenReturn(15L);
        String html = mockMvc.perform(get("/todos").param("keyword", "勉強 & Java").param("category", "資格")
                .param("order", "desc").param("includeCompleted", "true").param("page", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher link = Pattern.compile("<a[^>]*href=\"([^\"]*todos\\.csv[^\"]*)\"[^>]*>CSVを書き出す</a>").matcher(html);
        assertTrue(link.find());
        assertTrue(link.group().contains("download=\"todos.csv\""));
        assertEquals("/api/todos.csv?keyword=勉強 & Java&category=資格&order=desc&includeCompleted=true",
                URLDecoder.decode(HtmlUtils.htmlUnescape(link.group(1)), StandardCharsets.UTF_8));

        String trash = mockMvc.perform(get("/todos").param("trash", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(trash.contains("/api/todos.csv"));
    }

    private Todo sample(String title, String detail) {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setTitle(title);
        todo.setDetail(detail);
        todo.setCategory("資格");
        todo.setPriority(2);
        todo.setCompleted(false);
        return todo;
    }
}
