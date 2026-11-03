package com.saas.org.leave;

import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.LeavePolicyChangedPayload;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.org.service.ModuleService;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The leave policy of an organization - the yearly allowance, per-member overrides and the non-working days - which
 * only an ADMIN with write access changes (REQ-LEAVE-02, REQ-LEAVE-03) and every change of which goes to the tenant journal
 * (REQ-LEAVE-07). Reading it needs only membership. The module must be on, like everything else about leave.
 *
 * <p>A change applies to requests made after it: days already counted on a request do not move when a holiday is
 * added or removed, and lowering an allowance below what is already taken leaves the balance negative rather than
 * touching approved leave.
 */
@Service
public class LeavePolicyService {

    static final int MAX_ALLOWANCE = 366;
    static final int MAX_HOLIDAY_NAME = 100;

    private final TenantSettingsRepository settingsRepository;
    private final LeaveBalanceOverrideRepository overrides;
    private final PublicHolidayRepository holidays;
    private final MembershipRepository memberships;
    private final ModuleService modules;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public LeavePolicyService(TenantSettingsRepository settingsRepository, LeaveBalanceOverrideRepository overrides,
            PublicHolidayRepository holidays, MembershipRepository memberships, ModuleService modules,
            OutboxWriter outboxWriter, Clock clock, PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.overrides = overrides;
        this.holidays = holidays;
        this.memberships = memberships;
        this.modules = modules;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PolicyView policy(UUID tenantId, UUID callerUserId) {
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            return new PolicyView(modules.requireEnabled(ModuleService.LEAVE).getLeaveDefaultAllowance());
        });
    }

    public PolicyView setDefaultAllowance(UUID tenantId, UUID callerUserId, int allowance) {
        requireAllowance(allowance);
        return inTenant(tenantId, () -> {
            requireAdmin(callerUserId);
            TenantSettings settings = modules.requireEnabled(ModuleService.LEAVE);
            if (settings.getLeaveDefaultAllowance() != allowance) {
                settings.setLeaveDefaultAllowance(allowance);
                settingsRepository.save(settings);
                publish(tenantId, callerUserId, "DEFAULT_ALLOWANCE", null, allowance, null);
            }
            return new PolicyView(settings.getLeaveDefaultAllowance());
        });
    }

    /** Gives one member their own yearly allowance in place of the default. */
    public void setOverride(UUID tenantId, UUID callerUserId, UUID targetUserId, int allowance) {
        requireAllowance(allowance);
        inTenant(tenantId, () -> {
            requireAdmin(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            memberships.findByUserId(targetUserId).orElseThrow(() -> new IllegalArgumentException("member not found"));
            var now = clock.instant();
            LeaveBalanceOverride override = overrides.findByUserId(targetUserId).orElse(null);
            if (override == null) {
                override = new LeaveBalanceOverride();
                override.setId(UUID.randomUUID());
                override.setUserId(targetUserId);
                override.setCreatedAt(now);
            } else if (override.getAllowance() == allowance) {
                return null;
            }
            override.setAllowance(allowance);
            override.setUpdatedAt(now);
            overrides.save(override);
            publish(tenantId, callerUserId, "BALANCE_OVERRIDE", targetUserId, allowance, null);
            return null;
        });
    }

    /** Puts the member back on the default allowance; removing an override that does not exist is not an error. */
    public void removeOverride(UUID tenantId, UUID callerUserId, UUID targetUserId) {
        inTenant(tenantId, () -> {
            requireAdmin(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            overrides.findByUserId(targetUserId).ifPresent(override -> {
                overrides.delete(override);
                publish(tenantId, callerUserId, "BALANCE_OVERRIDE_REMOVED", targetUserId, null, null);
            });
            return null;
        });
    }

    public List<HolidayView> holidays(UUID tenantId, UUID callerUserId, int year) {
        if (year < LeaveService.MIN_YEAR || year > LeaveService.MAX_YEAR) {
            throw new IllegalArgumentException("the year is outside the supported range");
        }
        return inTenant(tenantId, () -> {
            requireMember(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            return holidays.findAllByHolidayDateBetweenOrderByHolidayDate(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                    .map(holiday -> new HolidayView(holiday.getHolidayDate(), holiday.getName())).toList();
        });
    }

    public HolidayView addHoliday(UUID tenantId, UUID callerUserId, LocalDate date, String name) {
        if (date == null || date.getYear() < LeaveService.MIN_YEAR || date.getYear() > LeaveService.MAX_YEAR) {
            throw new IllegalArgumentException("the date is missing or outside the supported range");
        }
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > MAX_HOLIDAY_NAME) {
            throw new IllegalArgumentException("name must be 1 to " + MAX_HOLIDAY_NAME + " characters");
        }
        return inTenant(tenantId, () -> {
            requireAdmin(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            if (holidays.findByHolidayDate(date).isPresent()) {
                throw new IllegalStateException("that day is already a holiday");
            }
            PublicHoliday holiday = new PublicHoliday();
            holiday.setId(UUID.randomUUID());
            holiday.setHolidayDate(date);
            holiday.setName(cleanName);
            holiday.setCreatedAt(clock.instant());
            holidays.save(holiday);
            publish(tenantId, callerUserId, "HOLIDAY_ADDED", null, null, date.toString());
            return new HolidayView(date, cleanName);
        });
    }

    public void removeHoliday(UUID tenantId, UUID callerUserId, LocalDate date) {
        inTenant(tenantId, () -> {
            requireAdmin(callerUserId);
            modules.requireEnabled(ModuleService.LEAVE);
            PublicHoliday holiday = holidays.findByHolidayDate(date).orElseThrow(() -> new IllegalArgumentException("no such holiday"));
            holidays.delete(holiday);
            publish(tenantId, callerUserId, "HOLIDAY_REMOVED", null, null, date.toString());
            return null;
        });
    }

    private static void requireAllowance(int allowance) {
        if (allowance < 0 || allowance > MAX_ALLOWANCE) {
            throw new IllegalArgumentException("the allowance must be between 0 and " + MAX_ALLOWANCE + " days");
        }
    }

    private Membership requireMember(UUID userId) {
        return memberships.findByUserId(userId).orElseThrow(() -> new SecurityException("not a member of this organization"));
    }

    private void requireAdmin(UUID userId) {
        Membership caller = requireMember(userId);
        if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
            throw new SecurityException("only an ADMIN with write access can change the leave policy");
        }
    }

    private void publish(UUID tenantId, UUID actor, String change, UUID target, Integer allowance, String holidayDate) {
        outboxWriter.writeEvent(EventEnvelope.forTenant("LEAVE_POLICY_CHANGED", 1, tenantId,
                new LeavePolicyChangedPayload(tenantId, actor, change, target, allowance, holidayDate)),
                OutboxDestination.KAFKA, "org.events");
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> action.get()));
    }
}
