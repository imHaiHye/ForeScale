package com.viralguard;

import com.viralguard.component.CooldownManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CooldownManager 단위 테스트.
 *
 * 검증 항목:
 *   1. 최초 activate() → Lock 획득 성공 (true 반환)
 *   2. 이미 Lock 상태에서 activate() → false 반환 (중복 방지)
 *   3. unlock() 후 다시 activate() → 정상 작동
 *   4. 동시 다중 요청 시 단 1개만 Lock 획득 (Thread-Safety)
 *   5. isActive() 상태 정확성
 *   6. getStatus() 응답 정확성
 */
class CooldownManagerTest {

    private CooldownManager cooldownManager;

    @BeforeEach
    void setUp() {
        cooldownManager = new CooldownManager();
        // 테스트용 짧은 Cooldown (10초)
        ReflectionTestUtils.setField(cooldownManager, "cooldownDurationSeconds", 10L);
    }

    @Test
    @DisplayName("최초 activate() 호출 시 Lock 획득 성공")
    void activate_firstCall_shouldReturnTrue() {
        boolean result = cooldownManager.activate();
        assertThat(result).isTrue();
        assertThat(cooldownManager.isActive()).isTrue();
    }

    @Test
    @DisplayName("Cooldown 활성화 중 재호출 시 false 반환 (중복 Scale-out 방지)")
    void activate_whileLocked_shouldReturnFalse() {
        cooldownManager.activate(); // 첫 번째 Lock 획득

        boolean secondAttempt = cooldownManager.activate(); // 두 번째 시도
        assertThat(secondAttempt).isFalse();
        assertThat(cooldownManager.isActive()).isTrue();
    }

    @Test
    @DisplayName("unlock() 후 다시 activate() 가능")
    void unlock_thenActivate_shouldWork() {
        cooldownManager.activate();
        assertThat(cooldownManager.isActive()).isTrue();

        cooldownManager.unlock();
        assertThat(cooldownManager.isActive()).isFalse();

        boolean afterUnlock = cooldownManager.activate();
        assertThat(afterUnlock).isTrue();
        assertThat(cooldownManager.isActive()).isTrue();
    }

    @Test
    @DisplayName("비활성 상태에서 getStatus() → active=false")
    void getStatus_whenInactive_shouldReturnInactive() {
        CooldownManager.CooldownStatus status = cooldownManager.getStatus();
        assertThat(status.active()).isFalse();
        assertThat(status.remainingSeconds()).isEqualTo(0);
    }

    @Test
    @DisplayName("활성 상태에서 getStatus() → active=true 및 시간 정보 포함")
    void getStatus_whenActive_shouldReturnActiveWithTimes() {
        cooldownManager.activate();
        CooldownManager.CooldownStatus status = cooldownManager.getStatus();

        assertThat(status.active()).isTrue();
        assertThat(status.lockedAt()).isNotNull();
        assertThat(status.unlockAt()).isNotNull();
        assertThat(status.remainingSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("동시 10개 요청 중 단 1개만 Lock 획득 (Thread-Safety 검증)")
    void activate_concurrentRequests_onlyOneShouldSucceed() throws InterruptedException {
        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드 동시 출발
                    if (cooldownManager.activate()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 동시 출발 신호
        doneLatch.await();
        executor.shutdown();

        // 10개 중 정확히 1개만 Lock 획득 성공
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(cooldownManager.isActive()).isTrue();
    }
}
