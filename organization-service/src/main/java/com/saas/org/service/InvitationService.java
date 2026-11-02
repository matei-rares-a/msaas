package com.saas.org.service;

import com.saas.org.command.SendInvitationEmailCommand;
import com.saas.org.dto.AcceptInvitationResponse;
import com.saas.org.dto.InvitationResponse;
import com.saas.org.entity.Invitation;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Tenant;
import com.saas.org.event.InvitationAcceptedPayload;
import com.saas.org.repository.InvitationRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Invitation lifecycle (REQ-ROLE-02, REQ-ROLE-05, REQ-MGR-01, REQ-INV-01, REQ-INV-02, REQ-BILL-03,
 * REQ-EVT-02, REQ-EVT-09). Same {@link TransactionTemplate} + {@link TenantContext#callWith} pattern
 * as {@link OrganizationService} (tenant-scoped writes), never {@code @Transactional} - the AOP proxy
 * would start the transaction before {@code TenantContext} is set. {@code defaultExpiryDays} is
 * injected directly as {@code @Value} on this constructor, like every other field here: a plain
 * {@code @Service}, no separate {@code @Configuration}/{@code @Bean} (design-review.json finding 2).
 */
@Service
public class InvitationService {

    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final InvitationRepository invitationRepository;
    private final InvitationTokenLookup invitationTokenLookup;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final int defaultExpiryDays;

    public InvitationService(TenantRepository tenantRepository, MembershipRepository membershipRepository,
            InvitationRepository invitationRepository, InvitationTokenLookup invitationTokenLookup,
            OutboxWriter outboxWriter, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${saas.invitation.default-expiry-days}") int defaultExpiryDays) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.invitationTokenLookup = invitationTokenLookup;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.defaultExpiryDays = defaultExpiryDays;
    }

    /** ADMIN invites any role; MANAGER only USER; USER never (REQ-ROLE-02, REQ-ROLE-05, REQ-MGR-01).
     * Blocked once the current plan's maxUsers (local projection, REQ-BILL-03) would be exceeded. */
    public InvitationResponse createInvitation(UUID tenantId, UUID callerUserId, String email, Membership.Role role) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly()) {
                throw new SecurityException("read-only membership cannot invite");
            }
            requireCanInvite(caller.getRole(), role);

            // findByIdForUpdate (SELECT ... FOR UPDATE) serializes concurrent createInvitation calls
            // for the same tenant, closing the count-then-insert race (design-review.json finding 3).
            Tenant tenant = tenantRepository.findByIdForUpdate(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("organization not found"));
            requireUnderMaxUsers(tenant);

            Invitation invitation = new Invitation();
            invitation.setId(UUID.randomUUID());
            invitation.setEmail(email);
            invitation.setRole(role);
            invitation.setToken(UUID.randomUUID().toString());
            invitation.setStatus(Invitation.Status.PENDING);
            invitation.setExpiresAt(clock.instant().plus(Duration.ofDays(
                    tenant.getInvitationExpiryDays() != null ? tenant.getInvitationExpiryDays() : defaultExpiryDays)));
            invitationRepository.save(invitation);

            outboxWriter.writeEvent(EventEnvelope.forTenant("SEND_INVITATION_EMAIL", 1, tenantId,
                    new SendInvitationEmailCommand(invitation.getId(), tenantId, email, role.name(), invitation.getToken())),
                    OutboxDestination.RABBITMQ, "org.commands");

            return InvitationResponse.of(invitation, clock.instant());
        }));
    }

    /** ADMIN with write access only (REQ-INV-02); a PENDING-but-expired invitation is revocable too -
     * it is unusable at accept either way, so no separate error code is needed for that distinction. */
    public void revokeInvitation(UUID tenantId, UUID callerUserId, UUID invitationId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can revoke an invitation");
            }
            Invitation invitation = invitationRepository.findById(invitationId)
                    .orElseThrow(() -> new IllegalArgumentException("invitation not found"));
            if (invitation.getStatus() != Invitation.Status.PENDING) {
                throw new IllegalStateException("invitation is no longer pending");
            }
            invitation.setStatus(Invitation.Status.REVOKED);
            invitationRepository.save(invitation);
            return null;
        }));
    }

    /** ADMIN + MANAGER only. The stored row stays PENDING past expiry (nothing transitions it
     * automatically); the displayed status is derived at read time ({@link InvitationResponse#of}). */
    public List<InvitationResponse> listPendingInvitations(UUID tenantId, UUID callerUserId) {
        Instant now = clock.instant();
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.getRole() == Membership.Role.USER) {
                throw new SecurityException("USER cannot view invitations");
            }
            return invitationRepository.findAllByStatusOrderByCreatedAtDesc(Invitation.Status.PENDING).stream()
                    .map(invitation -> InvitationResponse.of(invitation, now)).toList();
        }));
    }

    /** The tenant an invitation token belongs to, so the caller can check it is writable before accepting. */
    public UUID tenantIdForToken(String token) {
        return invitationTokenLookup.findTenantIdByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("invalid invitation token"));
    }

    /** No {@code TenantContext} exists yet for the accepting user - {@link InvitationTokenLookup}
     * (cross-tenant, RLS-bypassing) resolves the tenant from the token first, then every subsequent
     * read/write runs inside {@code TenantContext}, RLS-protected. */
    public AcceptInvitationResponse acceptInvitation(String token, UUID acceptingUserId) {
        UUID tenantId = tenantIdForToken(token);
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Invitation invitation = invitationRepository.findByToken(token)
                    .orElseThrow(() -> new IllegalArgumentException("invalid invitation token"));
            if (invitation.getStatus() != Invitation.Status.PENDING) {
                throw new IllegalStateException("invitation is no longer pending");
            }
            if (invitation.getExpiresAt().isBefore(clock.instant())) {
                throw new IllegalStateException("invitation has expired");
            }
            // memberships has UNIQUE(tenant_id, user_id) (baseline V1): without this check, a second
            // accepted invitation for the same user+tenant would hit that constraint and surface as
            // an unmapped DataIntegrityViolationException -> generic 500, instead of a clean 409.
            if (membershipRepository.findByUserId(acceptingUserId).isPresent()) {
                throw new IllegalStateException("user is already a member of this organization");
            }

            Membership membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(acceptingUserId);
            membership.setRole(invitation.getRole()); // role from the invitation, not chosen by the acceptor
            membership.setReadOnly(false);
            membershipRepository.save(membership);

            invitation.setStatus(Invitation.Status.ACCEPTED);
            invitation.setAcceptedBy(acceptingUserId);
            invitationRepository.save(invitation);

            outboxWriter.writeEvent(EventEnvelope.forTenant("INVITATION_ACCEPTED", 1, tenantId,
                    new InvitationAcceptedPayload(invitation.getId(), tenantId, membership.getId(), acceptingUserId,
                            invitation.getRole().name())),
                    OutboxDestination.KAFKA, "org.events");

            return new AcceptInvitationResponse(tenantId, invitation.getRole().name());
        }));
    }

    /** ADMIN with write access only (same guard as {@link #revokeInvitation}). */
    public void updateInvitationExpiry(UUID tenantId, UUID callerUserId, int days) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can configure invitation expiry");
            }
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("organization not found"));
            tenant.setInvitationExpiryDays(days);
            tenantRepository.save(tenant);
            return null;
        }));
    }

    private void requireCanInvite(Membership.Role callerRole, Membership.Role targetRole) {
        switch (callerRole) {
            case ADMIN -> { /* any role */ }
            case MANAGER -> {
                if (targetRole != Membership.Role.USER) {
                    throw new SecurityException("MANAGER can only invite with the USER role");
                }
            }
            case USER -> throw new SecurityException("USER cannot invite members");
        }
    }

    private void requireUnderMaxUsers(Tenant tenant) {
        Integer maxUsers = tenant.getMaxUsers();
        if (maxUsers == null) {
            throw new IllegalStateException("subscription limit not yet known for this tenant; invitations are blocked until it is");
        }
        long occupied = membershipRepository.count()
                + invitationRepository.countByStatusAndExpiresAtAfter(Invitation.Status.PENDING, clock.instant());
        if (occupied >= maxUsers) {
            throw new IllegalStateException("maxUsers limit reached for the current plan");
        }
    }
}
