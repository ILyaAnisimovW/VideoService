package com.videoservice.auth.service;

import com.videoservice.auth.dto.LoginRequest;
import com.videoservice.auth.dto.RegisterRequest;
import com.videoservice.auth.entity.User;
import com.videoservice.auth.entity.UserStatus;
import com.videoservice.auth.repository.UserRepository;
import com.videoservice.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private UserRepository repository;
    private JwtTokenService tokens;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        tokens = mock(JwtTokenService.class);
        service = new AuthService(repository, new BCryptPasswordEncoder(), tokens);
    }

    @Test
    void registerCanonicalizesEmailAndNeverLetsClientChooseRole() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(Instant.now());
            return user;
        });
        when(tokens.issue(any(User.class))).thenReturn("signed-token");

        var response = service.register(new RegisterRequest("  AUTHOR@Example.com  ", "long-password-123", " Автор "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals("author@example.com", saved.getValue().getEmail());
        assertEquals("Автор", saved.getValue().getDisplayName());
        assertEquals("USER", saved.getValue().getRole().name());
        assertNotEquals("long-password-123", saved.getValue().getPasswordHash());
        assertEquals("signed-token", response.accessToken());
        assertEquals(3600, response.expiresIn());
    }

    @Test
    void loginRejectsWrongPasswordWithoutIssuingToken() {
        User user = user("author@example.com", "correct-password-123");
        when(repository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        ApiException error = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("AUTHOR@example.com", "incorrect")));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        verifyNoInteractions(tokens);
    }

    @Test
    void blockedUserCannotLoginOrReadProfile() {
        User user = user("author@example.com", "correct-password-123");
        user.setStatus(UserStatus.BLOCKED);
        when(repository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("author@example.com", "correct-password-123"))).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.currentUser(user.getId())).getStatus());
        verifyNoInteractions(tokens);
    }

    private User user(String email, String password) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        user.setCreatedAt(Instant.now());
        return user;
    }
}
