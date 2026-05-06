package com.viralguard.config;

import com.viralguard.dto.ScaleOutResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 전역 예외 처리기.
 * DTO @Valid 검증 실패 및 기타 런타임 예외를 일관된 JSON 응답으로 변환.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * @Valid 검증 실패 처리 (400 Bad Request).
     * FastAPI가 잘못된 페이로드를 보낸 경우.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ScaleOutResponseDTO> handleValidationException(
            MethodArgumentNotValidException ex) {

        String errorMessages = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("[GlobalExceptionHandler] 유효성 검증 실패: {}", errorMessages);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ScaleOutResponseDTO.error("Validation failed: " + errorMessages));
    }

    /**
     * 기타 예상치 못한 예외 처리 (500 Internal Server Error).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ScaleOutResponseDTO> handleUnexpectedException(Exception ex) {
        log.error("[GlobalExceptionHandler] 예상치 못한 예외 발생", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ScaleOutResponseDTO.error(ex.getMessage()));
    }
}
