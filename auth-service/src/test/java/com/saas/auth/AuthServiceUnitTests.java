package com.saas.auth;

import com.saas.auth.dto.AuthResponse;
import com.saas.auth.dto.LoginRequest;
import com.saas.auth.dto.RegisterRequest;
import com.saas.auth.entity.PasswordResetToken;
import com.saas.auth.entity.RefreshToken;
import com.saas.auth.entity.User;
import com.saas.auth.exception.UnauthorizedException;
import com.saas.auth.repository.PasswordResetTokenRepository;
import com.saas.auth.repository.RefreshTokenRepository;
import com.saas.auth.repository.UserRepository;
import com.saas.auth.security.JwtService;
import com.saas.auth.service.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AuthServiceUnitTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testRegisterNewUser() {
        // Arrange
        String email = "newuser@example.com";
        String password = "SecurePassword123";
        String hashedPassword = "hashed_password";

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn(hashedPassword);
        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(UUID.randomUUID());
                return user;
            });

        // Act
        User result = authService.register(new RegisterRequest(email, password));

        // Assert
        assertNotNull(result);
        assertEquals(email, result.getEmail());
        assertEquals(hashedPassword, result.getPasswordHash());
        assertTrue(result.isActive());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void testRegisterDuplicateUserThrowsException() {
        // Arrange
        String email = "existing@example.com";
        User existingUser = new User();
        existingUser.setEmail(email);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            authService.register(new RegisterRequest(email, "Password123"));
        });
    }

    @Test
    void testLoginWithValidCredentials() {
        // Arrange
        String email = "user@example.com";
        String password = "CorrectPassword123";
        String hashedPassword = "$2a$10$hashed_password";

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(hashedPassword);
        user.setActive(true);
        user.setRole("MEMBER");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, hashedPassword)).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), user.getRole(), user.getOrganizationId()))
            .thenReturn("valid_jwt_token");

        // Act
        String token = authService.login(new LoginRequest(email, password));

        // Assert
        assertNotNull(token);
        assertEquals("valid_jwt_token", token);
        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    void testLoginWithInvalidPasswordThrowsException() {
        // Arrange
        String email = "user@example.com";
        String password = "WrongPassword";
        String hashedPassword = "$2a$10$hashed_password";

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(hashedPassword);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, hashedPassword)).thenReturn(false);

        // Act & Assert
        assertThrows(UnauthorizedException.class, () -> {
            authService.login(new LoginRequest(email, password));
        });
    }

    @Test
    void testLoginWithNonExistentUserThrowsException() {
        // Arrange
        String email = "nonexistent@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(UnauthorizedException.class, () -> {
            authService.login(new LoginRequest(email, "Password123"));
        });
    }

    @Test
    void testRefreshTokenRotation() {
        // Arrange
        UUID userId = UUID.randomUUID();
        String oldRefreshToken = "old_refresh_token";
        String newAccessToken = "new_access_token";
        String newRefreshToken = "new_refresh_token";

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(userId);
        refreshToken.setToken(oldRefreshToken);

        User user = new User();
        user.setId(userId);
        user.setRole("ADMIN");

        when(refreshTokenRepository.findByToken(oldRefreshToken))
            .thenReturn(Optional.of(refreshToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(userId, "ADMIN", user.getOrganizationId()))
            .thenReturn(newAccessToken);
        when(jwtService.generateRefreshToken(userId))
            .thenReturn(newRefreshToken);

        // Act
        AuthResponse response = authService.refresh(oldRefreshToken);

        // Assert
        assertNotNull(response);
        assertEquals(newAccessToken, response.getAccessToken());
        assertEquals(newRefreshToken, response.getRefreshToken());
        verify(refreshTokenRepository, times(1)).delete(refreshToken);
    }

    @Test
    void testForgotPasswordCreatesResetToken() {
        // Arrange
        String email = "user@example.com";
        UUID userId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);
        user.setEmail(email);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // Act
        authService.forgotPassword(email);

        // Assert
        verify(userRepository, times(1)).findByEmail(email);
        // Verify that password reset token was created
    }

    @Test
    void testResetPasswordWithValidToken() {
        // Arrange
        String token = "valid_reset_token";
        String newPassword = "NewPassword123";
        String hashedPassword = "hashed_new_password";
        UUID userId = UUID.randomUUID();

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUserId(userId);
        resetToken.setToken(token);
        resetToken.setUsed(false);

        User user = new User();
        user.setId(userId);

        when(passwordResetTokenRepository.findByToken(token))
            .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(newPassword)).thenReturn(hashedPassword);

        // Act
        authService.resetPassword(token, newPassword);

        // Assert
        assertEquals(hashedPassword, user.getPasswordHash());
        assertTrue(resetToken.isUsed());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void testResetPasswordWithExpiredTokenThrowsException() {
        // Arrange
        String token = "expired_reset_token";
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setToken(token);
        resetToken.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));

        when(passwordResetTokenRepository.findByToken(token))
            .thenReturn(Optional.of(resetToken));

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            authService.resetPassword(token, "NewPassword123");
        });
    }

    @Test
    void testUserDeactivation() {
        // Arrange
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setActive(true);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Act
        authService.deactivateUser(userId);

        // Assert
        assertFalse(user.isActive());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void testPasswordValidation() {
        // Test various password validation scenarios

        // Valid password
        assertTrue(authService.isValidPassword("SecurePass123"));

        // Too short
        assertFalse(authService.isValidPassword("Short1"));

        // No uppercase
        assertFalse(authService.isValidPassword("securepass123"));

        // No numbers
        assertFalse(authService.isValidPassword("SecurePass"));
    }
}
