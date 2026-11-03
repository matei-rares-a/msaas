package com.saas.org.lunch;

import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.LunchGroupsFormedPayload;
import com.saas.org.event.LunchRoundCancelledPayload;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.org.service.ModuleService;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.EventPayload;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The weekly lunch roulette (REQ-LUNCH-01..05). Everything here runs in one transaction of the caller's tenant and starts by
 * asking who the caller is and whether the module is on (a module that is off is a 404 for everything, REQ-MOD-01); the
 * read-only flag of a membership blocks every write, the member's own enrolment included.
 *
 * <p><b>Rounds.</b> There is one round per week. It is not created ahead of time: it comes into being when the first member
 * enrols (or an approver cancels the week), and until then reading it shows an empty, open round without writing anything.
 * Which week is open is decided by the cut-off: this week's while it is still ahead, otherwise next week's. A member can
 * enrol and withdraw until the cut-off; an approver (MANAGER, ADMIN) can cancel the open round before it.
 *
 * <p><b>At the cut-off</b> {@link #closeDueRound} forms the groups, or cancels the round - with a notification to its
 * participants either way - when it has fewer than two, when the organization is in its billing grace period or suspended
 * (no groups are formed then, REQ-MOD-01) or when the module has been switched off. The round row is locked while it is
 * decided, so a late enrolment and the cut-off cannot both win, and a second run finds nothing to do.
 *
 * <p>Rounds and enrolments are not audited actions and never reach the activity journal (REQ-LUNCH-05); the two events
 * published, for the notifications, carry user ids only (REQ-EVT-02).
 */
@Service
public class LunchService {

    static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    /** Why a round ends up cancelled. */
    public static final String TOO_FEW_PARTICIPANTS = "TOO_FEW_PARTICIPANTS";
    public static final String BY_APPROVER = "BY_APPROVER";
    public static final String TENANT_RESTRICTED = "TENANT_RESTRICTED";
    public static final String MODULE_DISABLED = "MODULE_DISABLED";

    /** What {@link #closeDueRound} did with a round. */
    public enum Outcome { FORMED, CANCELLED, NOTHING_TO_DO }

    private final LunchRoundRepository rounds;
    private final LunchParticipantRepository participants;
    private final MembershipRepository memberships;
    private final TenantSettingsRepository settingsRepository;
    private final ModuleService modules;
    private final LunchDeadlineRefresher deadlines;
    private final OutboxWriter outboxWriter;
    private final Random random;
    private final Clock clock;
    private final LunchGrouper grouper;
    private final int recentRounds;
    private final TransactionTemplate transactionTemplate;

    public LunchService(LunchRoundRepository rounds, LunchParticipantRepository participants, MembershipRepository memberships,
            TenantSettingsRepository settingsRepository, ModuleService modules, LunchDeadlineRefresher deadlines,
            OutboxWriter outboxWriter, Random lunchRandom, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${saas.lunch.recent-rounds:4}") int recentRounds,
            @Value("${saas.lunch.grouping-attempts:200}") int groupingAttempts) {
        this.rounds = rounds;
        this.participants = participants;
        this.memberships = memberships;
        this.settingsRepository = settingsRepository;
        this.modules = modules;
        this.deadlines = deadlines;
        this.outboxWriter = outboxWriter;
        this.random = lunchRandom;
        this.clock = clock;
        this.grouper = new LunchGrouper(groupingAttempts);
        this.recentRounds = recentRounds;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ---- the weekly settings (REQ-LUNCH-01) ----

    public LunchConfigView config(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            return configView(modules.requireEnabled(ModuleService.LUNCH_ROULETTE));
        });
    }

    /**
     * MANAGER and ADMIN without the read-only flag set the weekday, the cut-off time and the group size. The cut-off of the
     * open round, if there is one, moves with them; groups already formed are not touched.
     */
    public LunchConfigView setConfig(UUID tenantId, UUID callerUserId, String day, String cutoff, int groupSize) {
        DayOfWeek weekday = parseDay(day);
        LocalTime time = parseCutoff(cutoff);
        if (groupSize < LunchGrouper.MIN_GROUP || groupSize > LunchGrouper.MAX_GROUP) {
            throw new IllegalArgumentException("the group size must be between " + LunchGrouper.MIN_GROUP + " and " + LunchGrouper.MAX_GROUP);
        }
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            requireApprover(caller, "configure the lunch roulette");
            settings.setLunchDay(weekday.name());
            settings.setLunchCutoff(time);
            settings.setLunchGroupSize(groupSize);
            settingsRepository.save(settings);
            deadlines.refresh(settings);
            return configView(settings);
        });
    }

    // ---- the open round and its members' actions (REQ-LUNCH-02) ----

    /** The round that can be joined now; reading it never creates anything. */
    public LunchRoundView current(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            LocalDate week = openWeek(settings);
            return rounds.findByWeekStart(week).map(round -> view(round, callerUserId))
                    .orElseGet(() -> LunchRoundView.notStarted(week, deadline(settings, week)));
        });
    }

    /** The caller joins the open round; joining twice is the same as once. */
    public LunchRoundView enroll(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            requireWriteAccess(caller);
            LunchRound round = openRoundForUpdate(settings, true);
            requireJoinable(round);
            if (!participants.existsByRoundIdAndUserId(round.getId(), callerUserId)) {
                LunchParticipant participant = new LunchParticipant();
                participant.setId(UUID.randomUUID());
                participant.setRoundId(round.getId());
                participant.setUserId(callerUserId);
                participant.setCreatedAt(clock.instant());
                participants.save(participant);
            }
            return view(round, callerUserId);
        });
    }

    /** The caller leaves the open round before its cut-off; leaving one they are not in changes nothing. */
    public LunchRoundView withdraw(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            requireWriteAccess(caller);
            LunchRound round = openRoundForUpdate(settings, false);
            if (round == null) {
                return LunchRoundView.notStarted(openWeek(settings), deadline(settings, openWeek(settings)));
            }
            requireJoinable(round);
            participants.findAllByRoundIdAndUserId(round.getId(), callerUserId).forEach(participants::delete);
            participants.flush();
            return view(round, callerUserId);
        });
    }

    /**
     * MANAGER or ADMIN cancels the open round before its cut-off; its participants are told. Cancelling a week nobody has
     * joined yet closes that week: nobody can enrol in it afterwards.
     */
    public LunchRoundView cancelCurrent(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            requireApprover(caller, "cancel a lunch round");
            LunchRound round = openRoundForUpdate(settings, true);
            requireJoinable(round);
            cancel(round, BY_APPROVER, callerUserId);
            return view(round, callerUserId);
        });
    }

    // ---- reading rounds and groups (REQ-LUNCH-04) ----

    /** Every round, newest week first: when, in what state, how many were in and whether the caller was. */
    public LunchRoundPage rounds(UUID tenantId, UUID callerUserId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be at least 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            Page<LunchRound> found = rounds.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "weekStart")));
            List<UUID> ids = found.getContent().stream().map(LunchRound::getId).toList();
            Map<UUID, Long> counts = new HashMap<>();
            Set<UUID> mine = new HashSet<>();
            if (!ids.isEmpty()) {
                participants.countByRound(ids).forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
                mine.addAll(participants.roundsOf(callerUserId, ids));
            }
            List<LunchRoundView> items = found.getContent().stream()
                    .map(round -> LunchRoundView.of(round, counts.getOrDefault(round.getId(), 0L), mine.contains(round.getId()))).toList();
            return new LunchRoundPage(items, page, size, found.getTotalElements());
        });
    }

    /**
     * One round with its groups: a participant sees their own group, MANAGER and ADMIN see all of them, anybody else sees
     * the round but no group. Groups exist only once the round is formed.
     */
    public LunchRoundDetail round(UUID tenantId, UUID callerUserId, UUID roundId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LUNCH_ROULETTE);
            LunchRound round = rounds.findById(roundId).orElseThrow(LunchRoundNotFoundException::new);
            List<LunchParticipant> all = participants.findAllByRoundId(roundId);
            boolean enrolled = all.stream().anyMatch(p -> p.getUserId().equals(callerUserId));
            LunchRoundView view = LunchRoundView.of(round, all.size(), enrolled);
            if (round.getStatus() != LunchStatus.FORMED) {
                return new LunchRoundDetail(view, null, null);
            }
            Map<Integer, List<UUID>> byGroup = new TreeMap<>();
            for (LunchParticipant participant : all) {
                if (participant.getGroupNumber() != null) {
                    byGroup.computeIfAbsent(participant.getGroupNumber(), n -> new ArrayList<>()).add(participant.getUserId());
                }
            }
            byGroup.values().forEach(group -> group.sort(Comparator.naturalOrder()));
            List<UUID> mine = byGroup.values().stream().filter(group -> group.contains(callerUserId)).findFirst().orElse(null);
            List<List<UUID>> groups = isApprover(caller) ? new ArrayList<>(byGroup.values()) : null;
            return new LunchRoundDetail(view, mine, groups);
        });
    }

    // ---- what the jobs do (REQ-LUNCH-03, REQ-LUNCH-05, REQ-MOD-01) ----

    /**
     * Closes one round whose cut-off has come: forms the groups, or cancels it (see the class description). Safe to call
     * again and for a round that is not due: it then does nothing.
     *
     * @param restricted whether the organization is in its billing grace period or suspended, asked of billing-service by
     *                   the caller before this transaction starts, so no database transaction waits on another service
     */
    public Outcome closeDueRound(UUID tenantId, UUID roundId, boolean restricted) {
        return inTenant(tenantId, () -> {
            LunchRound round = rounds.findByIdForUpdate(roundId).orElse(null);
            if (round == null || round.getStatus() != LunchStatus.OPEN || round.getDeadlineAt().isAfter(clock.instant())) {
                return Outcome.NOTHING_TO_DO;
            }
            TenantSettings settings = settingsRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
            if (settings == null || !settings.moduleEnabled(ModuleService.LUNCH_ROULETTE)) {
                cancel(round, MODULE_DISABLED, null);
                return Outcome.CANCELLED;
            }
            if (restricted) {
                cancel(round, TENANT_RESTRICTED, null);
                return Outcome.CANCELLED;
            }
            List<LunchParticipant> enrolled = participants.findAllByRoundId(roundId);
            if (enrolled.size() < LunchGrouper.MIN_GROUP) {
                cancel(round, TOO_FEW_PARTICIPANTS, null);
                return Outcome.CANCELLED;
            }
            form(round, enrolled, settings.getLunchGroupSize());
            return Outcome.FORMED;
        });
    }

    /** Deletes every round that closed before {@code cutoff}, with its enrolments and groups (REQ-LUNCH-05). */
    public int purgeOlderThan(UUID tenantId, Instant cutoff) {
        Integer deleted = inTenant(tenantId, () -> {
            List<LunchRound> old = rounds.findAllByDeadlineAtBefore(cutoff);
            if (old.isEmpty()) {
                return 0;
            }
            participants.deleteAllByRoundIdIn(old.stream().map(LunchRound::getId).toList());
            participants.flush();
            rounds.deleteAll(old);
            return old.size();
        });
        return deleted == null ? 0 : deleted;
    }

    // ---- the rounds' life ----

    private void form(LunchRound round, List<LunchParticipant> enrolled, int groupSize) {
        Set<LunchGrouper.Pair> recent = recentPairs(round);
        List<List<UUID>> groups = grouper.group(enrolled.stream().map(LunchParticipant::getUserId).toList(), groupSize, recent, random);
        Map<UUID, Integer> numberOf = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            for (UUID member : groups.get(i)) {
                numberOf.put(member, i + 1);
            }
        }
        enrolled.forEach(participant -> participant.setGroupNumber(numberOf.get(participant.getUserId())));
        participants.saveAll(enrolled);
        var now = clock.instant();
        round.setStatus(LunchStatus.FORMED);
        round.setFormedAt(now);
        round.setUpdatedAt(now);
        rounds.save(round);
        publish("LUNCH_GROUPS_FORMED", new LunchGroupsFormedPayload(TenantContext.require(), round.getId(),
                round.getWeekStart().toString(), groups));
    }

    /** Pairs that shared a group in the last few formed rounds before this one (REQ-LUNCH-03). */
    private Set<LunchGrouper.Pair> recentPairs(LunchRound current) {
        List<LunchRound> previous = rounds.findAllByStatus(LunchStatus.FORMED,
                PageRequest.of(0, recentRounds + 1, Sort.by(Sort.Direction.DESC, "weekStart"))).stream()
                .filter(round -> round.getWeekStart().isBefore(current.getWeekStart())).limit(recentRounds).toList();
        if (previous.isEmpty()) {
            return Set.of();
        }
        Map<String, List<UUID>> groups = new LinkedHashMap<>();
        for (LunchParticipant participant : participants.findAllByRoundIdIn(previous.stream().map(LunchRound::getId).toList())) {
            if (participant.getGroupNumber() != null) {
                groups.computeIfAbsent(participant.getRoundId() + "/" + participant.getGroupNumber(), k -> new ArrayList<>()).add(participant.getUserId());
            }
        }
        Set<LunchGrouper.Pair> pairs = new HashSet<>();
        for (List<UUID> group : groups.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    pairs.add(LunchGrouper.Pair.of(group.get(i), group.get(j)));
                }
            }
        }
        return pairs;
    }

    private void cancel(LunchRound round, String reason, UUID by) {
        var now = clock.instant();
        round.setStatus(LunchStatus.CANCELLED);
        round.setCancelReason(reason);
        round.setCancelledBy(by);
        round.setUpdatedAt(now);
        rounds.save(round);
        List<UUID> enrolled = participants.findAllByRoundId(round.getId()).stream().map(LunchParticipant::getUserId).sorted().toList();
        if (!enrolled.isEmpty()) {
            publish("LUNCH_ROUND_CANCELLED", new LunchRoundCancelledPayload(TenantContext.require(), round.getId(),
                    round.getWeekStart().toString(), reason, enrolled));
        }
    }

    /** The round of the open week, locked; created first when asked to and missing. Null when not asked to create and missing. */
    private LunchRound openRoundForUpdate(TenantSettings settings, boolean create) {
        LocalDate week = openWeek(settings);
        if (create) {
            var now = clock.instant();
            rounds.insertIfAbsent(UUID.randomUUID(), TenantContext.require(), week, deadline(settings, week), now);
        }
        return rounds.findByWeekStartForUpdate(week).orElse(null);
    }

    /** A round is joined, left or cancelled only while it is open and its cut-off is still ahead. */
    private void requireJoinable(LunchRound round) {
        if (round.getStatus() != LunchStatus.OPEN) {
            throw new IllegalStateException("this week's round is " + round.getStatus() + "; there is no second round in a week");
        }
        if (!clock.instant().isBefore(round.getDeadlineAt())) {
            throw new IllegalStateException("the cut-off of this week's round has passed");
        }
    }

    private LunchRoundView view(LunchRound round, UUID callerUserId) {
        return LunchRoundView.of(round, participants.countByRoundId(round.getId()), participants.existsByRoundIdAndUserId(round.getId(), callerUserId));
    }

    private LocalDate openWeek(TenantSettings settings) {
        return LunchSchedule.openWeek(clock.instant(), DayOfWeek.valueOf(settings.getLunchDay()), settings.getLunchCutoff(),
                ZoneId.of(settings.getTimezone()));
    }

    private static Instant deadline(TenantSettings settings, LocalDate week) {
        return LunchSchedule.deadline(week, DayOfWeek.valueOf(settings.getLunchDay()), settings.getLunchCutoff(),
                ZoneId.of(settings.getTimezone()));
    }

    private static LunchConfigView configView(TenantSettings settings) {
        return new LunchConfigView(settings.getLunchDay(), settings.getLunchCutoff().format(HH_MM), settings.getLunchGroupSize(), settings.getTimezone());
    }

    private static DayOfWeek parseDay(String day) {
        for (DayOfWeek candidate : DayOfWeek.values()) {
            if (candidate.name().equals(day)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("day must be a weekday in capitals, such as THURSDAY");
    }

    private static LocalTime parseCutoff(String cutoff) {
        try {
            return LocalTime.parse(cutoff, HH_MM);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("cutoff must be a time of day as HH:mm, such as 11:00");
        }
    }

    // ---- who is who ----

    private Membership requireMember(UUID userId) {
        return memberships.findByUserId(userId).orElseThrow(() -> new SecurityException("not a member of this organization"));
    }

    private static boolean isApprover(Membership membership) {
        return membership.getRole() == Membership.Role.MANAGER || membership.getRole() == Membership.Role.ADMIN;
    }

    /** MANAGER or ADMIN, and not read-only: what configuring and cancelling need (REQ-LUNCH-01, REQ-LUNCH-02). */
    private static void requireApprover(Membership membership, String what) {
        if (!isApprover(membership) || membership.isReadOnly()) {
            throw new SecurityException("only a MANAGER or an ADMIN with write access can " + what);
        }
    }

    /** The read-only flag blocks every write in the modules, including the member's own (REQ-MOD-01). */
    private static void requireWriteAccess(Membership membership) {
        if (membership.isReadOnly()) {
            throw new SecurityException("a read-only membership cannot change lunch roulette enrolments");
        }
    }

    private void publish(String eventType, EventPayload payload) {
        outboxWriter.writeEvent(EventEnvelope.forTenant(eventType, 1, TenantContext.require(), payload), OutboxDestination.KAFKA, "org.events");
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> action.get()));
    }
}
