package com.saas.org.leave;

import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.LeaveDecidedPayload;
import com.saas.org.event.LeaveRequestedPayload;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.service.ModuleService;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.EventPayload;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Leave requests (REQ-LEAVE-01..07). Every method runs in one transaction of the caller's tenant and starts by asking
 * who the caller is and whether the module is on; a tenant whose module is off answers 404 to everything here
 * (REQ-MOD-01), and a member with the read-only flag cannot write anything, not even about themselves.
 *
 * <p>Dates are the tenant's: "today" is read from the injected {@link Clock} in the tenant's time zone, so a leave that
 * "has not started" means its first day is after the tenant's today (REQ-LEAVE-04). Days are counted when a request is
 * made (see {@link WorkingDays}) and kept on it; the balance is always computed from the requests, never stored, so a cancel
 * gives the days back by itself (REQ-LEAVE-03). The requester's membership row is locked while a request is created, so
 * two requests of one member cannot both pass the balance or the overlap check.
 *
 * <p>Events go to Kafka in the same transaction (outbox) with ids only; the reason and the comment never leave this
 * service (REQ-EVT-02).
 */
@Service
public class LeaveService {

    static final int MAX_REASON = 500;
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2200;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_CALENDAR_DAYS = 366;

    private static final List<Membership.Role> APPROVER_ROLES = List.of(Membership.Role.MANAGER, Membership.Role.ADMIN);
    private static final List<LeaveStatus> BLOCKING = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);

    /** Which requests a list asks for: the caller's own, or (approvers only) everyone's. */
    public enum Scope { MINE, ALL }

    private final LeaveRequestRepository requests;
    private final LeaveBalanceOverrideRepository overrides;
    private final PublicHolidayRepository holidays;
    private final MembershipRepository memberships;
    private final ModuleService modules;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public LeaveService(LeaveRequestRepository requests, LeaveBalanceOverrideRepository overrides,
            PublicHolidayRepository holidays, MembershipRepository memberships, ModuleService modules,
            OutboxWriter outboxWriter, Clock clock, PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.overrides = overrides;
        this.holidays = holidays;
        this.memberships = memberships;
        this.modules = modules;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ---- making a request ----

    /**
     * The caller asks for leave for themselves (REQ-LEAVE-01). Refused when the interval is not whole days of one
     * calendar year that has not started, has no working day, overlaps a request waiting or approved, or - for
     * {@code ODIHNA} - is longer than what is left of the year's allowance (REQ-LEAVE-03). When the caller is the only
     * approver of the tenant the request is approved on the spot (REQ-LEAVE-05).
     */
    public LeaveRequestView create(UUID tenantId, UUID callerUserId, LeaveType type, LocalDate start, LocalDate end, String reason) {
        return inTenant(tenantId, () -> {
            Membership caller = memberships.findByUserIdForUpdate(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            TenantSettings settings = modules.requireEnabled(ModuleService.LEAVE);
            requireWriteAccess(caller);
            if (type == null || start == null || end == null) {
                throw new IllegalArgumentException("type, startDate and endDate are required");
            }
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("endDate must not be before startDate");
            }
            if (start.getYear() != end.getYear()) {
                throw new IllegalArgumentException("a request must stay within one calendar year; make one request per year");
            }
            if (start.getYear() < MIN_YEAR || start.getYear() > MAX_YEAR) {
                throw new IllegalArgumentException("the year is outside the supported range");
            }
            if (start.isBefore(today(settings))) {
                throw new IllegalArgumentException("a request cannot start in the past");
            }
            String cleanReason = cleanText(reason, "reason");

            Set<LocalDate> nonWorking = holidayDates(start, end);
            int days = WorkingDays.count(start, end, nonWorking);
            if (days == 0) {
                throw new IllegalArgumentException("the interval has no working day");
            }
            if (requests.countOverlapping(callerUserId, BLOCKING, start, end) > 0) {
                throw new IllegalStateException("the interval overlaps another request that is waiting or approved");
            }
            if (type == LeaveType.ODIHNA) {
                BalanceView balance = balanceOf(settings, callerUserId, start.getYear());
                if (days > balance.available()) {
                    throw new IllegalStateException("not enough rest-leave days: " + days + " requested, "
                            + Math.max(balance.available(), 0) + " available in " + start.getYear());
                }
            }

            List<UUID> otherApprovers = approvers().stream().filter(id -> !id.equals(callerUserId)).toList();
            boolean autoApprove = isApprover(caller) && otherApprovers.isEmpty();

            var now = clock.instant();
            LeaveRequest request = new LeaveRequest();
            request.setId(UUID.randomUUID());
            request.setUserId(callerUserId);
            request.setType(type);
            request.setStartDate(start);
            request.setEndDate(end);
            request.setDays(days);
            request.setReason(cleanReason);
            request.setCreatedAt(now);
            request.setUpdatedAt(now);
            if (autoApprove) {
                request.setStatus(LeaveStatus.APPROVED);
                request.setAutoApproved(true);
                request.setDecidedBy(callerUserId);
                request.setDecidedAt(now);
            } else {
                request.setStatus(LeaveStatus.PENDING);
            }
            requests.save(request);

            if (autoApprove) {
                publish(tenantId, "LEAVE_AUTO_APPROVED", decided(tenantId, request, callerUserId, null));
            } else if (!otherApprovers.isEmpty()) {
                publish(tenantId, "LEAVE_REQUESTED", new LeaveRequestedPayload(tenantId, request.getId(), callerUserId,
                        otherApprovers, start.toString(), end.toString()));
            }
            return LeaveRequestView.of(request);
        });
    }

    // ---- deciding and cancelling ----

    /** An approver (MANAGER or ADMIN with write access) approves a waiting request that is not their own (REQ-LEAVE-05). */
    public LeaveRequestView approve(UUID tenantId, UUID callerUserId, UUID requestId, String comment) {
        return decide(tenantId, callerUserId, requestId, comment, LeaveStatus.APPROVED);
    }

    /** Same rules as {@link #approve}; the request ends REJECTED and its days are free again. */
    public LeaveRequestView reject(UUID tenantId, UUID callerUserId, UUID requestId, String comment) {
        return decide(tenantId, callerUserId, requestId, comment, LeaveStatus.REJECTED);
    }

    private LeaveRequestView decide(UUID tenantId, UUID callerUserId, UUID requestId, String comment, LeaveStatus outcome) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            if (!isApprover(caller) || caller.isReadOnly()) {
                throw new SecurityException("only a MANAGER or an ADMIN with write access can decide a leave request");
            }
            String cleanComment = cleanText(comment, "comment");
            LeaveRequest request = requests.findById(requestId).orElseThrow(LeaveRequestNotFoundException::new);
            if (request.getUserId().equals(callerUserId)) {
                throw new SecurityException("nobody decides their own leave request");
            }
            if (request.getStatus() != LeaveStatus.PENDING) {
                throw new IllegalStateException("the request is " + request.getStatus() + ", not waiting for a decision");
            }
            var now = clock.instant();
            request.setStatus(outcome);
            request.setDecidedBy(callerUserId);
            request.setDecisionComment(cleanComment);
            request.setDecidedAt(now);
            request.setUpdatedAt(now);
            requests.save(request);
            publish(tenantId, outcome == LeaveStatus.APPROVED ? "LEAVE_APPROVED" : "LEAVE_REJECTED",
                    decided(tenantId, request, callerUserId, LeaveStatus.PENDING));
            return LeaveRequestView.of(request);
        });
    }

    /**
     * The requester cancels their own waiting request; an approved one is cancelled by the requester or by an approver, but
     * only while its first day is still ahead (REQ-LEAVE-04). The days go back to the balance by themselves. Cancelling an
     * approved leave is audited and, when an approver does it, the requester is told (REQ-LEAVE-07).
     */
    public LeaveRequestView cancel(UUID tenantId, UUID callerUserId, UUID requestId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LEAVE);
            requireWriteAccess(caller);
            LeaveRequest request = requests.findById(requestId).orElseThrow(LeaveRequestNotFoundException::new);
            boolean owner = request.getUserId().equals(callerUserId);
            if (!owner && !isApprover(caller)) {
                throw new LeaveRequestNotFoundException();
            }
            LeaveStatus previous = request.getStatus();
            switch (previous) {
                case PENDING -> {
                    if (!owner) {
                        throw new SecurityException("only the requester cancels a waiting request; reject it instead");
                    }
                }
                case APPROVED -> {
                    if (!request.getStartDate().isAfter(today(settings))) {
                        throw new IllegalStateException("the leave has already started and can no longer be changed");
                    }
                }
                default -> throw new IllegalStateException("the request is " + previous + " and cannot be cancelled");
            }
            var now = clock.instant();
            request.setStatus(LeaveStatus.CANCELLED);
            request.setCancelledBy(callerUserId);
            request.setCancelledAt(now);
            request.setUpdatedAt(now);
            requests.save(request);
            if (previous == LeaveStatus.APPROVED) {
                publish(tenantId, "LEAVE_CANCELLED", decided(tenantId, request, callerUserId, previous));
            }
            return LeaveRequestView.of(request);
        });
    }

    // ---- reading ----

    /** The caller's own requests, or - for MANAGER and ADMIN - everyone's, newest first (REQ-LEAVE-06). */
    public LeavePage list(UUID tenantId, UUID callerUserId, Scope scope, LeaveStatus status, UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be at least 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            UUID owner;
            if (scope == Scope.ALL) {
                if (!isApprover(caller)) {
                    throw new SecurityException("only a MANAGER or an ADMIN sees everyone's leave requests");
                }
                owner = userId;
            } else {
                if (userId != null && !userId.equals(callerUserId)) {
                    throw new SecurityException("a member sees only their own leave requests");
                }
                owner = callerUserId;
            }
            Specification<LeaveRequest> filter = (root, query, builder) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (owner != null) {
                    predicates.add(builder.equal(root.get("userId"), owner));
                }
                if (status != null) {
                    predicates.add(builder.equal(root.get("status"), status));
                }
                return builder.and(predicates.toArray(Predicate[]::new));
            };
            Page<LeaveRequest> found = requests.findAll(filter, PageRequest.of(page, size,
                    Sort.by(Sort.Direction.DESC, "startDate").and(Sort.by(Sort.Direction.DESC, "id"))));
            return new LeavePage(found.getContent().stream().map(LeaveRequestView::of).toList(), page, size, found.getTotalElements());
        });
    }

    /** One request: its requester and the approvers see it; for anyone else it does not exist. */
    public LeaveRequestView get(UUID tenantId, UUID callerUserId, UUID requestId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            LeaveRequest request = requests.findById(requestId).orElseThrow(LeaveRequestNotFoundException::new);
            if (!request.getUserId().equals(callerUserId) && !isApprover(caller)) {
                throw new LeaveRequestNotFoundException();
            }
            return LeaveRequestView.of(request);
        });
    }

    /** The caller's rest-leave balance for a year; MANAGER and ADMIN may ask for another member's (REQ-LEAVE-06). */
    public BalanceView balance(UUID tenantId, UUID callerUserId, Integer year, UUID userId) {
        return inTenant(tenantId, () -> {
            Membership caller = requireMember(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LEAVE);
            UUID target = userId == null ? callerUserId : userId;
            if (!target.equals(callerUserId)) {
                if (!isApprover(caller)) {
                    throw new SecurityException("a member sees only their own balance");
                }
                memberships.findByUserId(target).orElseThrow(() -> new IllegalArgumentException("member not found"));
            }
            int forYear = year == null ? today(settings).getYear() : year;
            if (forYear < MIN_YEAR || forYear > MAX_YEAR) {
                throw new IllegalArgumentException("the year is outside the supported range");
            }
            return balanceOf(settings, target, forYear);
        });
    }

    /** Every member sees who is away and when - approved leave only, with nothing else about it (REQ-LEAVE-06). */
    public List<CalendarEntry> calendar(UUID tenantId, UUID callerUserId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and from must not be after to");
        }
        if (from.plusDays(MAX_CALENDAR_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("the calendar covers at most " + MAX_CALENDAR_DAYS + " days at a time");
        }
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            return requests.findApprovedBetween(from, to).stream()
                    .map(request -> new CalendarEntry(request.getUserId(), request.getStartDate(), request.getEndDate())).toList();
        });
    }

    // ---- helpers shared with the policy service ----

    BalanceView balanceOf(TenantSettings settings, UUID userId, int year) {
        int allocated = overrides.findByUserId(userId).map(LeaveBalanceOverride::getAllowance)
                .orElse(settings.getLeaveDefaultAllowance());
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        int approved = (int) requests.sumDays(userId, LeaveType.ODIHNA, LeaveStatus.APPROVED, yearStart, yearEnd);
        int pending = (int) requests.sumDays(userId, LeaveType.ODIHNA, LeaveStatus.PENDING, yearStart, yearEnd);
        return new BalanceView(userId, year, allocated, approved, pending, allocated - approved - pending);
    }

    private Set<LocalDate> holidayDates(LocalDate from, LocalDate to) {
        Set<LocalDate> dates = new HashSet<>();
        holidays.findAllByHolidayDateBetweenOrderByHolidayDate(from, to).forEach(holiday -> dates.add(holiday.getHolidayDate()));
        return dates;
    }

    /** MANAGER or ADMIN without the read-only flag: the members who can decide a request (REQ-LEAVE-05). */
    private List<UUID> approvers() {
        return memberships.findAllByRoleInAndReadOnlyFalse(APPROVER_ROLES).stream().map(Membership::getUserId).sorted().toList();
    }

    private static boolean isApprover(Membership membership) {
        return APPROVER_ROLES.contains(membership.getRole());
    }

    private LocalDate today(TenantSettings settings) {
        return LocalDate.now(clock.withZone(ZoneId.of(settings.getTimezone())));
    }

    private Membership requireMember(UUID userId) {
        return memberships.findByUserId(userId).orElseThrow(() -> new SecurityException("not a member of this organization"));
    }

    /** The read-only flag blocks every write in the modules, including the member's own (REQ-MOD-01). */
    private static void requireWriteAccess(Membership caller) {
        if (caller.isReadOnly()) {
            throw new SecurityException("a read-only membership cannot change leave requests");
        }
    }

    private static String cleanText(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_REASON) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_REASON + " characters");
        }
        return trimmed;
    }

    private static LeaveDecidedPayload decided(UUID tenantId, LeaveRequest request, UUID actor, LeaveStatus previous) {
        return new LeaveDecidedPayload(tenantId, request.getId(), actor, request.getUserId(), request.getType().name(),
                request.getStartDate().toString(), request.getEndDate().toString(), request.getDays(),
                previous == null ? null : previous.name());
    }

    private void publish(UUID tenantId, String eventType, EventPayload payload) {
        outboxWriter.writeEvent(EventEnvelope.forTenant(eventType, 1, tenantId, payload), OutboxDestination.KAFKA, "org.events");
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> action.get()));
    }
}
