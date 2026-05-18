package com.viralguard;

import com.viralguard.dto.ScaleOutEventDTO;
import com.viralguard.dto.ScaleOutResult;
import com.viralguard.dto.ScaleOutResultPayloadDTO;
import com.viralguard.dto.TriggerDTO;
import com.viralguard.service.ScaleOutExecutor;
import com.viralguard.service.ScaleOutHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScaleOutExecutor 단위 테스트 (실제 객체 사용, mock 없음).
 *
 * 검증:
 *   1. C팀 이벤트 수신 시 historyService에 이력 저장됨
 *   2. ScaleOutResult에 이벤트 필드가 정확히 매핑됨
 *   3. 결과 상태가 SUCCESS로 저장됨
 */
class ScaleOutExecutorTest {

    private ScaleOutHistoryService historyService;
    private ScaleOutExecutor executor;

    @BeforeEach
    void setUp() {
        historyService = new ScaleOutHistoryService();
        executor = new ScaleOutExecutor(historyService);
    }

    private ScaleOutEventDTO buildEvent(String modelType, String instanceId, String vmName, String sourceInstanceId) {
        try {
            var trigger = TriggerDTO.class.getDeclaredConstructor().newInstance();
            setField(trigger, "modelType", modelType);
            setField(trigger, "predictedValue", 87.3);
            setField(trigger, "threshold", 80.0);
            setField(trigger, "predictedAt", "2026-05-18T14:20:00Z");
            setField(trigger, "severity", "CRITICAL");

            var payload = ScaleOutResultPayloadDTO.class.getDeclaredConstructor().newInstance();
            setField(payload, "instanceId", instanceId);
            setField(payload, "vmName", vmName);
            setField(payload, "privateIp", "10.0.2.100");
            setField(payload, "status", "provisioned");

            var event = ScaleOutEventDTO.class.getDeclaredConstructor().newInstance();
            setField(event, "eventType", "SCALE_OUT_COMPLETED");
            setField(event, "trigger", trigger);
            setField(event, "scaleOutResult", payload);
            setField(event, "sourceInstanceId", sourceInstanceId);
            setField(event, "executedAt", "2026-05-18T14:20:05Z");

            return event;
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
    @DisplayName("이벤트 수신 시 이력에 저장됨")
    void execute_shouldSaveToHistory() {
        executor.execute(buildEvent("CPU", "i-0abc1234", "forescale-recovery-001", "i-03512866"));

        assertThat(historyService.getHistory()).hasSize(1);
    }

    @Test
    @DisplayName("이벤트 필드가 ScaleOutResult에 정확히 매핑됨")
    void execute_shouldMapEventFieldsCorrectly() {
        executor.execute(buildEvent("TRAFFIC", "i-0traffic001", "forescale-recovery-traffic-001", "i-0source001"));

        ScaleOutResult result = historyService.getLatest();
        assertThat(result.modelType()).isEqualTo("TRAFFIC");
        assertThat(result.sourceInstanceId()).isEqualTo("i-0source001");
        assertThat(result.newInstanceId()).isEqualTo("i-0traffic001");
        assertThat(result.vmName()).isEqualTo("forescale-recovery-traffic-001");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("결과 상태가 SUCCESS이고 exitCode가 0임")
    void execute_shouldSetSuccessStatus() {
        executor.execute(buildEvent("CPU", "i-0abc1234", "forescale-recovery-001", "i-03512866"));

        ScaleOutResult result = historyService.getLatest();
        assertThat(result.status()).isEqualTo(ScaleOutResult.Status.SUCCESS);
        assertThat(result.exitCode()).isEqualTo(0);
    }
}
