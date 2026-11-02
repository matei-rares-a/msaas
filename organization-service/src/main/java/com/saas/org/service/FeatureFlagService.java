package com.saas.org.service;

import com.saas.org.entity.TenantSettings;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant feature flags for gradual rollout (REQ-PERS-07, REQ-ARCH-02), stored in the tenant's own
 * settings row so they follow the same isolation as everything else (tenant filter + RLS): a flag
 * switched on for one tenant cannot be seen, let alone affect, another. Every known flag is disabled
 * until switched on. Platform operators switch flags; tenant members can only read their own. Same
 * {@link TransactionTemplate} + {@link TenantContext#callWith} pattern as {@link ThemeService}.
 */
@Service
public class FeatureFlagService {

    private final TenantSettingsRepository settingsRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final ThemeService themeService;
    private final FeatureFlagRegistry registry;
    private final TransactionTemplate transactionTemplate;

    public FeatureFlagService(TenantSettingsRepository settingsRepository, TenantRepository tenantRepository,
            MembershipRepository membershipRepository, ThemeService themeService, FeatureFlagRegistry registry,
            PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.themeService = themeService;
        this.registry = registry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** What feature code asks: is this flag on for this tenant? Unknown or never-set flags are off. */
    public boolean isEnabled(UUID tenantId, String flag) {
        if (!registry.isKnown(flag)) {
            return false;
        }
        return Boolean.TRUE.equals(flagsOf(tenantId).get(flag));
    }

    /** Every known flag with its current value for one tenant, readable by any member of that tenant. */
    public Map<String, Boolean> flagsForMember(UUID tenantId, UUID callerUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            return null;
        }));
        return resolved(flagsOf(tenantId));
    }

    /** Switches one flag for one tenant (platform operators only: reached through the internal API). */
    public Map<String, Boolean> setFlag(UUID tenantId, String flag, boolean enabled) {
        if (!registry.isKnown(flag)) {
            throw new IllegalArgumentException("unknown feature flag: " + flag);
        }
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            tenantRepository.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("organization not found"));
            TenantSettings settings = settingsRepository.findFirstByOrderByCreatedAtAsc().orElseGet(themeService::newDefaultSettings);
            // a fresh map: Hibernate detects the change on the replaced reference, not by mutation in place
            var flags = new LinkedHashMap<>(settings.getFeatureFlagsJson());
            flags.put(flag, enabled);
            settings.setFeatureFlagsJson(flags);
            settingsRepository.save(settings);
            return null;
        }));
        return resolved(flagsOf(tenantId));
    }

    private Map<String, Object> flagsOf(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status ->
                settingsRepository.findFirstByOrderByCreatedAtAsc().map(TenantSettings::getFeatureFlagsJson).orElse(Map.of())));
    }

    private Map<String, Boolean> resolved(Map<String, Object> stored) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String flag : registry.known()) {
            result.put(flag, Boolean.TRUE.equals(stored.get(flag)));
        }
        return result;
    }
}
