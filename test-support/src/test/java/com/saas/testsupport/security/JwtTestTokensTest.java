package com.saas.testsupport.security;

import com.saas.testsupport.fixtures.TenantFixtures;
import com.saas.testsupport.fixtures.TenantFixtures.Role;
import com.saas.testsupport.fixtures.TenantFixtures.TestMember;
import com.saas.testsupport.time.MutableClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTestTokensTest {

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    private final JwtTestTokens tokens = new JwtTestTokens(clock);
    private final TestMember manager = TenantFixtures.member(TenantFixtures.tenant("Acme"), Role.MANAGER);

    @Test
    void tokenCarriesTenantScopedClaimsOnly() {
        Claims claims = parse(tokens.accessToken(manager));

        assertThat(claims.getSubject()).isEqualTo(manager.user().id().toString());
        assertThat(claims.get(JwtTestTokens.CLAIM_TENANT_ID)).isEqualTo(manager.tenant().id().toString());
        assertThat(claims.get(JwtTestTokens.CLAIM_ROLE)).isEqualTo("MANAGER");
        assertThat(claims.get(JwtTestTokens.CLAIM_READ_ONLY)).isEqualTo(false);
        assertThat(claims.get(JwtTestTokens.CLAIM_TENANT_NAME)).isEqualTo("Acme");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(clock.instant().plus(JwtTestTokens.ACCESS_TOKEN_TTL));
    }

    @Test
    void readOnlyFlagIsIssued() {
        assertThat(parse(tokens.accessToken(manager.asReadOnly())).get(JwtTestTokens.CLAIM_READ_ONLY)).isEqualTo(true);
    }

    @Test
    void tokenExpiresAccordingToTheInjectedClock() {
        String token = tokens.accessToken(manager);
        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(JwtTestTokens.TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .setClock(() -> Date.from(clock.instant()))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
