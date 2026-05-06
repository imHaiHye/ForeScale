package com.viralguard;

import com.viralguard.dto.ScaleOutResult;
import com.viralguard.service.ScaleOutHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScaleOutHistoryService 단위 테스트.
 *
 * 검증 항목:
 *   1. 이력 기록 및 조회
 *   2. 최신 순 정렬
 *   3. 50건 초과 시 오래된 항목 자동 제거 (링버퍼)
 *   4. 통계(성공/실패 카운트) 정확성
 *   5. 빈 상태에서 getLatest() null 반환
 */
class ScaleOutHistoryServiceTest {

    private ScaleOutHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ScaleOutHistoryService();
    }

    @Test
    @DisplayName("이력 기록 후 조회 시 포함됨")
    void record_thenGetHistory_shouldContainResult() {
        ScaleOutResult result = ScaleOutResult.mockSuccess("CPU", "i-001");
        service.record(result);

        List<ScaleOutResult> history = service.getHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).modelType()).isEqualTo("CPU");
    }

    @Test
    @DisplayName("빈 상태에서 getLatest() → null")
    void getLatest_empty_shouldReturnNull() {
        assertThat(service.getLatest()).isNull();
    }

    @Test
    @DisplayName("최근 기록이 getLatest()로 반환됨")
    void getLatest_shouldReturnMostRecent() {
        service.record(ScaleOutResult.mockSuccess("CPU", "i-001"));
        service.record(ScaleOutResult.mockSuccess("TRAFFIC", "i-002"));

        ScaleOutResult latest = service.getLatest();
        assertThat(latest.modelType()).isEqualTo("TRAFFIC");
    }

    @Test
    @DisplayName("51건 기록 시 가장 오래된 항목 제거됨 (링버퍼)")
    void record_over50_shouldEvictOldest() {
        for (int i = 0; i < 51; i++) {
            service.record(ScaleOutResult.mockSuccess("CPU", "i-" + i));
        }
        assertThat(service.getHistory()).hasSize(50);
    }

    @Test
    @DisplayName("통계: 성공 4건 + 실패 1건 정확히 집계")
    void getStats_shouldCountCorrectly() {
        service.record(ScaleOutResult.mockSuccess("CPU", "i-001"));
        service.record(ScaleOutResult.mockSuccess("CPU", "i-002"));
        service.record(ScaleOutResult.mockSuccess("CPU", "i-003"));
        service.record(ScaleOutResult.mockSuccess("TRAFFIC", "i-004"));
        service.record(ScaleOutResult.failed(
                "CPU", "i-005", 1, 1000L,
                List.of(), List.of("error"), "exitCode=1", Instant.now()
        ));

        ScaleOutHistoryService.ScaleOutStats stats = service.getStats();
        assertThat(stats.total()).isEqualTo(5);
        assertThat(stats.success()).isEqualTo(4);
        assertThat(stats.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 상태 통계 → total=0, avgDuration=0")
    void getStats_empty_shouldReturnZeros() {
        ScaleOutHistoryService.ScaleOutStats stats = service.getStats();
        assertThat(stats.total()).isZero();
        assertThat(stats.success()).isZero();
        assertThat(stats.failed()).isZero();
        assertThat(stats.avgDurationMs()).isZero();
    }
}
