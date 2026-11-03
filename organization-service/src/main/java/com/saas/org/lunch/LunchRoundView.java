package com.saas.org.lunch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A round as any member sees it: when, in what state, how many are in (not who) and whether the caller is. */
public record LunchRoundView(UUID id, LocalDate weekStart, Instant deadlineAt, LunchStatus status, String cancelReason,
        long participantCount, boolean enrolled) {

    /** What the open week looks like before anybody enrolled and a row exists: nothing to hide, nothing stored yet. */
    static LunchRoundView notStarted(LocalDate weekStart, Instant deadlineAt) {
        return new LunchRoundView(null, weekStart, deadlineAt, LunchStatus.OPEN, null, 0, false);
    }

    static LunchRoundView of(LunchRound round, long participantCount, boolean enrolled) {
        return new LunchRoundView(round.getId(), round.getWeekStart(), round.getDeadlineAt(), round.getStatus(),
                round.getCancelReason(), participantCount, enrolled);
    }
}
