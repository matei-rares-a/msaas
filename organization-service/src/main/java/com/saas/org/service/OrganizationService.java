package com.saas.org.service;

import com.saas.org.dto.OrganizationResponse;
import com.saas.org.entity.Membership;
import com.saas.org.entity.Tenant;
import com.saas.org.repository.MembershipRepository;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Organization = tenant creation/management (REQ-AUTH-03, REQ-ROLE-06). Uses a programmatic
 * {@link TransactionTemplate}, not {@code @Transactional}: the AOP proxy would start the transaction
 * (and {@link com.saas.shared.tenancy.TenantAwareJpaTransactionManager} would read the tenant context)
 * before this method body gets a chance to set it via {@link TenantContext#callWith}.
 */
@Service
public class OrganizationService {

    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final TransactionTemplate transactionTemplate;

    public OrganizationService(TenantRepository tenantRepository, MembershipRepository membershipRepository,
            PlatformTransactionManager transactionManager) {
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
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
}
