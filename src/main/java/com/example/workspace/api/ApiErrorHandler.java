package com.example.workspace.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiError(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ReservationConflictException.class)
    ResponseEntity<Map<String, Object>> reservationConflict(ReservationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", exception.getCode(),
                "message", exception.getMessage(),
                "conflicts", exception.getConflicts()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request fields"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON or invalid date/type"));
    }
}
