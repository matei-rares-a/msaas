package com.saas.org.service;

import com.saas.org.dto.PaletteView;
import com.saas.org.dto.ThemeResponse;
import com.saas.org.entity.Membership;
import com.saas.org.entity.TenantSettings;
import com.saas.org.event.ThemeChangedPayload;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantSettingsRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * A tenant's visual theme (REQ-PERS-01, REQ-PERS-02, REQ-PERS-04, REQ-PERS-05, REQ-PERS-06). The theme is
 * a palette id from the platform catalog plus, later (P8.2), a logo URL; changing it is ADMIN-only and a
 * palette that is not in the catalog is rejected, so no colour or CSS ever comes from a tenant. Same
 * {@link TransactionTemplate} + {@link TenantContext#callWith} pattern as {@link OrganizationService}.
 */
@Service
public class ThemeService {

    private final TenantSettingsRepository settingsRepository;
    private final MembershipRepository membershipRepository;
    private final PaletteCatalogReader paletteCatalog;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final String defaultPaletteId;

    public ThemeService(TenantSettingsRepository settingsRepository, MembershipRepository membershipRepository,
            PaletteCatalogReader paletteCatalog, OutboxWriter outboxWriter, PlatformTransactionManager transactionManager,
            @Value("${saas.theme.default-palette-id}") String defaultPaletteId) {
        this.settingsRepository = settingsRepository;
        this.membershipRepository = membershipRepository;
        this.paletteCatalog = paletteCatalog;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.defaultPaletteId = defaultPaletteId;
    }

    /** The settings row every new tenant starts with (REQ-PERS-05); saved by the caller, in its own transaction. */
    public TenantSettings newDefaultSettings() {
        TenantSettings settings = new TenantSettings();
        settings.setId(UUID.randomUUID());
        var theme = new LinkedHashMap<String, Object>();
        theme.put(TenantSettings.PALETTE_ID, defaultPaletteId);
        settings.setThemeJson(theme);
        return settings;
    }

    /** Any member reads the theme; a tenant without a settings row yet gets the default one. */
    public ThemeResponse getTheme(UUID tenantId, UUID callerUserId) {
        TenantSettings settings = TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            requireMember(callerUserId);
            return settingsRepository.findFirstByOrderByCreatedAtAsc().orElse(null);
        }));
        String paletteId = settings != null && settings.paletteId() != null ? settings.paletteId() : defaultPaletteId;
        PaletteView palette = paletteCatalog.findAny(paletteId).or(() -> paletteCatalog.findAny(defaultPaletteId)).orElse(null);
        return new ThemeResponse(paletteId, palette, settings == null ? null : settings.logoUrl());
    }

    /** The palettes an ADMIN can currently pick from, for any member to see. */
    public List<PaletteView> listPalettes(UUID tenantId, UUID callerUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> requireMember(callerUserId)));
        return paletteCatalog.listActive();
    }

    /** ADMIN with write access only; the palette must be in the catalog and active. Other keys (the logo) are kept. */
    public ThemeResponse updatePalette(UUID tenantId, UUID callerUserId, String paletteId) {
        PaletteView palette = paletteCatalog.findActive(paletteId)
                .orElseThrow(() -> new IllegalArgumentException("unknown palette: " + paletteId));
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = requireMember(callerUserId);
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can change the theme");
            }
            TenantSettings settings = settingsRepository.findFirstByOrderByCreatedAtAsc().orElseGet(this::newDefaultSettings);
            // a fresh map: Hibernate detects the change on the replaced reference, not by mutation in place
            var theme = new LinkedHashMap<>(settings.getThemeJson());
            theme.put(TenantSettings.PALETTE_ID, palette.id());
            settings.setThemeJson(theme);
            settingsRepository.save(settings);
            outboxWriter.writeEvent(EventEnvelope.forTenant("THEME_CHANGED", 1, tenantId,
                    new ThemeChangedPayload(tenantId, callerUserId, palette.id())), OutboxDestination.KAFKA, "org.events");
            return null;
        }));
        return getTheme(tenantId, callerUserId);
    }

    private Membership requireMember(UUID userId) {
        return membershipRepository.findByUserId(userId)
                .orElseThrow(() -> new SecurityException("not a member of this organization"));
    }
}
