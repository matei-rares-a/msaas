package com.saas.auth.repository;

import com.saas.auth.entity.PasswordResetRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, UUID> {
    long countByEmailAndRequestedAtAfter(String email, Instant since);

    long countByIpAddressAndRequestedAtAfter(String ipAddress, Instant since);
}
