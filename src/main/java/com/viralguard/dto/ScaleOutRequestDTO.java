package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * FastAPI(AI VM)가 보내는 Scale-out 알람 요청 페이로드.
 *
 * 예시 JSON:
 * {
 *   "model_type": "CPU",
 *   "predicted_value": 87.3,
 *   "threshold": 85.0,
 *   "predicted_at": "2024-01-15T14:23:00Z",
 *   "source_instance_id": "i-0abc123def456",
 *   "severity": "CRITICAL"
 * }
 */
@Getter
@NoArgsConstructor
@ToString
public class ScaleOutRequestDTO {

    /**
     * 예측 모델 종류.
     * - "CPU"     : node_cpu_seconds_total 기반 Prophet 예측
     * - "TRAFFIC" : node_network_receive_bytes 기반 Prophet 예측
     */
    @NotBlank(message = "model_type은 필수입니다.")
    @JsonProperty("model_type")
    private String modelType;

    /**
     * 예측된 메트릭 값.
     * - CPU 모델: CPU 사용률 (%)
     * - TRAFFIC 모델: 수신 바이트/초 (bytes/s)
     */
    @NotNull(message = "predicted_value는 필수입니다.")
    @Positive(message = "predicted_value는 양수여야 합니다.")
    @JsonProperty("predicted_value")
    private Double predictedValue;

    /**
     * 임계치 값. 이 값을 예측값이 초과할 것으로 판단되어 알람 발생.
     */
    @NotNull(message = "threshold는 필수입니다.")
    @JsonProperty("threshold")
    private Double threshold;

    /**
     * 임계치 초과 예상 시각 (ISO 8601 형식).
     * AI가 "약 3분 뒤"를 예측하여 계산한 절대 시각.
     */
    @NotBlank(message = "predicted_at은 필수입니다.")
    @JsonProperty("predicted_at")
    private String predictedAt;

    /**
     * 알람 발생 원천 EC2 Instance ID.
     * boto3 스크립트에 전달되어 신규 인스턴스 프로비저닝 참고용으로 사용.
     * 선택 필드 (null 허용).
     */
    @JsonProperty("source_instance_id")
    private String sourceInstanceId;

    /**
     * 심각도 레벨.
     * - "WARNING"  : 경계 수준 (조기 감지)
     * - "CRITICAL" : 즉시 Scale-out 필요
     * 선택 필드. 현재 MVP에서는 모든 알람을 Scale-out 트리거로 처리.
     */
    @JsonProperty("severity")
    private String severity;
}
