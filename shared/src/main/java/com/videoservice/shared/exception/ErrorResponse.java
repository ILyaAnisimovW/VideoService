package com.videoservice.shared.exception;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(String code, String message, String traceId, Instant timestamp,
                            String path, List<Detail> details) {
    public record Detail(String field, String message) {
    }

    public static ErrorResponse of(String code, String message, HttpServletRequest request) {
        return of(code, message, request, List.of());
    }

    public static ErrorResponse of(String code, String message, HttpServletRequest request, List<Detail> details) {
        return new ErrorResponse(code, message, String.valueOf(request.getAttribute("traceId")),
                Instant.now(), request.getRequestURI(), details);
    }
}
