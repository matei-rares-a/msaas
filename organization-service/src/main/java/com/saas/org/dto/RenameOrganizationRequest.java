package com.saas.org.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RenameOrganizationRequest {

    @NotBlank
    private String name;
}
