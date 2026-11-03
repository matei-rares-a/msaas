package com.saas.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TimezoneRequest(@NotBlank @Size(max = 64) String timezone) {
}
