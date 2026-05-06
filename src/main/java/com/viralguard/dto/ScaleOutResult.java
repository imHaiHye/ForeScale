package com.viralguard.dto;

import java.time.Instant;
import java.util.List;

/**
 * boto3 스크립트 실행 결과를 담는 불변 레코드.
 *
 * ScaleOutExecutor → ScaleOutHistoryService로 전달되어 이력 기록에 사용됨.
 */
public record ScaleOutResult(

        /** 트리거 요청 정보 */
        String modelType,
        String sourceInstanceId,

        /** 실행 결과 */
        Status status,
        int exitCode,
        long durationMs,

        /** 스크립트 출력 (마지막 20줄) */
        List<String> stdoutLines,
        List<String> stderrLines,

        /** 실패 사유 (null = 성공) */
        String failureReason,

        /** 실행 시각 */
        Instant startedAt,
        Instant finishedAt

) {
    public enum Status {
        /** 스크립트 exitCode 0으로 정상 완료 */
        SUCCESS,
        /** exitCode 0이 아님 */
        FAILED,
        /** timeoutSeconds 초과로 강제 종료 */
        TIMEOUT,
        /** ProcessBuilder 자체 실행 실패 (파일 없음, 권한 없음 등) */
        LAUNCH_ERROR,
        /** Mock 모드 성공 시뮬레이션 */
        MOCK_SUCCESS
    }

    // ── 정적 팩토리 ────────────────────────────────────────────

    public static ScaleOutResult success(
            String modelType, String instanceId,
            int exitCode, long durationMs,
            List<String> stdout, List<String> stderr,
            Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.SUCCESS, exitCode, durationMs,
                stdout, stderr, null,
                startedAt, Instant.now()
        );
    }

    public static ScaleOutResult failed(
            String modelType, String instanceId,
            int exitCode, long durationMs,
            List<String> stdout, List<String> stderr,
            String reason, Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.FAILED, exitCode, durationMs,
                stdout, stderr, reason,
                startedAt, Instant.now()
        );
    }

    public static ScaleOutResult timeout(
            String modelType, String instanceId,
            long durationMs, Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.TIMEOUT, -1, durationMs,
                List.of(), List.of(), "Script execution timed out",
                startedAt, Instant.now()
        );
    }

    public static ScaleOutResult launchError(
            String modelType, String instanceId,
            String reason, Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.LAUNCH_ERROR, -1, 0,
                List.of(), List.of(), reason,
                startedAt, Instant.now()
        );
    }

    public static ScaleOutResult mockSuccess(String modelType, String instanceId) {
        Instant now = Instant.now();
        return new ScaleOutResult(
                modelType, instanceId,
                Status.MOCK_SUCCESS, 0, 3000,
                List.of("[MOCK] EC2 created", "[MOCK] ALB registered", "[MOCK] file_sd.json updated"),
                List.of(), null,
                now.minusSeconds(3), now
        );
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.MOCK_SUCCESS;
    }
}
