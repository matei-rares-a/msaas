package com.saas.org.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which flag names the platform declares (REQ-PERS-07): a configuration list, validated at startup. */
@Tag("REQ-PERS-07")
class FeatureFlagRegistryTest {

    @Test
    void withNothingDeclaredNoFlagExistsAndNothingIsKnown() {
        FeatureFlagRegistry registry = new FeatureFlagRegistry("");

        assertThat(registry.known()).isEmpty();
        assertThat(registry.isKnown("anything")).isFalse();
        assertThat(registry.isKnown(null)).isFalse();
    }

    @Test
    void declaredNamesAreTrimmedAndKnownAndNothingElseIs() {
        FeatureFlagRegistry registry = new FeatureFlagRegistry(" beta-dashboard , new-reports,,");

        assertThat(registry.known()).containsExactly("beta-dashboard", "new-reports");
        assertThat(registry.isKnown("beta-dashboard")).isTrue();
        assertThat(registry.isKnown("Beta-Dashboard")).isFalse();
    }

    @ParameterizedTest(name = "a declared name {0} stops the application from starting")
    @ValueSource(strings = {"Has-Capitals", "with space", "under_score", "semi;colon", "quote'", "../up"})
    void anInvalidNameInTheConfigurationFailsFast(String name) {
        assertThatThrownBy(() -> new FeatureFlagRegistry("ok-flag," + name)).isInstanceOf(IllegalStateException.class);
    }
}
