package com.viralguard.controller;

import com.viralguard.dto.ScaleOutResult;
import com.viralguard.service.ScaleOutHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Scale-out 실행 이력 및 상태 조회 API.
 *
 * 엔드포인트:
 *   GET  /api/v1/scale-out/history  → 최근 실행 이력 목록
 *   GET  /api/v1/scale-out/latest   → 마지막 실행 결과
 *   GET  /api/v1/scale-out/stats    → 전체 통계 (성공/실패 카운트)
 *
 * 운영자가 Grafana 또는 curl로 현재 상태를 확인하기 위한 용도.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/scale-out")
@RequiredArgsConstructor
public class ScaleOutController {

    private final ScaleOutHistoryService historyService;

    /**
     * 최근 Scale-out 실행 이력 목록 (최신 순, 최대 50건).
     *
     * 확인 포인트:
     *   - status: SUCCESS / FAILED / TIMEOUT / LAUNCH_ERROR / MOCK_SUCCESS
     *   - exitCode: 0=성공, 그 외=실패
     *   - durationMs: 스크립트 실행 시간
     *   - failureReason: 실패 사유 (실패 시만)
     *   - stderrLines: Python 스크립트 에러 출력
     */
    @GetMapping("/history")
    public ResponseEntity<List<ScaleOutResult>> getHistory() {
        List<ScaleOutResult> history = historyService.getHistory();
        log.debug("[ScaleOutController] GET /history → {}건 반환", history.size());
        return ResponseEntity.ok(history);
    }

    /**
     * 마지막 Scale-out 실행 결과.
     * 아직 한 번도 실행되지 않은 경우 404 반환.
     */
    @GetMapping("/latest")
    public ResponseEntity<?> getLatest() {
        ScaleOutResult latest = historyService.getLatest();
        if (latest == null) {
            return ResponseEntity.notFound().build();
        }
        log.debug("[ScaleOutController] GET /latest → status={}", latest.status());
        return ResponseEntity.ok(latest);
    }

    /**
     * 전체 실행 통계.
     *
     * 응답 예시:
     * {
     *   "total": 5,
     *   "success": 4,
     *   "failed": 1,
     *   "avgDurationMs": 3200
     * }
     */
    @GetMapping("/stats")
    public ResponseEntity<ScaleOutHistoryService.ScaleOutStats> getStats() {
        ScaleOutHistoryService.ScaleOutStats stats = historyService.getStats();
        log.debug("[ScaleOutController] GET /stats → total={}, success={}, failed={}",
                stats.total(), stats.success(), stats.failed());
        return ResponseEntity.ok(stats);
    }
}
