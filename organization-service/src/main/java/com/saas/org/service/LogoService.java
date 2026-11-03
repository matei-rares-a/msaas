package com.saas.org.service;

import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.ThemeChangedPayload;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.org.storage.ObjectStorage;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * A tenant's logo (REQ-PERS-01, REQ-PERS-03). Only ADMIN with write access can replace it; the bytes are
 * validated by what they are ({@link LogoValidator}), stored through {@link ObjectStorage} under a key
 * that contains the tenant id, and referenced from the settings document as {@code logoKey}/{@code logoUrl}.
 * This class depends only on the storage interface. Same {@link TransactionTemplate} +
 * {@link TenantContext#callWith} pattern as {@link ThemeService}.
 */
@Service
public class LogoService {

    static final String LOGO_KEY = "logoKey";

    public record StoredLogo(byte[] content, String contentType) {
    }

    private final TenantSettingsRepository settingsRepository;
    private final MembershipRepository membershipRepository;
    private final ThemeService themeService;
    private final LogoValidator validator;
    private final ObjectStorage storage;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public LogoService(TenantSettingsRepository settingsRepository, MembershipRepository membershipRepository,
            ThemeService themeService, LogoValidator validator, ObjectStorage storage, OutboxWriter outboxWriter,
            PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.membershipRepository = membershipRepository;
        this.themeService = themeService;
        this.validator = validator;
        this.storage = storage;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Replaces the tenant's logo; the previous file is removed once the new one is referenced. */
    public String replaceLogo(UUID tenantId, UUID callerUserId, byte[] content) {
        // authorization first: nobody who may not change the logo gets to make the server decode their bytes
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can change the logo");
            }
            return null;
        }));

        LogoValidator.LogoType type = validator.validate(content);
        String key = "tenants/" + tenantId + "/logo-" + UUID.randomUUID().toString().replace("-", "") + "." + type.extension();
        storage.put(key, content);

        String logoUrl = "/organizations/" + tenantId + "/theme/logo?v=" + UUID.randomUUID().toString().replace("-", "");
        String previousKey;
        try {
            previousKey = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
                TenantSettings settings = settingsRepository.findFirstByOrderByCreatedAtAsc().orElseGet(themeService::newDefaultSettings);
                String previous = settings.getThemeJson().get(LOGO_KEY) instanceof String s ? s : null;
                // a fresh map: Hibernate detects the change on the replaced reference, not by mutation in place
                var theme = new LinkedHashMap<>(settings.getThemeJson());
                theme.put(LOGO_KEY, key);
                theme.put(TenantSettings.LOGO_URL, logoUrl);
                settings.setThemeJson(theme);
                settingsRepository.save(settings);
                outboxWriter.writeEvent(EventEnvelope.forTenant("LOGO_CHANGED", 1, tenantId,
                        new ThemeChangedPayload(tenantId, callerUserId, null)), OutboxDestination.KAFKA, "org.events");
                return previous;
            }));
        } catch (RuntimeException e) {
            storage.delete(key); // never leave an orphan behind a failed update
            throw e;
        }
        themeService.evictTheme(tenantId); // the cached theme still has the old logo url (REQ-SCALE-01)
        if (previousKey != null && !previousKey.equals(key)) {
            storage.delete(previousKey);
        }
        return logoUrl;
    }

    /** Any member can read their own tenant's logo; empty when none was ever uploaded. */
    public Optional<StoredLogo> readLogo(UUID tenantId, UUID callerUserId) {
        String key = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            return settingsRepository.findFirstByOrderByCreatedAtAsc()
                    .map(s -> s.getThemeJson().get(LOGO_KEY) instanceof String k ? k : null)
                    .orElse(null);
        }));
        if (key == null) {
            return Optional.empty();
        }
        String extension = key.substring(key.lastIndexOf('.') + 1);
        return storage.get(key).map(bytes -> new StoredLogo(bytes, LogoValidator.LogoType.fromExtension(extension).contentType()));
    }
}
