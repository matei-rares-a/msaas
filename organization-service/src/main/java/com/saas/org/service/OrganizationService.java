package com.saas.org.service;

import com.saas.org.dto.AdminDemotionRequestResponse;
import com.saas.org.dto.MemberRoleResponse;
import com.saas.org.dto.OrganizationResponse;
import com.saas.org.dto.RoleChangeOutcome;
import com.saas.org.entity.AdminDemotionRequest;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Tenant;
import com.saas.org.event.AdminDemotionEscalatedPayload;
import com.saas.org.repository.AdminDemotionRequestRepository;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Organization = tenant creation/management (REQ-AUTH-03, REQ-ROLE-06, REQ-ROLE-01, REQ-ROLE-05,
 * REQ-MGR-03, REQ-MGR-05, REQ-ROLE-03, REQ-ROLE-04). Uses a programmatic {@link TransactionTemplate},
 * not {@code @Transactional}: the AOP proxy would start the transaction (and
 * {@link com.saas.shared.tenancy.TenantAwareJpaTransactionManager} would read the tenant context)
 * before this method body gets a chance to set it via {@link TenantContext#callWith}.
 */
@Service
public class OrganizationService {

    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final AdminDemotionRequestRepository demotionRequestRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrganizationService(TenantRepository tenantRepository, MembershipRepository membershipRepository,
            AdminDemotionRequestRepository demotionRequestRepository, OutboxWriter outboxWriter, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.demotionRequestRepository = demotionRequestRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** The creator becomes ADMIN, with write access, of the brand-new tenant. */
    public OrganizationResponse createOrganization(String name, UUID creatorUserId) {
        UUID tenantId = UUID.randomUUID();
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Tenant tenant = new Tenant();
            tenant.setId(tenantId);
            tenant.setName(name);
            tenantRepository.save(tenant);

            Membership membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(creatorUserId);
            membership.setRole(Membership.Role.ADMIN);
            membership.setReadOnly(false);
            membershipRepository.save(membership);

            return new OrganizationResponse(tenant.getId(), tenant.getName());
        }));
    }

    /** RLS-scoped (unlike {@link MembershipLookupService}'s cross-tenant reader); used by auth to get
     * the UI-facing tenant name for a token it already knows which single tenant it's for (REQ-AUTH-05). */
    public String tenantName(UUID tenantId) {
        return TenantContext.callWith(tenantId, () -> tenantRepository.findById(tenantId)
                .map(Tenant::getName)
                .orElseThrow(() -> new IllegalArgumentException("organization not found")));
    }

    /** Minimal write path to exercise the read-only flag (REQ-ROLE-06); the full permission matrix is P4.2. */
    public OrganizationResponse renameOrganization(UUID tenantId, UUID callerUserId, String newName) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership membership = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (membership.isReadOnly()) {
                throw new SecurityException("read-only membership cannot write");
            }
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("organization not found"));
            tenant.setName(newName);
            tenantRepository.save(tenant);
            return new OrganizationResponse(tenant.getId(), tenant.getName());
        }));
    }

    /**
     * Role management is ADMIN-only, regardless of how many ADMIN/MANAGER a tenant has (REQ-MGR-03,
     * REQ-ROLE-01, REQ-ROLE-05); any number of MANAGER, including zero, is allowed (REQ-MGR-05).
     * Demoting the last ADMIN is rejected outright (REQ-ROLE-03); demoting one of exactly two ADMIN
     * requires the target's acceptance instead of applying immediately (REQ-ROLE-04).
     */
    public RoleChangeOutcome updateMemberRole(UUID tenantId, UUID callerUserId, UUID targetUserId, Membership.Role newRole) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            Membership caller = membershipRepository.findByUserId(callerUserId)
                    .orElseThrow(() -> new SecurityException("not a member of this organization"));
            if (caller.isReadOnly() || caller.getRole() != Membership.Role.ADMIN) {
                throw new SecurityException("only an ADMIN with write access can manage roles");
            }
            Membership target = membershipRepository.findByUserId(targetUserId)
                    .orElseThrow(() -> new IllegalArgumentException("member not found"));

            if (target.getRole() == Membership.Role.ADMIN && newRole != Membership.Role.ADMIN) {
                long adminCount = membershipRepository.countByRole(Membership.Role.ADMIN);
                if (adminCount <= 1) {
                    throw new IllegalStateException("cannot demote the last ADMIN of an organization");
                }
                if (adminCount == 2) {
                    AdminDemotionRequest request = new AdminDemotionRequest();
                    request.setId(UUID.randomUUID());
                    request.setTargetUserId(targetUserId);
                    request.setRequestedBy(callerUserId);
                    request.setNewRole(newRole);
                    request.setStatus(AdminDemotionRequest.Status.PENDING);
                    request.setCreatedAt(clock.instant());
                    demotionRequestRepository.save(request);
                    return RoleChangeOutcome.pending(AdminDemotionRequestResponse.of(request));
                }
            }

            target.setRole(newRole);
            membershipRepository.save(target);
            return RoleChangeOutcome.applied(new MemberRoleResponse(target.getUserId(), target.getRole().name()));
        }));
    }

    /** Only the targeted admin can accept their own demotion (REQ-ROLE-04). */
    public MemberRoleResponse acceptAdminDemotion(UUID tenantId, UUID requestId, UUID callerUserId) {
        return TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = requirePendingRequest(requestId, callerUserId);

            Membership target = membershipRepository.findByUserId(request.getTargetUserId())
                    .orElseThrow(() -> new IllegalArgumentException("member not found"));
            target.setRole(request.getNewRole());
            membershipRepository.save(target);

            request.setStatus(AdminDemotionRequest.Status.ACCEPTED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);

            return new MemberRoleResponse(target.getUserId(), target.getRole().name());
        }));
    }

    /** Only the targeted admin can reject their own demotion; nothing changes besides the request status. */
    public void rejectAdminDemotion(UUID tenantId, UUID requestId, UUID callerUserId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = requirePendingRequest(requestId, callerUserId);
            request.setStatus(AdminDemotionRequest.Status.REJECTED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);
            return null;
        }));
    }

    /** Only the targeted admin can respond to this request. */
    private AdminDemotionRequest requirePendingRequest(UUID requestId, UUID callerUserId) {
        AdminDemotionRequest request = demotionRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("demotion request not found"));
        if (!request.getTargetUserId().equals(callerUserId)) {
            throw new SecurityException("only the targeted admin can respond to this request");
        }
        if (request.getStatus() != AdminDemotionRequest.Status.PENDING) {
            throw new IllegalStateException("this request is no longer pending");
        }
        return request;
    }

    /** Called by {@code AdminDemotionEscalationJob} once per overdue request (REQ-ROLE-04). */
    public void escalateAdminDemotion(UUID tenantId, UUID requestId) {
        TenantContext.callWith(tenantId, () -> transactionTemplate.execute(status -> {
            AdminDemotionRequest request = demotionRequestRepository.findById(requestId).orElse(null);
            if (request == null || request.getStatus() != AdminDemotionRequest.Status.PENDING) {
                return null;
            }
            request.setStatus(AdminDemotionRequest.Status.ESCALATED);
            request.setRespondedAt(clock.instant());
            demotionRequestRepository.save(request);

            EventEnvelope<AdminDemotionEscalatedPayload> envelope = EventEnvelope.forTenant(
                    "ADMIN_DEMOTION_ESCALATED", 1, tenantId,
                    new AdminDemotionEscalatedPayload(request.getId(), request.getTargetUserId(), request.getRequestedBy()));
            outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "org.events");
            return null;
        }));
    }
}
