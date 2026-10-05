package com.saas.auth.service;

import com.saas.auth.dto.*;
import com.saas.auth.entity.PasswordResetToken;
import com.saas.auth.entity.RefreshToken;
import com.saas.auth.entity.User;
import com.saas.auth.event.UserRegisteredPayload;
import com.saas.auth.repository.PasswordResetTokenRepository;
import com.saas.auth.repository.RefreshTokenRepository;
import com.saas.auth.repository.UserRepository;
import com.saas.auth.security.JwtService;
import com.saas.auth.security.PasswordPolicy;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxDestination;
import com.saas.shared.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final OutboxWriter outboxWriter;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    /** Global identity, no tenant (REQ-EVT-08); password policy applies at registration (REQ-PWD-06). */
    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }
        passwordPolicy.validate(request.getPassword());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setActive(true);
        userRepository.save(user);

        // Identity event, no tenantId, no PII (REQ-EVT-02, REQ-EVT-06, REQ-EVT-08)
        EventEnvelope<UserRegisteredPayload> envelope = EventEnvelope.forIdentity(
                "USER_REGISTERED", 1, new UserRegisteredPayload(user.getId()));
        outboxWriter.writeEvent(envelope, OutboxDestination.KAFKA, "auth.events");

        log.info("User registered: {}", user.getId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!user.isActive()) {
            throw new IllegalArgumentException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        return buildAuthResponse(user, null);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken existing = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(existing);
            throw new IllegalArgumentException("Refresh token expired");
        }

        // Rotate: delete old, issue new
        refreshTokenRepository.delete(existing);
        return buildAuthResponse(existing.getUser(), null);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setId(UUID.randomUUID());
            resetToken.setUser(user);
            resetToken.setToken(UUID.randomUUID().toString());
            resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));
            resetTokenRepository.save(resetToken);

            // Publish PASSWORD_RESET event to RabbitMQ via Kafka bridge or direct
            kafkaTemplate.send("notification-events", Map.of(
                    "eventType", "PASSWORD_RESET",
                    "email", user.getEmail(),
                    "token", resetToken.getToken(),
                    "userId", user.getId().toString()
            ));

            log.info("Password reset token generated for: {}", user.getEmail());
        });
        // Always return 200 to avoid user enumeration
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = resetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired token"));

        if (resetToken.isUsed() || resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token has expired or already used");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        // Invalidate all refresh tokens
        refreshTokenRepository.deleteAllByUser(user);

        log.info("Password reset for user: {}", user.getEmail());
    }

    private AuthResponse buildAuthResponse(User user, String orgId) {
        String accessToken = jwtService.generateAccessToken(
                user.getId().toString(),
                "USER",
                orgId != null ? orgId : ""
        );

        String rawRefreshToken = jwtService.generateRefreshToken();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setId(UUID.randomUUID());
        refreshToken.setUser(user);
        refreshToken.setToken(rawRefreshToken);
        refreshToken.setExpiresAt(
                LocalDateTime.now().plusSeconds(refreshTokenExpiryMs / 1000));
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(refreshTokenExpiryMs / 1000)
                .build();
    }
}
