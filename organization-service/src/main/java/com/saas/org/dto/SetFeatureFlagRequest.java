package com.saas.org.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetFeatureFlagRequest {

    @NotNull
    private Boolean enabled;
}
