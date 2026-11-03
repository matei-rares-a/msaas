package com.saas.org.service;

import com.saas.org.entity.TenantSettings;

/**
 * Told, inside the transaction that changes a tenant's settings, about a change another module depends on. The time zone
 * is one: lunch roulette's cut-off is a time in it, so an open round's cut-off has to move with it (REQ-MOD-01).
 */
public interface TenantSettingsListener {

    void timezoneChanged(TenantSettings settings);
}
