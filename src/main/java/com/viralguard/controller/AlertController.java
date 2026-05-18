package com.viralguard.controller;

import com.viralguard.dto.AlertReceivedResponseDTO;
import com.viralguard.dto.ScaleOutEventDTO;
import com.viralguard.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * C팀으로부터 Scale-out 완료 이벤트를 수신하는 Webhook 컨트롤러.
 *
 * Base URL: /api/v1
 *
 * 엔드포인트:
 *   POST /alerts/scale-out  → Scale-out 완료 이벤트 수신 및 이력 저장
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /**
     * Scale-out 완료 이벤트 수신 Webhook.
     *
     * C팀이 scale_out() 실행 완료 후 이 엔드포인트로 POST 요청을 보냄.
     *
     * 응답 코드:
     *   200 OK      - 이벤트 수신 및 이력 저장 완료
     *   400 BadRequest - 요청 페이로드 유효성 검증 실패
     *   500 Internal - 서버 내부 오류
     */
    @PostMapping("/alerts/scale-out")
    public ResponseEntity<AlertReceivedResponseDTO> receiveScaleOutAlert(
            @Valid @RequestBody ScaleOutEventDTO event) {

        log.info("[AlertController] ▶ POST /api/v1/alerts/scale-out 수신 | eventType={}", event.getEventType());

        AlertReceivedResponseDTO response = alertService.handleScaleOutAlert(event);

        log.info("[AlertController] ◀ 응답: status={}, instanceId={}", response.getStatus(), response.getInstanceId());
        return ResponseEntity.ok(response);
    }

    /**
     * JMeter 부하 테스트 대상 엔드포인트.
     */
    @GetMapping("/hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Viral-Guard App VM is running");
    }
}
