package com.viralguard.controller;

import com.viralguard.component.CooldownManager;
import com.viralguard.dto.ScaleOutRequestDTO;
import com.viralguard.dto.ScaleOutResponseDTO;
import com.viralguard.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI VM(FastAPI)으로부터 Scale-out 알람을 수신하는 Webhook 컨트롤러.
 *
 * Base URL: /api/v1
 *
 * 엔드포인트:
 *   POST /alerts/scale-out  → Scale-out 트리거 수신
 *   GET  /status/cooldown   → 현재 Cooldown 상태 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final CooldownManager cooldownManager;

    /**
     * Scale-out 알람 수신 Webhook.
     *
     * FastAPI가 Prophet 모델의 임계치 초과 예측 시 이 엔드포인트로 POST 요청을 보냄.
     *
     * 응답 코드:
     *   200 OK      - Scale-out 실행 시작됨
     *   409 Conflict - Cooldown 활성화 중 (중복 요청 무시됨)
     *   400 BadRequest - 요청 페이로드 유효성 검증 실패
     *   500 Internal - 서버 내부 오류
     */
    @PostMapping("/alerts/scale-out")
    public ResponseEntity<ScaleOutResponseDTO> receiveScaleOutAlert(
            @Valid @RequestBody ScaleOutRequestDTO request) {

        log.info("[AlertController] ▶ POST /api/v1/alerts/scale-out 수신");
        log.debug("[AlertController] 요청 페이로드: {}", request);

        ScaleOutResponseDTO response = alertService.handleScaleOutAlert(request);

        // Cooldown 활성 상태였으면 409 반환
        HttpStatus status = "COOLDOWN_ACTIVE".equals(response.getStatus())
                ? HttpStatus.CONFLICT
                : HttpStatus.OK;

        log.info("[AlertController] ◀ 응답: status={}, httpCode={}", response.getStatus(), status.value());
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 현재 Cooldown 상태 조회 API.
     *
     * 운영자 또는 Grafana 대시보드에서 현재 Lock 상태 확인용.
     *
     * 응답 예시 (활성화 중):
     * {
     *   "active": true,
     *   "lockedAt": "2024-01-15T14:23:00Z",
     *   "unlockAt": "2024-01-15T14:28:00Z",
     *   "remainingSeconds": 180
     * }
     */
    @GetMapping("/status/cooldown")
    public ResponseEntity<CooldownManager.CooldownStatus> getCooldownStatus() {
        CooldownManager.CooldownStatus status = cooldownManager.getStatus();
        log.debug("[AlertController] GET /api/v1/status/cooldown → active={}", status.active());
        return ResponseEntity.ok(status);
    }

    /**
     * JMeter 부하 테스트 대상 엔드포인트.
     *
     * CPU 및 네트워크 메트릭을 유발하기 위한 더미 API.
     * Prometheus가 이 VM의 node_cpu_seconds_total, node_network_receive_bytes를 수집함.
     */
    @GetMapping("/hello")
    public ResponseEntity<String> hello() {
        // JMeter가 이 엔드포인트에 부하를 주면 CPU/네트워크 메트릭 상승
        return ResponseEntity.ok("Viral-Guard App VM is running 🚀");
    }
}
