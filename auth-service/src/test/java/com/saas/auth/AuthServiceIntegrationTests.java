package com.saas.auth;

import com.saas.auth.entity.User;
import com.saas.auth.repository.UserRepository;
import com.saas.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthServiceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void testUserRegistration() throws Exception {
        String payload = """
            {
                "email": "test@example.com",
                "password": "SecurePassword123"
            }
        """;

        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.expiresIn").value(greaterThan(0)));
    }

    @Test
    void testUserLogin() throws Exception {
        // First register a user
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("login@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("TestPassword123"));
        user.setActive(true);
        userRepository.save(user);

        // Then try to login
        String payload = """
            {
                "email": "login@example.com",
                "password": "TestPassword123"
            }
        """;

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void testLoginWithInvalidCredentials() throws Exception {
        String payload = """
            {
                "email": "nonexistent@example.com",
                "password": "WrongPassword"
            }
        """;

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testPasswordReset() throws Exception {
        // Create a user first
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("reset@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("OldPassword123"));
        user.setActive(true);
        userRepository.save(user);

        // Request password reset
        String forgotPayload = """
            {
                "email": "reset@example.com"
            }
        """;

        mockMvc.perform(post("/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(forgotPayload))
                .andExpect(status().isOk());
    }

    @Test
    void testTokenRefresh() throws Exception {
        // First login to get tokens
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("refresh@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("TestPassword123"));
        user.setActive(true);
        userRepository.save(user);

        // Login
        String loginPayload = """
            {
                "email": "refresh@example.com",
                "password": "TestPassword123"
            }
        """;

        // Extract refresh token from login response...
        // Then use it to refresh
        String refreshPayload = """
            {
                "refreshToken": "your-refresh-token-here"
            }
        """;

        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }
}
