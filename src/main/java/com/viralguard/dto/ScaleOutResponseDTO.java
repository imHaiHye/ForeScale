package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Scale-out 알람 수신 후 FastAPI에 반환하는 응답 DTO.
 *
 * HTTP 200 (TRIGGERED):
 * {
 *   "status": "TRIGGERED",
 *   "message": "Scale-out script triggered for CPU alert",
 *   "timestamp": "2024-01-15T14:23:00.123Z"
 * }
 *
 * HTTP 409 (COOLDOWN_ACTIVE):
 * {
 *   "status": "COOLDOWN_ACTIVE",
 *   "message": "Scale-out already in progress. Unlock at: 2024-01-15T14:28:00Z",
 *   "timestamp": "2024-01-15T14:23:30.456Z"
 * }
 */
@Getter
@Builder
public class ScaleOutResponseDTO {

    /**
     * 처리 상태.
     * - TRIGGERED      : Scale-out 스크립트 실행 시작됨
     * - COOLDOWN_ACTIVE : Cooldown 활성화 중 - 이번 알람은 무시됨
     * - ERROR          : 내부 오류 발생
     */
    private String status;

    /** 상세 메시지 (로그 및 운영자 확인용) */
    private String message;

    /** 응답 생성 시각 (ISO 8601 UTC) */
    private String timestamp;

    // ── 정적 팩토리 메서드 ─────────────────────────────────────

    public static ScaleOutResponseDTO triggered(String modelType) {
        return ScaleOutResponseDTO.builder()
                .status("TRIGGERED")
                .message(String.format("Scale-out script triggered for %s alert. EC2 provisioning started.", modelType))
                .timestamp(Instant.now().toString())
                .build();
    }

    public static ScaleOutResponseDTO cooldownActive(String unlockAt) {
        return ScaleOutResponseDTO.builder()
                .status("COOLDOWN_ACTIVE")
                .message(String.format("Scale-out already in progress. Ignoring alert. Cooldown unlock at: %s", unlockAt))
                .timestamp(Instant.now().toString())
                .build();
    }

    public static ScaleOutResponseDTO error(String reason) {
        return ScaleOutResponseDTO.builder()
                .status("ERROR")
                .message("Scale-out script execution failed: " + reason)
                .timestamp(Instant.now().toString())
                .build();
    }
}
