package com.saas.auth.security;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Password policy for registration and reset (REQ-PWD-06): min 12 chars, checked against a
 * common/compromised password list; no forced complexity rules, no password history in v1. */
@Component
public class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final String COMPROMISED_LIST_RESOURCE = "/security/compromised-passwords.txt";

    private final Set<String> compromisedPasswords = loadCompromisedPasswords();

    public void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (compromisedPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("This password is too common; choose a different one");
        }
    }

    private static Set<String> loadCompromisedPasswords() {
        try (var in = PasswordPolicy.class.getResourceAsStream(COMPROMISED_LIST_RESOURCE)) {
            if (in == null) {
                return Set.of();
            }
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .map(line -> line.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load the compromised password list", e);
        }
    }
}
