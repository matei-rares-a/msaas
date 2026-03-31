package com.saas.shared.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PasswordResetEvent {
    private String eventType = "PASSWORD_RESET";
    private String email;
    private String token;
    private UUID userId;
    private Instant timestamp;
}
