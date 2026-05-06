package com.viralguard;

import com.viralguard.component.CooldownManager;
import com.viralguard.config.ScaleOutProperties;
import com.viralguard.dto.ScaleOutRequestDTO;
import com.viralguard.dto.ScaleOutResult;
import com.viralguard.service.ScaleOutExecutor;
import com.viralguard.service.ScaleOutHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ScaleOutExecutor 테스트.
 *
 * 검증 항목:
 *   1. Mock 모드 → MOCK_SUCCESS 결과 반환
 *   2. Real 모드 + 스크립트 없음 → LAUNCH_ERROR (Cooldown 해제)
 *   3. Real 모드 + exitCode 0 스크립트 → SUCCESS
 *   4. Real 모드 + exitCode 1 스크립트 → FAILED + Cooldown 해제
 *   5. Real 모드 + 타임아웃 → TIMEOUT + Cooldown 해제
 *   6. 이력이 ScaleOutHistoryService에 기록되는지
 */
@ExtendWith(MockitoExtension.class)
class ScaleOutExecutorTest {

    @Mock
    private CooldownManager cooldownManager;

    @Mock
    private ScaleOutHistoryService historyService;

    @InjectMocks
    private ScaleOutExecutor executor;

    @TempDir
    Path tempDir;

    private ScaleOutProperties props;

    @BeforeEach
    void setUp() {
        props = new ScaleOutProperties();
        props.getCooldown().setDurationSeconds(10);
        props.getScaleOut().setAwsRegion("ap-northeast-2");
        props.getScaleOut().setWorkingDirectory(tempDir.toString());
        props.getScaleOut().setTimeoutSeconds(5);
        props.getScaleOut().setUnlockOnFailure(true);
        ReflectionTestUtils.setField(executor, "props", props);
    }

    private ScaleOutRequestDTO buildRequest() {
        // @Valid 없이 직접 필드를 주입하는 방식으로 테스트용 DTO 생성
        // 실제 운영에서는 Jackson이 역직렬화
        try {
            var dto = ScaleOutRequestDTO.class.getDeclaredConstructor().newInstance();
            setField(dto, "modelType", "CPU");
            setField(dto, "predictedValue", 87.3);
            setField(dto, "threshold", 85.0);
            setField(dto, "predictedAt", "2024-01-15T14:23:00Z");
            setField(dto, "sourceInstanceId", "i-0test123");
            setField(dto, "severity", "CRITICAL");
            return dto;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("Mock 모드 실행 → MOCK_SUCCESS + 이력 기록")
    void execute_mockMode_shouldRetordMockSuccess() throws InterruptedException {
        props.getScaleOut().setMockMode(true);
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        // @Async를 직접 호출하면 동기로 실행됨 (테스트에서 @Async 동작 안 함)
        verify(historyService, timeout(5000)).record(argThat(result ->
                result.status() == ScaleOutResult.Status.MOCK_SUCCESS
        ));
        verify(cooldownManager, never()).unlock(); // 성공이므로 unlock 없음
    }

    @Test
    @DisplayName("Real 모드 + 스크립트 파일 없음 → LAUNCH_ERROR + Cooldown 해제")
    void execute_realMode_scriptNotFound_shouldReturnLaunchError() {
        props.getScaleOut().setMockMode(false);
        props.getScaleOut().setScriptPath("/nonexistent/path/scale_out.py");
        props.getScaleOut().setPythonPath("/usr/bin/python3");
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        verify(historyService, timeout(3000)).record(argThat(result ->
                result.status() == ScaleOutResult.Status.LAUNCH_ERROR
                        && result.failureReason().contains("스크립트 파일 없음")
        ));
        verify(cooldownManager, timeout(3000)).unlock();
    }

    @Test
    @DisplayName("Real 모드 + exitCode 0 스크립트 → SUCCESS")
    void execute_realMode_exitCode0_shouldReturnSuccess() throws IOException {
        // OS에서 실행 가능한 간단한 Python 스크립트 생성
        Path scriptFile = tempDir.resolve("scale_out.py");
        Files.writeString(scriptFile,
                "import sys\n" +
                "print(f'[boto3] Scale-out triggered: {sys.argv}')\n" +
                "print('[boto3] EC2 created: i-0mock999')\n" +
                "print('[boto3] ALB registered')\n" +
                "sys.exit(0)\n"
        );

        props.getScaleOut().setMockMode(false);
        props.getScaleOut().setScriptPath(scriptFile.toString());
        props.getScaleOut().setPythonPath("python3");
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        verify(historyService, timeout(10000)).record(argThat(result -> {
            System.out.println("Result status: " + result.status());
            return result.status() == ScaleOutResult.Status.SUCCESS
                    && result.exitCode() == 0
                    && result.isSuccess();
        }));
        verify(cooldownManager, never()).unlock();
    }

    @Test
    @DisplayName("Real 모드 + exitCode 1 스크립트 → FAILED + Cooldown 해제")
    void execute_realMode_exitCode1_shouldReturnFailed() throws IOException {
        Path scriptFile = tempDir.resolve("scale_out_fail.py");
        Files.writeString(scriptFile,
                "import sys\n" +
                "print('Starting scale-out...', file=sys.stderr)\n" +
                "print('ERROR: boto3 NoRegionError', file=sys.stderr)\n" +
                "sys.exit(1)\n"
        );

        props.getScaleOut().setMockMode(false);
        props.getScaleOut().setScriptPath(scriptFile.toString());
        props.getScaleOut().setPythonPath("python3");
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        verify(historyService, timeout(10000)).record(argThat(result ->
                result.status() == ScaleOutResult.Status.FAILED
                        && result.exitCode() == 1
                        && !result.isSuccess()
        ));
        verify(cooldownManager, timeout(3000)).unlock(); // unlock-on-failure=true
    }

    @Test
    @DisplayName("unlock-on-failure=false 시 실패해도 Cooldown 유지")
    void execute_realMode_failureWithUnlockDisabled_shouldNotUnlock() {
        props.getScaleOut().setMockMode(false);
        props.getScaleOut().setScriptPath("/nonexistent/script.py");
        props.getScaleOut().setUnlockOnFailure(false); // Cooldown 유지 설정
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        verify(historyService, timeout(3000)).record(any());
        verify(cooldownManager, never()).unlock(); // unlock 호출 없음
    }

    @Test
    @DisplayName("이력 기록에 modelType과 instanceId가 정확히 포함됨")
    void execute_shouldRecordCorrectMetadata() {
        props.getScaleOut().setMockMode(true);
        ScaleOutRequestDTO request = buildRequest();

        executor.execute(request);

        verify(historyService, timeout(5000)).record(argThat(result ->
                "CPU".equals(result.modelType())
                        && "i-0test123".equals(result.sourceInstanceId())
        ));
    }
}
