package com.saas.org.event;

import com.saas.org.entity.Membership;
import com.saas.org.entity.Organization;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private final OrganizationRepository orgRepository;
    private final MembershipRepository membershipRepository;

    @KafkaListener(topics = "user-events", groupId = "org-service")
    public void handleUserCreated(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        if (!"USER_CREATED".equals(eventType)) {
            return;
        }

        String userIdStr = (String) event.get("userId");
        String email = (String) event.get("email");

        UUID userId = UUID.fromString(userIdStr);

        // Create default organization for new user
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("My Organization");
        org.setOwnerId(userId);
        orgRepository.save(org);

        Membership membership = new Membership();
        membership.setId(UUID.randomUUID());
        membership.setUserId(userId);
        membership.setOrganization(org);
        membership.setRole(Membership.Role.ADMIN);
        membershipRepository.save(membership);

        log.info("Default org created for user: {} ({})", userId, email);
    }
}
