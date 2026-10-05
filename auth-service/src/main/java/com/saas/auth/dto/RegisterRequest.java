package com.saas.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Email
    private String email;

    /** Length and the compromised-password check are enforced by PasswordPolicy (REQ-PWD-06), not here. */
    @NotBlank
    private String password;
}
