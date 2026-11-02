package com.saas.auth.service;

import com.saas.auth.client.OrgServiceClient;
import com.saas.auth.entity.User;
import com.saas.auth.exception.AccountInUseException;
import com.saas.auth.repository.LoginSessionRepository;
import com.saas.auth.repository.PasswordResetTokenRepository;
import com.saas.auth.repository.RefreshTokenRepository;
import com.saas.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Erasure of the global identity (REQ-DATA-01). The identity (email, password) can serve several
 * tenants, so offboarding one tenant never touches it; it is erased only when the user no longer
 * belongs to any tenant, or when the user asks for it - and a user who still belongs to a tenant must
 * leave it first. "Erased" means anonymized, not deleted: the row stays so ids held elsewhere (billing,
 * audit) keep pointing at something, but it carries no email, cannot log in and has no sessions.
 */
@Service
public class IdentityErasureService {

    private static final String ANONYMIZED_EMAIL_DOMAIN = "@anonymized.invalid";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final LoginSessionRepository loginSessionRepository;
    private final OrgServiceClient orgServiceClient;
    private final PasswordEncoder passwordEncoder;

    public IdentityErasureService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository resetTokenRepository, LoginSessionRepository loginSessionRepository,
            OrgServiceClient orgServiceClient, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.loginSessionRepository = loginSessionRepository;
        this.orgServiceClient = orgServiceClient;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The user was removed from a tenant: its refresh tokens stop working at once (a token for a
     * tenant the user no longer belongs to must not be renewable), and the identity is erased if that
     * was the user's last tenant. The membership check is a live call to org, never a cache, and a
     * failure propagates so the event is retried instead of erasing on a guess.
     */
    @Transactional
    public void onMemberOffboarded(UUID userId, UUID tenantId) {
        refreshTokenRepository.deleteAllByUserIdAndTenantId(userId, tenantId);
        if (orgServiceClient.findMemberships(userId).isEmpty()) {
            userRepository.findById(userId).ifPresent(this::anonymize);
        }
    }

    /** At the user's own request; only possible once the user belongs to no tenant. */
    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("account not found"));
        if (!orgServiceClient.findMemberships(userId).isEmpty()) {
            throw new AccountInUseException("leave every organization before deleting the account");
        }
        anonymize(user);
    }

    private void anonymize(User user) {
        user.setEmail("deleted-" + user.getId() + ANONYMIZED_EMAIL_DOMAIN);
        // a hash of a random secret nobody knows: the account can never be logged into again
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setActive(false);
        userRepository.save(user);
        refreshTokenRepository.deleteAllByUser(user);
        resetTokenRepository.deleteAllByUser(user);
        loginSessionRepository.deleteAllByUserId(user.getId());
    }
}
