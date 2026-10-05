package com.saas.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CreateOrganizationRequest {

    @NotBlank
    private String name;

    /** Interim, until P5.3 wires the real JWT principal through the gateway. */
    @NotNull
    private UUID creatorUserId;
}
