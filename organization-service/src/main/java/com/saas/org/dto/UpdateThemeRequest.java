package com.saas.org.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The only thing an ADMIN can choose is a palette id from the platform catalog (REQ-PERS-02). Any other
 * property - a colour, a CSS value, a gradient - is rejected outright instead of being ignored. (Spring
 * Boot's mapper ignores unknown properties by default, so they are collected and validated explicitly.)
 */
@Getter
@Setter
public class UpdateThemeRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]{1,50}$", message = "must be a palette id from the catalog")
    private String paletteId;

    @Getter(lombok.AccessLevel.NONE)
    private final Set<String> unexpectedProperties = new LinkedHashSet<>();

    @JsonAnySetter
    void unexpected(String name, Object value) {
        unexpectedProperties.add(name);
    }

    @JsonIgnore
    @AssertTrue(message = "only paletteId may be sent")
    public boolean isOnlyPaletteIdSent() {
        return unexpectedProperties.isEmpty();
    }
}
