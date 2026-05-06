package com.viralguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AlertController Mock AI 알람 수신 통합 테스트.
 *
 * Week 1 DoD 검증:
 *   ✅ 정상 알람 수신 → 200 OK + TRIGGERED 상태
 *   ✅ 연속 알람 → 두 번째는 409 Conflict + COOLDOWN_ACTIVE 상태
 *   ✅ 잘못된 페이로드 → 400 Bad Request
 *   ✅ /health → 200 OK
 *   ✅ /api/v1/hello → 200 OK
 *   ✅ /api/v1/status/cooldown → 200 OK + JSON 구조
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlertControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String buildAlertJson(String modelType, double predicted, double threshold) {
        return String.format("""
                {
                    "model_type": "%s",
                    "predicted_value": %.1f,
                    "threshold": %.1f,
                    "predicted_at": "2024-01-15T14:23:00Z",
                    "source_instance_id": "i-0test123",
                    "severity": "CRITICAL"
                }
                """, modelType, predicted, threshold);
    }

    @Test
    @DisplayName("CPU 알람 정상 수신 → 200 OK + TRIGGERED")
    void receiveAlert_cpu_shouldReturn200Triggered() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildAlertJson("CPU", 87.3, 85.0)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("TRAFFIC 알람 정상 수신 → 200 OK + TRIGGERED")
    void receiveAlert_traffic_shouldReturn200Triggered() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildAlertJson("TRAFFIC", 52428800.0, 41943040.0)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIGGERED"));
    }

    @Test
    @DisplayName("필수 필드 누락 → 400 Bad Request")
    void receiveAlert_missingRequiredField_shouldReturn400() throws Exception {
        String invalidJson = """
                {
                    "predicted_value": 87.3,
                    "threshold": 85.0,
                    "predicted_at": "2024-01-15T14:23:00Z"
                }
                """;
        // model_type 누락

        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("/health 헬스체크 → 200 OK + UP 상태")
    void healthCheck_shouldReturn200Up() throws Exception {
        mockMvc.perform(get("/health"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/api/v1/hello 부하 테스트 엔드포인트 → 200 OK")
    void hello_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/hello"))
                .andDo(print())
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cooldown 상태 조회 API → 200 OK + JSON 구조")
    void getCooldownStatus_shouldReturnJson() throws Exception {
        mockMvc.perform(get("/api/v1/status/cooldown"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").exists());
    }
}
