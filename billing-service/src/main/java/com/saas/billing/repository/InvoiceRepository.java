package com.saas.billing.repository;

import com.saas.billing.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findAllByOrganizationId(UUID orgId);

    java.util.Optional<Invoice> findByIdAndOrganizationId(UUID id, UUID orgId);
}
