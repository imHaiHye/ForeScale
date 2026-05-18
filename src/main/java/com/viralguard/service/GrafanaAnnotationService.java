package com.viralguard.service;

import com.viralguard.config.ScaleOutProperties;
import com.viralguard.dto.ScaleOutEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrafanaAnnotationService {

    private final ScaleOutProperties props;
    private final RestTemplate restTemplate;

    public void registerAnnotation(ScaleOutEventDTO event) {
        ScaleOutProperties.Grafana grafana = props.getGrafana();

        String text = String.format(
                "Scale-out | model=%s | predicted=%.1f | VM=%s | source=%s",
                event.getTrigger().getModelType(),
                event.getTrigger().getPredictedValue(),
                event.getScaleOutResult().getVmName(),
                event.getSourceInstanceId()
        );

        long timeMs = parseToEpochMilli(event.getExecutedAt());

        Map<String, Object> body = new HashMap<>();
        body.put("time", timeMs);
        body.put("timeEnd", timeMs);
        body.put("isRegion", false);
        body.put("tags", List.of("scale-out", "forescale"));
        body.put("text", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, basicAuth(grafana.getUsername(), grafana.getPassword()));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            String url = grafana.getUrl() + "/api/annotations";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            log.info("[Grafana] 어노테이션 등록 완료. status={}, text={}", response.getStatusCode(), text);
        } catch (RestClientException e) {
            log.warn("[Grafana] 어노테이션 등록 실패 (무시). reason={}", e.getMessage());
        }
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private long parseToEpochMilli(String isoString) {
        try {
            return Instant.parse(isoString).toEpochMilli();
        } catch (Exception e) {
            return Instant.now().toEpochMilli();
        }
    }
}
