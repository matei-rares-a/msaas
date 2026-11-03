package com.saas.org.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A member's request for leave for themselves; who it is for is never read from the body (REQ-LEAVE-01). The dates are whole days,
 * strictly {@code yyyy-MM-dd}: a time of day is refused instead of being cut off, since there are no half days.
 */
public record CreateLeaveRequest(@NotNull LeaveType type, @NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
        @NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
        @Size(max = 500) String reason) {
}
