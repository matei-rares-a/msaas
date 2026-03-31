package com.saas.org.service;

import com.saas.org.dto.*;
import com.saas.org.entity.Invitation;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Organization;
import com.saas.org.repository.InvitationRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository orgRepository;
    private final MembershipRepository membershipRepository;
    private final InvitationRepository invitationRepository;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request, UUID ownerId) {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName(request.getName());
        org.setOwnerId(ownerId);
        orgRepository.save(org);

        // Add owner as ADMIN member
        Membership membership = new Membership();
        membership.setId(UUID.randomUUID());
        membership.setUserId(ownerId);
        membership.setOrganization(org);
        membership.setRole(Membership.Role.ADMIN);
        membershipRepository.save(membership);

        log.info("Organization created: {} by user: {}", org.getId(), ownerId);
        return toResponse(org);
    }

    public List<OrganizationResponse> getAll(UUID userId) {
        return membershipRepository.findAllByOrganizationId(userId).stream()
                .map(m -> toResponse(m.getOrganization()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void invite(UUID orgId, InviteRequest request, UUID requesterId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        requireAdminOrManager(orgId, requesterId);

        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setEmail(request.getEmail());
        invitation.setOrganization(org);
        invitation.setRole(request.getRole());
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusDays(7));
        invitationRepository.save(invitation);

        // Publish to RabbitMQ for notification service
        rabbitTemplate.convertAndSend("email-exchange", "email.routing", Map.of(
                "eventType", "USER_INVITED",
                "email", request.getEmail(),
                "organizationId", orgId.toString(),
                "organizationName", org.getName(),
                "inviteToken", invitation.getToken()
        ));

        log.info("Invitation sent to {} for org {}", request.getEmail(), orgId);
    }

    public List<MembershipResponse> getMembers(UUID orgId, UUID requesterId) {
        requireMembership(orgId, requesterId);
        return membershipRepository.findAllByOrganizationId(orgId).stream()
                .map(this::toMemberResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public MembershipResponse updateMemberRole(UUID orgId, UUID targetUserId,
                                                UpdateRoleRequest request, UUID requesterId) {
        requireAdmin(orgId, requesterId);

        Membership membership = membershipRepository.findByUserIdAndOrganizationId(targetUserId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));

        membership.setRole(request.getRole());
        return toMemberResponse(membershipRepository.save(membership));
    }

    // --- helpers ---

    private void requireMembership(UUID orgId, UUID userId) {
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
            throw new SecurityException("Access denied: not a member of this organization");
        }
    }

    private void requireAdminOrManager(UUID orgId, UUID userId) {
        Membership m = membershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new SecurityException("Not a member"));
        if (m.getRole() == Membership.Role.MEMBER) {
            throw new SecurityException("Insufficient permissions");
        }
    }

    private void requireAdmin(UUID orgId, UUID userId) {
        Membership m = membershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new SecurityException("Not a member"));
        if (m.getRole() != Membership.Role.ADMIN) {
            throw new SecurityException("Only ADMIN can perform this action");
        }
    }

    private OrganizationResponse toResponse(Organization org) {
        return OrganizationResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .ownerId(org.getOwnerId())
                .createdAt(org.getCreatedAt())
                .build();
    }

    private MembershipResponse toMemberResponse(Membership m) {
        return MembershipResponse.builder()
                .id(m.getId())
                .userId(m.getUserId())
                .organizationId(m.getOrganization().getId())
                .role(m.getRole())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
