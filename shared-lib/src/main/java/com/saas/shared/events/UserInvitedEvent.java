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
public class UserInvitedEvent {
    private String eventType = "USER_INVITED";
    private String email;
    private UUID organizationId;
    private String inviteToken;
    private Instant timestamp;
}
