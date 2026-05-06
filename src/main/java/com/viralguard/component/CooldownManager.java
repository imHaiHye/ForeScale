package com.viralguard.component;

import com.viralguard.config.ScaleOutProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scale-out 중복 실행 방지 컴포넌트 (Cooldown / Debounce Lock).
 *
 * 핵심 문제:
 *   AI(Prophet)는 1분 주기로 임계치 초과 예측 알람을 지속 발송함.
 *   EC2 Cold Start(부팅 + ALB 등록) 시간이 약 3~5분 소요됨.
 *   -> 이 사이에 들어오는 모든 추가 알람을 무시(Lock)해야 함.
 *
 * Thread-Safety: AtomicBoolean.compareAndSet으로 보장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CooldownManager {

    private final ScaleOutProperties props;

    private final AtomicBoolean locked = new AtomicBoolean(false);
    private volatile Instant lockedAt;
    private volatile Instant unlockAt;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cooldown-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> pendingUnlock;

    public boolean activate() {
        if (!locked.compareAndSet(false, true)) {
            log.warn("[Cooldown] 이미 활성화 상태. unlockAt={}", unlockAt);
            return false;
        }

        long durationSeconds = props.getCooldown().getDurationSeconds();
        lockedAt = Instant.now();
        unlockAt = lockedAt.plusSeconds(durationSeconds);

        if (pendingUnlock != null && !pendingUnlock.isDone()) {
            pendingUnlock.cancel(false);
        }

        pendingUnlock = scheduler.schedule(this::unlock, durationSeconds, TimeUnit.SECONDS);
        log.info("[Cooldown] ACTIVATED | duration={}s, unlockAt={}", durationSeconds, unlockAt);
        return true;
    }

    public void unlock() {
        if (locked.compareAndSet(true, false)) {
            log.info("[Cooldown] DEACTIVATED | was lockedAt={}", lockedAt);
            lockedAt = null;
            unlockAt = null;
        }
    }

    public boolean isActive() {
        return locked.get();
    }

    public CooldownStatus getStatus() {
        if (!locked.get()) {
            return CooldownStatus.inactive();
        }
        long remainingSeconds = unlockAt != null
                ? Math.max(0, unlockAt.getEpochSecond() - Instant.now().getEpochSecond())
                : 0;
        return CooldownStatus.active(
                lockedAt != null ? lockedAt.toString() : "unknown",
                unlockAt != null ? unlockAt.toString() : "unknown",
                remainingSeconds
        );
    }

    public record CooldownStatus(
            boolean active,
            String lockedAt,
            String unlockAt,
            long remainingSeconds
    ) {
        static CooldownStatus inactive() {
            return new CooldownStatus(false, null, null, 0);
        }

        static CooldownStatus active(String lockedAt, String unlockAt, long remainingSeconds) {
            return new CooldownStatus(true, lockedAt, unlockAt, remainingSeconds);
        }
    }
}
