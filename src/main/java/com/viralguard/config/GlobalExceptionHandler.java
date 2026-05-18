package com.viralguard.config;

import com.viralguard.dto.AlertReceivedResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AlertReceivedResponseDTO> handleValidationException(
            MethodArgumentNotValidException ex) {

        String errorMessages = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("[GlobalExceptionHandler] 유효성 검증 실패: {}", errorMessages);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AlertReceivedResponseDTO.error("Validation failed: " + errorMessages));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AlertReceivedResponseDTO> handleUnexpectedException(Exception ex) {
        log.error("[GlobalExceptionHandler] 예상치 못한 예외 발생", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AlertReceivedResponseDTO.error(ex.getMessage()));
    }
}
