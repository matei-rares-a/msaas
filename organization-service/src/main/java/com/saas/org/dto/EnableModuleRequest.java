package com.saas.org.dto;

import jakarta.validation.constraints.NotNull;

public record EnableModuleRequest(@NotNull Boolean enabled) {
}
