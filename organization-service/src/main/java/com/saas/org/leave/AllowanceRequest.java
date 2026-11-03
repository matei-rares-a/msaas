package com.saas.org.leave;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AllowanceRequest(@NotNull @Min(0) @Max(366) Integer allowance) {
}
