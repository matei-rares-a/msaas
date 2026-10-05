package com.saas.org.dto;

import com.saas.org.entity.Membership;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemberRoleRequest {

    @NotNull
    private Membership.Role role;
}
