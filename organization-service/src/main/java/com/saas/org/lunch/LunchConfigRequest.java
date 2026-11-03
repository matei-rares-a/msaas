package com.saas.org.lunch;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code day} is an English weekday in capitals, {@code cutoff} is {@code HH:mm}; both are checked exactly by the service. */
public record LunchConfigRequest(@NotBlank String day, @NotBlank String cutoff, @NotNull @Min(2) @Max(4) Integer groupSize) {
}
