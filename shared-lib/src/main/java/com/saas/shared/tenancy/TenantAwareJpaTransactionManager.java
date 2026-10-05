package com.saas.shared.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Sets the tenant for the RLS policies at the start of every transaction. {@code set_config(..., true)}
 * is the parameterised form of {@code SET LOCAL}: it is reverted when the transaction ends, which is
 * what makes this safe behind PgBouncer in transaction pooling mode.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public static final String TENANT_SETTING = "app.tenant_id";

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        UUID tenantId = TenantContext.require();
        super.doBegin(transaction, definition);
        entityManager().createNativeQuery("select set_config(?1, ?2, true)")
                .setParameter(1, TENANT_SETTING)
                .setParameter(2, tenantId.toString())
                .getSingleResult();
    }

    private jakarta.persistence.EntityManager entityManager() {
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException("No EntityManager bound to the transaction");
        }
        return holder.getEntityManager();
    }
}
