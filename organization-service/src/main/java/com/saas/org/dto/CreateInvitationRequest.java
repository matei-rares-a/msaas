package com.saas.org.dto;

import com.saas.org.entity.Membership;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateInvitationRequest {

    @NotBlank
    @Email
    private String email;

    @NotNull
    private Membership.Role role;
}
