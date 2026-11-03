package com.saas.org.lunch;

import com.saas.org.entity.TenantSettings;
import com.saas.org.service.TenantSettingsListener;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.ZoneId;

/**
 * Moves the cut-off of a tenant's open round when the settings it is worked out from change - the weekday, the time or the
 * tenant's time zone (REQ-LUNCH-01, REQ-MOD-01). Runs inside the transaction that changes the settings, so the two never
 * disagree. Rounds that are already formed or cancelled keep the cut-off they had.
 */
@Component
public class LunchDeadlineRefresher implements TenantSettingsListener {

    private final LunchRoundRepository rounds;

    public LunchDeadlineRefresher(LunchRoundRepository rounds) {
        this.rounds = rounds;
    }

    @Override
    public void timezoneChanged(TenantSettings settings) {
        refresh(settings);
    }

    public void refresh(TenantSettings settings) {
        DayOfWeek day = DayOfWeek.valueOf(settings.getLunchDay());
        ZoneId zone = ZoneId.of(settings.getTimezone());
        for (LunchRound round : rounds.findAllByStatus(LunchStatus.OPEN)) {
            round.setDeadlineAt(LunchSchedule.deadline(round.getWeekStart(), day, settings.getLunchCutoff(), zone));
            rounds.save(round);
        }
    }
}
