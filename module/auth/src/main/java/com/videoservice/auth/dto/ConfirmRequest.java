package com.videoservice.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String cod;
}