package com.viralguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Viral-Guard AIOps - Spring Boot 중앙 제어 컨트롤러
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan  // ScaleOutProperties 자동 스캔
public class ViralGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(ViralGuardApplication.class, args);
    }
}
