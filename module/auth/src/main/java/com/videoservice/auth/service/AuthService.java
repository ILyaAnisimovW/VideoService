package com.videoservice.auth.service;

import com.videoservice.auth.dto.AuthResponse;
import com.videoservice.auth.dto.LoginRequest;
import com.videoservice.auth.dto.RegisterRequest;
import com.videoservice.auth.dto.UserResponse;
import com.videoservice.auth.entity.User;
import com.videoservice.auth.entity.UserStatus;
import com.videoservice.auth.repository.UserRepository;
import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = canonicalEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Пользователь с таким email уже существует");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Пользователь с таким email уже существует");
        }
        return new AuthResponse(jwtTokenService.issue(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(canonicalEmail(request.email()))
                .orElseThrow(this::badCredentials);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw badCredentials();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Учётная запись заблокирована");
        }
        return new AuthResponse(jwtTokenService.issue(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Пользователь не найден"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Учётная запись заблокирована");
        }
        return UserResponse.from(user);
    }

    private ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Неверный email или пароль");
    }

    private static String canonicalEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
