package com.saas.shared.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * Enabled with {@code saas.tenancy.enabled=true} in the services that own tenant-scoped schemas.
 * Services on the global database (identities, catalogs) leave it off.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, JpaTransactionManager.class})
@ConditionalOnProperty(prefix = "saas.tenancy", name = "enabled", havingValue = "true")
public class TenantPersistenceAutoConfiguration {

    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
        return properties -> properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                new TenantContextIdentifierResolver());
    }

    /** Replaces the Spring Boot default, which backs off on @ConditionalOnMissingBean(TransactionManager). */
    @Bean
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }
}
