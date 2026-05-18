package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TriggerDTO {

    @NotBlank
    @JsonProperty("model_type")
    private String modelType;

    @JsonProperty("predicted_value")
    private double predictedValue;

    @JsonProperty("threshold")
    private double threshold;

    @JsonProperty("predicted_at")
    private String predictedAt;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("reason")
    private String reason;
}
