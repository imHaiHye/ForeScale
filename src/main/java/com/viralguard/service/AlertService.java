package com.viralguard.service;

import com.viralguard.component.CooldownManager;
import com.viralguard.config.ScaleOutProperties;
import com.viralguard.dto.ScaleOutRequestDTO;
import com.viralguard.dto.ScaleOutResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Scale-out 알람 처리 비즈니스 로직.
 *
 * 처리 흐름:
 *   1. Cooldown 활성화 여부 확인 -> 활성 시 409 즉시 반환
 *   2. AtomicBoolean CAS로 Cooldown Lock 획득
 *   3. ScaleOutExecutor에게 비동기 실행 위임
 *   4. 즉시 200 OK 반환 (스크립트 완료 대기 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final CooldownManager cooldownManager;
    private final ScaleOutExecutor scaleOutExecutor;
    private final ScaleOutProperties props;

    public ScaleOutResponseDTO handleScaleOutAlert(ScaleOutRequestDTO request) {
        log.info("[AlertService] 알람 수신 | model={}, predicted={}, threshold={}, predictedAt={}",
                request.getModelType(),
                request.getPredictedValue(),
                request.getThreshold(),
                request.getPredictedAt());

        // Step 1: Cooldown 확인
        if (cooldownManager.isActive()) {
            CooldownManager.CooldownStatus status = cooldownManager.getStatus();
            log.warn("[AlertService] Cooldown 활성 중. 알람 무시. unlockAt={}, remaining={}s",
                    status.unlockAt(), status.remainingSeconds());
            return ScaleOutResponseDTO.cooldownActive(status.unlockAt());
        }

        // Step 2: Lock 획득 (Race Condition 방어)
        boolean lockAcquired = cooldownManager.activate();
        if (!lockAcquired) {
            CooldownManager.CooldownStatus status = cooldownManager.getStatus();
            log.warn("[AlertService] Lock 획득 실패 (동시 요청 race). 알람 무시.");
            return ScaleOutResponseDTO.cooldownActive(status.unlockAt());
        }

        boolean isMock = props.getScaleOut().isMockMode();
        log.info("[AlertService] Cooldown Lock 획득. Scale-out 실행 시작. model={}, mockMode={}",
                request.getModelType(), isMock);

        // Step 3: 비동기 실행 위임
        scaleOutExecutor.execute(request);

        // Step 4: 즉시 200 반환
        return ScaleOutResponseDTO.triggered(request.getModelType());
    }
}
