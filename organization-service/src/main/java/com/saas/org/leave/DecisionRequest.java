package com.saas.org.leave;

import jakarta.validation.constraints.Size;

public record DecisionRequest(@Size(max = 500) String comment) {
}
