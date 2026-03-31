package com.saas.notification.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeatureFlagService {

    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    public FeatureFlagService() {
        flags.put("NEW_DASHBOARD", false);
        flags.put("BETA_BILLING", true);
        flags.put("IN_APP_NOTIFICATIONS", true);
        flags.put("FILE_UPLOADS", true);
        flags.put("INVOICE_PDF_EXPORT", true);
    }

    public Map<String, Boolean> getAll() {
        return Map.copyOf(flags);
    }

    public boolean isEnabled(String key) {
        return flags.getOrDefault(key, false);
    }

    public Map<String, Boolean> update(String key, boolean enabled) {
        flags.put(key, enabled);
        return Map.copyOf(flags);
    }
}
