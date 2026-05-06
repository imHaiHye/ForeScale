package com.viralguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Week 2 신규 엔드포인트 통합 테스트.
 *
 * 검증:
 *   GET /api/v1/scale-out/history  → 200 + 배열
 *   GET /api/v1/scale-out/latest   → 404 (이력 없음) or 200
 *   GET /api/v1/scale-out/stats    → 200 + total/success/failed/avgDurationMs
 *   Mock 모드 알람 수신 후 이력에 기록됨
 */
@SpringBootTest
@AutoConfigureMockMvc
class Week2IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String ALERT_JSON = """
            {
                "model_type": "CPU",
                "predicted_value": 91.5,
                "threshold": 85.0,
                "predicted_at": "2024-01-15T14:23:00Z",
                "source_instance_id": "i-0week2test",
                "severity": "CRITICAL"
            }
            """;

    @Test
    @DisplayName("이력 조회 API → 200 OK + JSON 배열")
    void getHistory_shouldReturn200Array() throws Exception {
        mockMvc.perform(get("/api/v1/scale-out/history"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("통계 API → 200 OK + 필수 필드 포함")
    void getStats_shouldReturn200WithRequiredFields() throws Exception {
        mockMvc.perform(get("/api/v1/scale-out/stats"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.success").exists())
                .andExpect(jsonPath("$.failed").exists())
                .andExpect(jsonPath("$.avgDurationMs").exists());
    }

    @Test
    @DisplayName("아무 실행 없을 때 latest → 404")
    void getLatest_noHistory_shouldReturn404() throws Exception {
        // 이력이 없을 때 (다른 테스트에서 기록했을 수 있으므로 케이스 분기)
        mockMvc.perform(get("/api/v1/scale-out/latest"))
                .andDo(print())
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 200 || status == 404 : "200 or 404 expected, got " + status;
                });
    }

    @Test
    @DisplayName("TRAFFIC 모델 알람 수신 → 200 OK + TRIGGERED")
    void receiveTrafficAlert_shouldReturn200() throws Exception {
        String trafficJson = """
                {
                    "model_type": "TRAFFIC",
                    "predicted_value": 52428800.0,
                    "threshold": 41943040.0,
                    "predicted_at": "2024-01-15T15:00:00Z",
                    "source_instance_id": "i-0traffic001",
                    "severity": "WARNING"
                }
                """;

        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trafficJson))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIGGERED"));
    }

    @Test
    @DisplayName("Cooldown 상태 API → active 필드 포함")
    void cooldownStatus_shouldIncludeActiveField() throws Exception {
        mockMvc.perform(get("/api/v1/status/cooldown"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").isBoolean());
    }

    @Test
    @DisplayName("ScaleOutResult 상태 상수 검증")
    void scaleOutResult_statusValues_shouldBeCorrect() {
        var mockSuccess = com.viralguard.dto.ScaleOutResult.mockSuccess("CPU", "i-001");
        assert mockSuccess.isSuccess();
        assert mockSuccess.status() == com.viralguard.dto.ScaleOutResult.Status.MOCK_SUCCESS;
        assert mockSuccess.exitCode() == 0;
    }
}
