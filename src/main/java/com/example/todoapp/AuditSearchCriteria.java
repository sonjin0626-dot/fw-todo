package com.example.todoapp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AuditSearchCriteria {
    public static final int PAGE_SIZE = 20;
    @Pattern(regexp = "^(|TODO)$")
    private String entityType;
    @Min(1) private Long entityId;
    @Min(1) private Long userId;
    @Pattern(regexp = "^(|CREATE|UPDATE|DELETE|BULK_DELETE|RESTORE|TOGGLE_PIN)$")
    private String action;
    @Pattern(regexp = "^(|ATTEMPTED|COMMITTED|ROLLED_BACK|NO_CHANGE|FAILED|UNKNOWN|LEGACY)$")
    private String outcome;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;
    @Min(0) @Max(1000000) private int page;

    public int getLimit() { return PAGE_SIZE; }
    public long getOffset() { return (long) page * PAGE_SIZE; }
    public LocalDateTime getFromTime() { return fromDate == null ? null : fromDate.atStartOfDay(); }
    public LocalDateTime getUntilTime() { return toDate == null ? null : toDate.plusDays(1).atStartOfDay(); }

    @AssertTrue
    public boolean isDateRangeValid() {
        return (fromDate == null || (fromDate.getYear() >= 1 && fromDate.getYear() <= 9998))
                && (toDate == null || (toDate.getYear() >= 1 && toDate.getYear() <= 9998))
                && (fromDate == null || toDate == null || !fromDate.isAfter(toDate));
    }
}
