package com.viralguard.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "viral-guard")
public class ScaleOutProperties {

    private ScaleOut scaleOut = new ScaleOut();
    private Grafana grafana = new Grafana();

    @Getter
    @Setter
    public static class ScaleOut {
        private String scriptPath = "/opt/viralguard/scripts/scale_out.py";
        private String pythonPath = "/usr/bin/python3";
        private int timeoutSeconds = 30;
        private boolean mockMode = true;
        private String awsRegion = "ap-northeast-2";
        private String workingDirectory = "/opt/viralguard/scripts";
        private boolean unlockOnFailure = true;
    }

    @Getter
    @Setter
    public static class Grafana {
        private String url = "http://10.0.2.202:3000";
        private String username = "admin";
        private String password = "admin";
    }
}
