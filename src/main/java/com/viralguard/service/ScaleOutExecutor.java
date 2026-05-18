package com.viralguard.service;

import com.viralguard.dto.ScaleOutEventDTO;
import com.viralguard.dto.ScaleOutResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScaleOutExecutor {

    private final ScaleOutHistoryService historyService;

    public void execute(ScaleOutEventDTO event) {
        log.info("[Executor] Scale-out 이벤트 저장 | model={}, sourceInstanceId={}, newInstanceId={}, vmName={}",
                event.getTrigger().getModelType(),
                event.getSourceInstanceId(),
                event.getScaleOutResult().getInstanceId(),
                event.getScaleOutResult().getVmName());

        ScaleOutResult result = ScaleOutResult.fromEvent(event);
        historyService.record(result);

        log.info("[Executor] 이력 저장 완료 | newInstanceId={}", result.newInstanceId());
    }
}
