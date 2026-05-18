package com.viralguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ScaleOutResultPayloadDTO {

    @NotBlank
    @JsonProperty("instance_id")
    private String instanceId;

    @JsonProperty("private_ip")
    private String privateIp;

    @JsonProperty("vm_name")
    private String vmName;

    @JsonProperty("status")
    private String status;
}
