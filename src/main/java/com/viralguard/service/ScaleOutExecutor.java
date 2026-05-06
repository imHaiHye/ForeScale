package com.viralguard.service;

import com.viralguard.component.CooldownManager;
import com.viralguard.component.ProcessStreamGobbler;
import com.viralguard.config.ScaleOutProperties;
import com.viralguard.dto.ScaleOutRequestDTO;
import com.viralguard.dto.ScaleOutResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * boto3 Python Scale-out 스크립트 실행기 — Week 2 완성본.
 *
 * ── 실행 흐름 ──────────────────────────────────────────────────
 *  1. @Async → 별도 스레드에서 실행, 호출 즉시 반환
 *  2. ProcessBuilder로 Python 스크립트 기동
 *  3. ProcessStreamGobbler 2개(stdout/stderr)로 동시 스트림 소비 (데드락 방지)
 *  4. process.waitFor(timeout) 로 완료 대기
 *  5. exitCode 기반 성공/실패 판정
 *  6. ScaleOutHistoryService에 결과 기록
 *  7. 실패 시 unlock-on-failure 설정에 따라 Cooldown 해제
 *
 * ── boto3 스크립트 계약 (인프라팀 협의) ───────────────────────
 *  실행:   python3 {scriptPath} {instanceId} {modelType}
 *  성공:   exitCode = 0
 *  실패:   exitCode != 0, stderr에 오류 내용 출력
 *  환경변수: AWS_DEFAULT_REGION (ProcessBuilder.environment()로 주입)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaleOutExecutor {

    private final ScaleOutProperties props;
    private final CooldownManager cooldownManager;
    private final ScaleOutHistoryService historyService;

    /**
     * Scale-out 스크립트를 비동기로 실행합니다.
     *
     * @Async("scaleOutExecutor"): AsyncConfig에 정의된 스레드 풀에서 실행.
     *   -> AlertService가 클라이언트에게 즉시 200 OK 반환 가능.
     *   -> 실제 EC2 생성(3~5분)은 백그라운드에서 진행.
     */
    @Async("scaleOutTaskExecutor")
    public void execute(ScaleOutRequestDTO request) {
        String instanceId = request.getSourceInstanceId() != null
                ? request.getSourceInstanceId()
                : "UNKNOWN";

        log.info("[Executor] Scale-out 실행 시작 | model={}, instanceId={}, mockMode={}",
                request.getModelType(), instanceId, props.getScaleOut().isMockMode());

        ScaleOutResult result;
        if (props.getScaleOut().isMockMode()) {
            result = executeMock(request, instanceId);
        } else {
            result = executeReal(request, instanceId);
        }

        historyService.record(result);

        if (!result.isSuccess()) {
            if (props.getScaleOut().isUnlockOnFailure()) {
                log.warn("[Executor] Scale-out 실패 -> Cooldown 해제 (다음 알람 재시도 허용)");
                cooldownManager.unlock();
            } else {
                log.warn("[Executor] Scale-out 실패 -> Cooldown 유지 (unlock-on-failure=false)");
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // REAL 실행 (ProcessBuilder + StreamGobbler)
    // ══════════════════════════════════════════════════════════

    private ScaleOutResult executeReal(ScaleOutRequestDTO request, String instanceId) {
        Instant startedAt = Instant.now();
        ScaleOutProperties.ScaleOut cfg = props.getScaleOut();

        log.info("[Executor][REAL] command: {} {} {} {}",
                cfg.getPythonPath(), cfg.getScriptPath(), instanceId, request.getModelType());

        // Step 1: 스크립트 파일 사전 검증
        File scriptFile = new File(cfg.getScriptPath());
        if (!scriptFile.exists()) {
            String reason = "스크립트 파일 없음: " + cfg.getScriptPath();
            log.error("[Executor][REAL] {}", reason);
            return ScaleOutResult.launchError(request.getModelType(), instanceId, reason, startedAt);
        }
        if (!scriptFile.canRead()) {
            String reason = "스크립트 읽기 권한 없음: " + cfg.getScriptPath();
            log.error("[Executor][REAL] {}", reason);
            return ScaleOutResult.launchError(request.getModelType(), instanceId, reason, startedAt);
        }

        // Step 2: ProcessBuilder 구성
        ProcessBuilder pb = new ProcessBuilder(
                cfg.getPythonPath(),
                cfg.getScriptPath(),
                instanceId,
                request.getModelType()
        );

        File workDir = new File(cfg.getWorkingDirectory());
        if (workDir.exists() && workDir.isDirectory()) {
            pb.directory(workDir);
        }

        // stdout과 stderr를 분리 유지 (StreamGobbler로 각각 처리)
        pb.redirectErrorStream(false);

        // AWS 환경변수 주입
        pb.environment().put("AWS_DEFAULT_REGION", cfg.getAwsRegion());
        pb.environment().put("PYTHONUNBUFFERED", "1");

        // Step 3: 프로세스 기동
        Process process;
        try {
            process = pb.start();
            log.info("[Executor][REAL] 프로세스 기동 완료 (PID: {})", process.pid());
        } catch (IOException e) {
            String reason = "프로세스 기동 실패: " + e.getMessage();
            log.error("[Executor][REAL] {}", reason, e);
            return ScaleOutResult.launchError(request.getModelType(), instanceId, reason, startedAt);
        }

        // Step 4: stdout / stderr 비동기 소비 (데드락 방지 핵심)
        ProcessStreamGobbler stdoutGobbler = new ProcessStreamGobbler(
                process.getInputStream(), "[boto3-out]", line -> log.info("{}", line)
        );
        ProcessStreamGobbler stderrGobbler = new ProcessStreamGobbler(
                process.getErrorStream(), "[boto3-err]", line -> log.warn("{}", line)
        );
        stdoutGobbler.start();
        stderrGobbler.start();

        // Step 5: 완료 대기
        boolean finished;
        try {
            finished = process.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            log.error("[Executor][REAL] 대기 중 인터럽트. 프로세스 강제 종료.");
            return ScaleOutResult.timeout(request.getModelType(), instanceId, elapsed(startedAt), startedAt);
        }

        if (!finished) {
            process.destroyForcibly();
            long ms = elapsed(startedAt);
            log.error("[Executor][REAL] 타임아웃 ({}ms > {}s). 강제 종료.", ms, cfg.getTimeoutSeconds());
            waitForGobblers(stdoutGobbler, stderrGobbler);
            return ScaleOutResult.timeout(request.getModelType(), instanceId, ms, startedAt);
        }

        // Step 6: 종료 코드 분석
        waitForGobblers(stdoutGobbler, stderrGobbler);
        int exitCode = process.exitValue();
        long elapsed = elapsed(startedAt);
        List<String> stdout = stdoutGobbler.getCollectedLines();
        List<String> stderr = stderrGobbler.getCollectedLines();

        if (exitCode == 0) {
            log.info("[Executor][REAL] Scale-out 완료! exitCode=0, elapsed={}ms", elapsed);
            return ScaleOutResult.success(request.getModelType(), instanceId,
                    exitCode, elapsed, stdout, stderr, startedAt);
        } else {
            String reason = String.format("exitCode=%d, stderr: %s",
                    exitCode, stderr.isEmpty() ? "(없음)" : stderr.get(stderr.size() - 1));
            log.error("[Executor][REAL] Scale-out 실패. {}", reason);
            return ScaleOutResult.failed(request.getModelType(), instanceId,
                    exitCode, elapsed, stdout, stderr, reason, startedAt);
        }
    }

    // ══════════════════════════════════════════════════════════
    // MOCK 실행 (로컬 개발 / CI)
    // ══════════════════════════════════════════════════════════

    private ScaleOutResult executeMock(ScaleOutRequestDTO request, String instanceId) {
        try {
            log.info("[Executor][MOCK] boto3 Scale-out 시뮬레이션 시작");
            log.info("[Executor][MOCK] script: {} | region: {}",
                    props.getScaleOut().getScriptPath(), props.getScaleOut().getAwsRegion());

            log.info("[Executor][MOCK] [1/4] RunInstances API 호출...");
            Thread.sleep(1_000);
            log.info("[Executor][MOCK] [2/4] 인스턴스 pending -> running...");
            Thread.sleep(1_000);
            log.info("[Executor][MOCK] [3/4] ALB Target Group 등록...");
            Thread.sleep(500);
            log.info("[Executor][MOCK] [4/4] Prometheus file_sd.json 업데이트...");
            Thread.sleep(500);

            log.info("[Executor][MOCK] Scale-out 시뮬레이션 완료");
            return ScaleOutResult.mockSuccess(request.getModelType(), instanceId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Executor][MOCK] 인터럽트 발생", e);
            return ScaleOutResult.failed(request.getModelType(), instanceId,
                    -1, 0, List.of(), List.of(), "Mock interrupted", Instant.now());
        }
    }

    private long elapsed(Instant startedAt) {
        return Instant.now().toEpochMilli() - startedAt.toEpochMilli();
    }

    private void waitForGobblers(ProcessStreamGobbler stdout, ProcessStreamGobbler stderr) {
        try {
            stdout.join(2_000);
            stderr.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
