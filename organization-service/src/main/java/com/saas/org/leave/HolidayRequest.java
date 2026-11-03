package com.saas.org.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record HolidayRequest(@NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date, @NotBlank @Size(max = 100) String name) {
}
