package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CalendarController.class)
@org.springframework.security.test.context.support.WithMockUser
@org.springframework.context.annotation.Import(AuthenticatedMvcTestConfig.class)
class CalendarControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({
            "2026, 4, 3, 30, 35",
            "2024, 2, 4, 29, 35",
            "2026, 2, 0, 28, 28",
            "2026, 8, 6, 31, 42",
            "2026, 10, 4, 31, 35",
            "2026, 1, 4, 31, 35",
            "2026, 12, 2, 31, 35"
    })
    void rendersCalendarWithEmptyCellsAndMonthLinks(int year, int month, int leadingEmptyCells,
            int daysInMonth, int cellCount) throws Exception {
        String html = mockMvc.perform(get("/calendar")
                .param("year", String.valueOf(year)).param("month", String.valueOf(month)))
                .andExpect(status().isOk())
                .andExpect(view().name("calendar"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        YearMonth displayMonth = YearMonth.of(year, month);
        assertTrue(html.contains(year + "年" + month + "月"));
        assertTrue(html.contains("data-from=\"" + displayMonth.atDay(1) + "\""));
        assertTrue(html.contains("data-to=\"" + displayMonth.atEndOfMonth() + "\""));
        assertMonthLink(html, displayMonth.minusMonths(1), "前の月");
        assertMonthLink(html, displayMonth.plusMonths(1), "次の月");

        Matcher headings = Pattern.compile("<th scope=\"col\">(.*?)</th>").matcher(html);
        for (String weekday : new String[] { "日", "月", "火", "水", "木", "金", "土" }) {
            assertTrue(headings.find());
            assertEquals(weekday, headings.group(1));
        }
        assertFalse(headings.find());

        String body = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        assertEquals(cellCount / 7, Pattern.compile("<tr>").matcher(body).results().count());
        Matcher cells = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL).matcher(body);
        for (int index = 0; index < cellCount; index++) {
            assertTrue(cells.find());
            int day = index - leadingEmptyCells + 1;
            if (day < 1 || day > daysInMonth) {
                assertFalse(cells.group(1).contains("data-date"));
                assertTrue(cells.group(2).isBlank());
            } else {
                assertTrue(cells.group(1).contains("data-date=\"" + displayMonth.atDay(day) + "\""));
                assertEquals(String.valueOf(day), cells.group(2).replaceAll("<[^>]+>", "").strip());
            }
        }
        assertFalse(cells.find());
    }

    @Test
    void defaultsBothMissingParametersToCurrentMonth() throws Exception {
        mockMvc.perform(get("/calendar"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("displayMonth", YearMonth.now()));
    }

    @Test
    void defaultsOnlyMissingYear() throws Exception {
        mockMvc.perform(get("/calendar").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("displayMonth", YearMonth.of(YearMonth.now().getYear(), 4)));
    }

    @Test
    void defaultsOnlyMissingMonth() throws Exception {
        mockMvc.perform(get("/calendar").param("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("displayMonth", YearMonth.of(2024, YearMonth.now().getMonthValue())));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "13", "abc" })
    void rejectsInvalidMonth(String month) throws Exception {
        mockMvc.perform(get("/calendar").param("month", month))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-04-05, 2026-04-05, 2026-04-11",
            "2026-04-06, 2026-04-05, 2026-04-11",
            "2026-04-11, 2026-04-05, 2026-04-11",
            "2026-05-01, 2026-04-26, 2026-05-02",
            "2027-01-01, 2026-12-27, 2027-01-02",
            "2024-02-29, 2024-02-25, 2024-03-02"
    })
    void rendersExactlySevenDaysFromSundayIncludingMonthAndYearBoundaries(
            String date, String from, String to) throws Exception {
        String html = mockMvc.perform(get("/calendar").param("view", "week").param("date", date))
                .andExpect(status().isOk())
                .andExpect(view().name("calendar"))
                .andExpect(model().attribute("weekView", true))
                .andExpect(model().attribute("firstDate", LocalDate.parse(from)))
                .andExpect(model().attribute("lastDate", LocalDate.parse(to)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(html.contains("data-from=\"" + from + "\""));
        assertTrue(html.contains("data-to=\"" + to + "\""));
        assertTrue(html.contains("data-view=\"week\""));
        assertTrue(html.contains("aria-label=\"週間カレンダー\""));
        assertFalse(html.contains(">前の月</a>"));
        String body = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        assertEquals(1, Pattern.compile("<tr>").matcher(body).results().count());
        Matcher cells = Pattern.compile("<td[^>]*data-date=\"([^\"]+)\"[^>]*>(.*?)</td>",
                Pattern.DOTALL).matcher(body);
        for (int day = 0; day < 7; day++) {
            assertTrue(cells.find());
            LocalDate expected = LocalDate.parse(from).plusDays(day);
            assertEquals(expected.toString(), cells.group(1));
            assertEquals(expected.getMonthValue() + "/" + expected.getDayOfMonth(),
                    cells.group(2).replaceAll("<[^>]+>", "").strip());
        }
        assertFalse(cells.find());
        String decoded = HtmlUtils.htmlUnescape(html);
        assertTrue(decoded.contains("href=\"/calendar?view=week&date="
                + LocalDate.parse(from).minusWeeks(1) + "\">前の週</a>"));
        assertTrue(decoded.contains("href=\"/calendar?view=week&date="
                + LocalDate.parse(from).plusWeeks(1) + "\">次の週</a>"));
    }

    @Test
    void switchesFromSelectedMonthToWeekAndBackToSameMonth() throws Exception {
        String monthHtml = mockMvc.perform(get("/calendar").param("year", "2026").param("month", "4")
                        .param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String weekLink = link(monthHtml, "週で見る");
        String weekHtml = mockMvc.perform(get(weekLink))
                .andExpect(status().isOk())
                .andExpect(model().attribute("firstDate", LocalDate.of(2026, 3, 29)))
                .andExpect(model().attribute("lastDate", LocalDate.of(2026, 4, 4)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        mockMvc.perform(get(link(weekHtml, "月で見る")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("weekView", false))
                .andExpect(model().attribute("displayMonth", YearMonth.of(2026, 4)));
    }

    @Test
    void defaultsWeekToWeekContainingToday() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
        mockMvc.perform(get("/calendar").param("view", "week"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("firstDate", sunday))
                .andExpect(model().attribute("lastDate", sunday.plusDays(6)));
    }

    @Test
    void keepsSelectedWeekWhenSwitchingToMonthAndBack() throws Exception {
        String weekHtml = mockMvc.perform(get("/calendar").param("view", "week").param("date", "2026-05-29"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String monthHtml = mockMvc.perform(get(link(weekHtml, "月で見る")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("displayMonth", YearMonth.of(2026, 5)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        mockMvc.perform(get(link(monthHtml, "週で見る")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("firstDate", LocalDate.of(2026, 5, 24)))
                .andExpect(model().attribute("lastDate", LocalDate.of(2026, 5, 30)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "bad-date", "2026-02-30" })
    void rejectsInvalidWeekDate(String date) throws Exception {
        mockMvc.perform(get("/calendar").param("view", "week").param("date", date))
                .andExpect(status().isBadRequest());
    }

    private String link(String html, String label) {
        Matcher link = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>" + label + "</a>").matcher(html);
        assertTrue(link.find(), label);
        return HtmlUtils.htmlUnescape(link.group(1));
    }

    private void assertMonthLink(String html, YearMonth month, String label) {
        assertTrue(html.contains("href=\"/calendar?year=" + month.getYear()
                + "&amp;month=" + month.getMonthValue() + "\">" + label + "</a>"));
    }
}
