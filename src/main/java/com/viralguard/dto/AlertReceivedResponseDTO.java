package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class AlertReceivedResponseDTO {

    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("instance_id")
    private String instanceId;

    @JsonProperty("timestamp")
    private String timestamp;

    public static AlertReceivedResponseDTO received(String instanceId) {
        return AlertReceivedResponseDTO.builder()
                .status("RECEIVED")
                .message("Scale-out event recorded successfully")
                .instanceId(instanceId)
                .timestamp(Instant.now().toString())
                .build();
    }

    public static AlertReceivedResponseDTO error(String reason) {
        return AlertReceivedResponseDTO.builder()
                .status("ERROR")
                .message(reason)
                .instanceId(null)
                .timestamp(Instant.now().toString())
                .build();
    }
}
