package com.saas.shared.tenancy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "tenancy_probe")
public class TenancyProbe extends TenantScopedEntity {

    @Id
    private UUID id;

    private String label;

    protected TenancyProbe() {
    }

    TenancyProbe(UUID id, String label) {
        this.id = id;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
