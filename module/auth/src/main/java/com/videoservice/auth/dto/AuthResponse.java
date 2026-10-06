package com.videoservice.auth.dto;

public record AuthResponse(String accessToken, String tokenType, int expiresIn, UserResponse user) {
    public AuthResponse(String accessToken, UserResponse user) {
        this(accessToken, "Bearer", 3600, user);
    }
}
