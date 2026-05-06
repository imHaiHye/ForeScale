package com.viralguard.service;

import com.viralguard.dto.ScaleOutResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Scale-out 실행 이력 관리 서비스.
 *
 * MVP 범위: SQLite 영구 저장은 Out-of-Scope이므로 인메모리 링버퍼로 구현.
 * 최근 50건을 보관하며 GET /api/v1/scale-out/history 로 조회 가능.
 *
 * 재시작 시 이력 초기화됨 — 데모 환경에서는 허용.
 */
@Slf4j
@Service
public class ScaleOutHistoryService {

    /** 최대 보관 건수 (링버퍼) */
    private static final int MAX_HISTORY = 50;

    private final Deque<ScaleOutResult> history = new ArrayDeque<>();

    /**
     * 실행 결과를 이력에 추가합니다.
     */
    public synchronized void record(ScaleOutResult result) {
        if (history.size() >= MAX_HISTORY) {
            history.pollFirst(); // 가장 오래된 항목 제거
        }
        history.addLast(result);

        if (result.isSuccess()) {
            log.info("[History] ✅ Scale-out 성공 기록 | model={} instance={} duration={}ms",
                    result.modelType(), result.sourceInstanceId(), result.durationMs());
        } else {
            log.warn("[History] ❌ Scale-out 실패 기록 | status={} reason={} model={}",
                    result.status(), result.failureReason(), result.modelType());
        }
    }

    /**
     * 최신 순으로 이력 목록을 반환합니다.
     */
    public synchronized List<ScaleOutResult> getHistory() {
        return history.stream()
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList();
    }

    /**
     * 마지막 실행 결과를 반환합니다.
     */
    public synchronized ScaleOutResult getLatest() {
        return history.peekLast();
    }

    /**
     * 전체 실행 통계를 반환합니다.
     */
    public synchronized ScaleOutStats getStats() {
        long total = history.size();
        long success = history.stream().filter(ScaleOutResult::isSuccess).count();
        long failed = total - success;
        double avgDuration = history.stream()
                .mapToLong(ScaleOutResult::durationMs)
                .average()
                .orElse(0);

        return new ScaleOutStats(total, success, failed, (long) avgDuration);
    }

    public record ScaleOutStats(long total, long success, long failed, long avgDurationMs) {}
}
