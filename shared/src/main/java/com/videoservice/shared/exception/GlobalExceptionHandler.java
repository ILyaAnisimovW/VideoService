package com.videoservice.shared.exception;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.of(ex.getCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.Detail(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.unprocessableEntity()
                .body(ErrorResponse.of("VALIDATION_FAILED", "Некорректные поля запроса", request, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            return ResponseEntity.unprocessableEntity().body(ErrorResponse.of("VALIDATION_FAILED",
                    "Неизвестное поле запроса", request,
                    List.of(new ErrorResponse.Detail(unknown.getPropertyName(), "Поле не поддерживается"))));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST", "Некорректный JSON", request));
    }
}
