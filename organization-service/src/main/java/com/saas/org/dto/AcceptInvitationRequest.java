package com.saas.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class AcceptInvitationRequest {

    @NotBlank
    private String token;

    /** No tenant/token exists yet for the accepting user on THIS tenant (same situation as
     * CreateOrganizationRequest.creatorUserId) - the caller is identified by this field. */
    @NotNull
    private UUID acceptingUserId;
}
