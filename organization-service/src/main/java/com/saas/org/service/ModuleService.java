package com.saas.org.service;

import com.saas.org.dto.ModulesResponse;
import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.ModuleChangedPayload;
import com.saas.org.leave.ModuleDisabledException;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * The business modules of an organization and its time zone (REQ-MOD-01). Which modules are on is a setting of the
 * organization, made by its ADMIN and off by default - not a platform feature flag. A module that is off does not
 * exist for the tenant's members, but its data is kept and is back when it is switched on again. Same
 * {@link TransactionTemplate} + {@link TenantContext#callWith} pattern as {@link ThemeService}.
 */
@Service
public class ModuleService {

    /** The leave-request module (REQ-LEAVE-01..08). */
    public static final String LEAVE = "LEAVE";

    /** Every module this version has; only an ADMIN can switch them. */
    public static final List<String> MODULES = List.of(LEAVE);

    private final TenantSettingsRepository settingsRepository;
    private final MembershipRepository membershipRepository;
    private final ThemeService themeService;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public ModuleService(TenantSettingsRepository settingsRepository, MembershipRepository membershipRepository,
            ThemeService themeService, OutboxWriter outboxWriter, PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.membershipRepository = membershipRepository;
        this.themeService = themeService;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Any member sees which modules are on and the time zone. */
    public ModulesResponse get(UUID tenantId, UUID callerUserId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            requireMember(callerUserId);
            return view(settingsRepository.findFirstByOrderByCreatedAtAsc().orElseGet(themeService::newDefaultSettings));
        }));
    }

    /** ADMIN with write access only; recorded in the tenant journal only when the setting really changes. */
    public ModulesResponse setEnabled(UUID tenantId, UUID callerUserId, String module, boolean enabled) {
        if (!MODULES.contains(module)) {
            throw new IllegalArgumentException("unknown module");
        }
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            requireAdmin(callerUserId, "only an ADMIN with write access can switch a module");
            TenantSettings settings = settings();
            if (settings.moduleEnabled(module) != enabled) {
                // a fresh map: Hibernate detects the change on the replaced reference, not by mutation in place
                var modules = new LinkedHashMap<>(settings.getModulesJson());
                modules.put(module, enabled);
                settings.setModulesJson(modules);
                settingsRepository.save(settings);
                outboxWriter.writeEvent(EventEnvelope.forTenant("MODULE_CHANGED", 1, tenantId,
                        new ModuleChangedPayload(tenantId, callerUserId, module, enabled)), OutboxDestination.KAFKA, "org.events");
            }
            return view(settings);
        }));
    }

    /** ADMIN with write access only; the zone must be one the JDK knows by region name (not an offset). */
    public ModulesResponse setTimezone(UUID tenantId, UUID callerUserId, String timezone) {
        String zone = validZone(timezone);
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            requireAdmin(callerUserId, "only an ADMIN with write access can change the time zone");
            TenantSettings settings = settings();
            settings.setTimezone(zone);
            settingsRepository.save(settings);
            return view(settings);
        }));
    }

    /**
     * For the module's own services, inside their tenant transaction: the tenant's settings if the module is on,
     * otherwise {@link ModuleDisabledException} (a 404: the module does not exist for this tenant).
     */
    public TenantSettings requireEnabled(String module) {
        return settingsRepository.findFirstByOrderByCreatedAtAsc()
                .filter(settings -> settings.moduleEnabled(module))
                .orElseThrow(ModuleDisabledException::new);
    }

    /** The tenant's settings row, created with the defaults the first time it is needed. */
    public TenantSettings settings() {
        return settingsRepository.findFirstByOrderByCreatedAtAsc().orElseGet(themeService::newDefaultSettings);
    }

    private ModulesResponse view(TenantSettings settings) {
        var modules = new LinkedHashMap<String, Boolean>();
        MODULES.forEach(module -> modules.put(module, settings.moduleEnabled(module)));
        return new ModulesResponse(modules, settings.getTimezone());
    }

    private static String validZone(String timezone) {
        if (timezone == null || timezone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new IllegalArgumentException("not a known time zone; use a region name such as Europe/Bucharest");
        }
        try {
            return ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a known time zone; use a region name such as Europe/Bucharest");
        }
    }

    private Membership requireMember(UUID userId) {
        return membershipRepository.findByUserId(userId)
                .orElseThrow(() -> new SecurityException("not a member of this organization"));
    }

    private void requireAdmin(UUID userId, String message) {
        Membership caller = requireMember(userId);
        if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
            throw new SecurityException(message);
        }
    }
}
