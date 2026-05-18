package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ScaleOutEventDTO {

    @NotBlank
    @JsonProperty("event_type")
    private String eventType;

    @NotNull
    @Valid
    @JsonProperty("trigger")
    private TriggerDTO trigger;

    @NotNull
    @Valid
    @JsonProperty("scale_out_result")
    private ScaleOutResultPayloadDTO scaleOutResult;

    @NotBlank
    @JsonProperty("source_instance_id")
    private String sourceInstanceId;

    @JsonProperty("executed_at")
    private String executedAt;
}
