package com.saas.auth.repository;

import com.saas.auth.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM LoginSession s WHERE s.userId = :userId")
    void deleteAllByUserId(UUID userId);
}
