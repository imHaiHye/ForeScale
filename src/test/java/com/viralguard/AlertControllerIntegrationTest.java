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
 * AlertController Scale-out 이벤트 수신 통합 테스트.
 *
 * 검증:
 *   ✅ C팀 이벤트 정상 수신 → 200 OK + RECEIVED 상태
 *   ✅ 필수 필드 누락 → 400 Bad Request
 *   ✅ /health → 200 OK
 *   ✅ /api/v1/hello → 200 OK
 *
 * Note: GrafanaAnnotationService는 연결 실패 시 warn 로그만 출력하고 계속 진행함.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlertControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String buildEventJson(String modelType, double predicted, double threshold, String instanceId, String vmName) {
        return String.format("""
                {
                    "event_type": "SCALE_OUT_COMPLETED",
                    "trigger": {
                        "model_type": "%s",
                        "predicted_value": %.1f,
                        "threshold": %.1f,
                        "predicted_at": "2026-05-18T14:20:00Z",
                        "severity": "CRITICAL",
                        "reason": "predicted exceeds threshold"
                    },
                    "scale_out_result": {
                        "instance_id": "%s",
                        "private_ip": "10.0.2.100",
                        "vm_name": "%s",
                        "status": "provisioned"
                    },
                    "source_instance_id": "i-03512866b1c1ba03e",
                    "executed_at": "2026-05-18T14:20:05Z"
                }
                """, modelType, predicted, threshold, instanceId, vmName);
    }

    @Test
    @DisplayName("CPU Scale-out 이벤트 정상 수신 → 200 OK + RECEIVED")
    void receiveEvent_cpu_shouldReturn200Received() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("CPU", 87.3, 80.0, "i-0abc1234", "forescale-recovery-20260518-cpu001")))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.message").value("Scale-out event recorded successfully"))
                .andExpect(jsonPath("$.instance_id").value("i-0abc1234"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("TRAFFIC Scale-out 이벤트 정상 수신 → 200 OK + RECEIVED")
    void receiveEvent_traffic_shouldReturn200Received() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildEventJson("TRAFFIC", 52428800.0, 41943040.0, "i-0traffic001", "forescale-recovery-traffic-001")))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    @DisplayName("event_type 누락 → 400 Bad Request")
    void receiveEvent_missingEventType_shouldReturn400() throws Exception {
        String invalidJson = """
                {
                    "trigger": {
                        "model_type": "CPU",
                        "predicted_value": 87.3,
                        "threshold": 80.0,
                        "predicted_at": "2026-05-18T14:20:00Z",
                        "severity": "CRITICAL"
                    },
                    "scale_out_result": {
                        "instance_id": "i-0abc1234",
                        "status": "provisioned"
                    },
                    "source_instance_id": "i-03512866b1c1ba03e"
                }
                """;

        mockMvc.perform(post("/api/v1/alerts/scale-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("scale_out_result 누락 → 400 Bad Request")
    void receiveEvent_missingScaleOutResult_shouldReturn400() throws Exception {
        String invalidJson = """
                {
                    "event_type": "SCALE_OUT_COMPLETED",
                    "trigger": {
                        "model_type": "CPU",
                        "predicted_value": 87.3,
                        "threshold": 80.0
                    },
                    "source_instance_id": "i-03512866b1c1ba03e"
                }
                """;

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
}
