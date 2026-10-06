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

    /** No tenant/token exists yet at this point (0-tenant user creating their first organization), so
     * the gateway cannot forward a tenant-scoped principal here; the caller is identified by this field. */
    @NotNull
    private UUID creatorUserId;
}
