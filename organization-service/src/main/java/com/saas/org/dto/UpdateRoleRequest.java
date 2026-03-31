package com.saas.org.dto;

import com.saas.org.entity.Membership;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateRoleRequest {
    @NotNull
    private Membership.Role role;
}
