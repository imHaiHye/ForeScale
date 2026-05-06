package com.viralguard.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * AWS ALB 헬스체크 엔드포인트.
 *
 * ALB가 10초마다 GET /health 를 호출하여 인스턴스 상태를 확인.
 * 200 OK 반환 시 Healthy, 그 외 상태 코드는 Unhealthy로 판정됨.
 *
 * 설정 (Terraform ALB Health Check):
 *   path     = "/health"
 *   port     = "8080"
 *   interval = 10 (초)
 *   timeout  = 5  (초)
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "viral-guard-backend",
                "timestamp", Instant.now().toString()
        ));
    }
}
