package com.viralguard.service;

import com.viralguard.dto.AlertReceivedResponseDTO;
import com.viralguard.dto.ScaleOutEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final ScaleOutExecutor scaleOutExecutor;
    private final GrafanaAnnotationService grafanaAnnotationService;

    public AlertReceivedResponseDTO handleScaleOutAlert(ScaleOutEventDTO event) {
        log.info("[AlertService] Scale-out 이벤트 수신 | model={}, predicted={}, instanceId={}, vmName={}",
                event.getTrigger().getModelType(),
                event.getTrigger().getPredictedValue(),
                event.getScaleOutResult().getInstanceId(),
                event.getScaleOutResult().getVmName());

        scaleOutExecutor.execute(event);
        grafanaAnnotationService.registerAnnotation(event);

        return AlertReceivedResponseDTO.received(event.getScaleOutResult().getInstanceId());
    }
}
