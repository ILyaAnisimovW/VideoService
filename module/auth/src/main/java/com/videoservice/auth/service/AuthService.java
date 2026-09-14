package com.videoservice.auth.service;

import com.videoservice.auth.dto.ConfirmRequest;
import com.videoservice.auth.dto.RegisterRequest;
import com.videoservice.auth.entity.User;
import com.videoservice.auth.entity.UserStatus;
import com.videoservice.auth.repository.UserRepository;
import com.videoservice.auth.util.ConfirmationCodeGenerator;
import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int CODE_TTL_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "Пользователь с таким email уже существует");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setName(request.getName());
        user.setFamilia(request.getFamilia());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDateOfBirth(request.getDateOfBirth());
        user.setStatus(UserStatus.NOT_CONFIRMED);

        String code = ConfirmationCodeGenerator.generate();
        user.setConfirmationCode(code);
        user.setConfirmationCodeExpiresAt(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));

        userRepository.save(user);
        emailService.sendConfirmationCode(user.getEmail(), code);
    }

    @Transactional
    public void confirm(ConfirmRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Пользователь уже подтверждён");
        }

        if (user.getConfirmationCode() == null || !user.getConfirmationCode().equals(request.getCod())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неверный код подтверждения");
        }

        if (user.getConfirmationCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Код подтверждения истёк");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setConfirmationCode(null);
        user.setConfirmationCodeExpiresAt(null);
        userRepository.save(user);
    }
}