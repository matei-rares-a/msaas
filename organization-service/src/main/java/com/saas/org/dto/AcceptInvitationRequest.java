package com.saas.org.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AcceptInvitationRequest {

    @NotBlank
    private String token;
}
