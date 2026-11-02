package com.saas.org.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The feature flags the platform knows about (REQ-PERS-07). Declaring one is a configuration change
 * ({@code saas.feature-flags.known}), not a migration; anything else - a typo, an arbitrary key - is
 * rejected, so the settings document cannot be filled with junk and a flag name never reaches a query
 * or a log line unvalidated.
 */
@Component
public class FeatureFlagRegistry {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,50}$");

    private final Set<String> known;

    public FeatureFlagRegistry(@Value("${saas.feature-flags.known:}") String known) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(known.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(name -> {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("invalid feature flag name in configuration: " + name);
            }
            names.add(name);
        });
        this.known = Collections.unmodifiableSet(names);
    }

    public Set<String> known() {
        return known;
    }

    public boolean isKnown(String flag) {
        return flag != null && known.contains(flag);
    }
}
