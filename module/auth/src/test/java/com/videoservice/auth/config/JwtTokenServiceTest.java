package com.videoservice.auth.config;

import com.videoservice.auth.entity.User;
import com.videoservice.auth.service.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {
    private static final String SECRET = "test-signing-key-at-least-thirty-two-bytes";

    @Test
    void issuedTokenHasUserAudienceAndOneHourLifetime() {
        SecurityConfig config = new SecurityConfig();
        User user = new User();
        user.setId(UUID.randomUUID());

        String token = new JwtTokenService(config.jwtEncoder(SECRET)).issue(user);
        var decoded = config.jwtDecoder(SECRET).decode(token);

        assertEquals(user.getId().toString(), decoded.getSubject());
        assertEquals("USER", decoded.getClaimAsString("role"));
        assertEquals("video-api", decoded.getAudience().get(0));
        assertEquals(Duration.ofHours(1), Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()));
        assertTrue(decoded.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void invalidSignatureIsRejected() {
        SecurityConfig config = new SecurityConfig();
        User user = new User();
        user.setId(UUID.randomUUID());
        String token = new JwtTokenService(config.jwtEncoder(SECRET)).issue(user);

        assertThrows(JwtException.class,
                () -> config.jwtDecoder("another-test-signing-key-with-32-bytes").decode(token));
    }
}
