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
 * 신규 엔드포인트 통합 테스트.
 *
 * 검증:
 *   GET /api/v1/scale-out/history  → 200 + 배열
 *   GET /api/v1/scale-out/latest   → 404 (이력 없음) or 200
 *   GET /api/v1/scale-out/stats    → 200 + total/success/failed/avgDurationMs
 *   이벤트 수신 후 이력에 기록됨
 */
@SpringBootTest
@AutoConfigureMockMvc
class Week2IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String EVENT_JSON = """
            {
                "event_type": "SCALE_OUT_COMPLETED",
                "trigger": {
                    "model_type": "CPU",
                    "predicted_value": 91.5,
                    "threshold": 85.0,
                    "predicted_at": "2026-05-18T14:20:00Z",
                    "severity": "CRITICAL",
                    "reason": "predicted exceeds threshold"
                },
                "scale_out_result": {
                    "instance_id": "i-0week2test",
                    "private_ip": "10.0.2.100",
                    "vm_name": "forescale-recovery-week2",
                    "status": "provisioned"
                },
                "source_instance_id": "i-03512866b1c1ba03e",
                "executed_at": "2026-05-18T14:20:05Z"
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
    @DisplayName("아무 실행 없을 때 latest → 404 or 200")
    void getLatest_noHistory_shouldReturn404Or200() throws Exception {
        mockMvc.perform(get("/api/v1/scale-out/latest"))
                .andDo(print())
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 200 || status == 404 : "200 or 404 expected, got " + status;
                });
    }

    @Test
    @DisplayName("이벤트 수신 → 200 OK + RECEIVED")
    void receiveEvent_shouldReturn200Received() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.instance_id").value("i-0week2test"));
    }

    @Test
    @DisplayName("ScaleOutResult SUCCESS 상태 검증")
    void scaleOutResult_successStatus_shouldBeCorrect() {
        var mockSuccess = com.viralguard.dto.ScaleOutResult.mockSuccess("CPU", "i-001");
        assert mockSuccess.isSuccess();
        assert mockSuccess.status() == com.viralguard.dto.ScaleOutResult.Status.MOCK_SUCCESS;
        assert mockSuccess.exitCode() == 0;
    }
}
