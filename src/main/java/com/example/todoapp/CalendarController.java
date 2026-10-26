package com.example.todoapp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class CalendarController {

    @GetMapping("/calendar")
    public String calendar(@RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month, Model model) {
        YearMonth currentMonth = YearMonth.now();
        YearMonth displayMonth;
        YearMonth previousMonth;
        YearMonth nextMonth;
        try {
            displayMonth = YearMonth.of(year == null ? currentMonth.getYear() : year,
                    month == null ? currentMonth.getMonthValue() : month);
            previousMonth = displayMonth.minusMonths(1);
            nextMonth = displayMonth.plusMonths(1);
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "年月が正しくありません", exception);
        }

        LocalDate firstDate = displayMonth.atDay(1);
        LocalDate lastDate = displayMonth.atEndOfMonth();
        // 日曜日を0、月曜日を1、…、土曜日を6として、先頭の空のマス数を求める。
        int leadingEmptyCells = firstDate.getDayOfWeek().getValue() % 7;
        int weekCount = (leadingEmptyCells + displayMonth.lengthOfMonth() + 6) / 7;
        List<List<LocalDate>> weeks = new ArrayList<>();

        for (int weekIndex = 0; weekIndex < weekCount; weekIndex++) {
            List<LocalDate> week = new ArrayList<>();
            for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
                int dayOfMonth = weekIndex * 7 + dayIndex - leadingEmptyCells + 1;
                if (dayOfMonth < 1 || dayOfMonth > displayMonth.lengthOfMonth()) {
                    week.add(null);
                } else {
                    week.add(displayMonth.atDay(dayOfMonth));
                }
            }
            weeks.add(week);
        }

        model.addAttribute("displayMonth", displayMonth);
        model.addAttribute("previousMonth", previousMonth);
        model.addAttribute("nextMonth", nextMonth);
        model.addAttribute("firstDate", firstDate);
        model.addAttribute("lastDate", lastDate);
        model.addAttribute("weeks", weeks);
        return "calendar";
    }
}
