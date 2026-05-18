package com.viralguard.dto;

import java.time.Instant;
import java.util.List;

/**
 * Scale-out 실행 결과를 담는 불변 레코드.
 *
 * ScaleOutExecutor → ScaleOutHistoryService로 전달되어 이력 기록에 사용됨.
 * Week 3: C팀 이벤트 수신 구조로 변경, newInstanceId/vmName/privateIp 필드 추가.
 */
public record ScaleOutResult(

        String modelType,
        String sourceInstanceId,

        Status status,
        int exitCode,
        long durationMs,

        List<String> stdoutLines,
        List<String> stderrLines,

        String failureReason,

        Instant startedAt,
        Instant finishedAt,

        // C팀으로부터 수신한 신규 VM 정보
        String newInstanceId,
        String vmName,
        String privateIp

) {
    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        LAUNCH_ERROR,
        MOCK_SUCCESS
    }

    // ── 정적 팩토리 ────────────────────────────────────────────

    public static ScaleOutResult fromEvent(ScaleOutEventDTO event) {
        Instant executedAt;
        try {
            executedAt = Instant.parse(event.getExecutedAt());
        } catch (Exception e) {
            executedAt = Instant.now();
        }
        return new ScaleOutResult(
                event.getTrigger().getModelType(),
                event.getSourceInstanceId(),
                Status.SUCCESS,
                0,
                0L,
                List.of(),
                List.of(),
                null,
                executedAt,
                Instant.now(),
                event.getScaleOutResult().getInstanceId(),
                event.getScaleOutResult().getVmName(),
                event.getScaleOutResult().getPrivateIp()
        );
    }

    public static ScaleOutResult success(
            String modelType, String instanceId,
            int exitCode, long durationMs,
            List<String> stdout, List<String> stderr,
            Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.SUCCESS, exitCode, durationMs,
                stdout, stderr, null,
                startedAt, Instant.now(),
                null, null, null
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
                startedAt, Instant.now(),
                null, null, null
        );
    }

    public static ScaleOutResult timeout(
            String modelType, String instanceId,
            long durationMs, Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.TIMEOUT, -1, durationMs,
                List.of(), List.of(), "Script execution timed out",
                startedAt, Instant.now(),
                null, null, null
        );
    }

    public static ScaleOutResult launchError(
            String modelType, String instanceId,
            String reason, Instant startedAt) {
        return new ScaleOutResult(
                modelType, instanceId,
                Status.LAUNCH_ERROR, -1, 0,
                List.of(), List.of(), reason,
                startedAt, Instant.now(),
                null, null, null
        );
    }

    public static ScaleOutResult mockSuccess(String modelType, String instanceId) {
        Instant now = Instant.now();
        return new ScaleOutResult(
                modelType, instanceId,
                Status.MOCK_SUCCESS, 0, 3000,
                List.of("[MOCK] EC2 created", "[MOCK] ALB registered", "[MOCK] file_sd.json updated"),
                List.of(), null,
                now.minusSeconds(3), now,
                null, null, null
        );
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.MOCK_SUCCESS;
    }
}
