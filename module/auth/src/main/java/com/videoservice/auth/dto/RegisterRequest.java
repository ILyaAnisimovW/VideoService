package com.videoservice.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegisterRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String name;

    @NotBlank
    private String familia;

    @NotBlank
    @Size(min = 8, message = "Пароль должен быть не короче 8 символов")
    private String password;

    @NotNull
    @Past(message = "Дата рождения должна быть в прошлом")
    private LocalDate dateOfBirth;
}