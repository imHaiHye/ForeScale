package com.viralguard.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 viral-guard 설정을 타입 안전하게 바인딩.
 *
 * @Value 어노테이션을 남발하는 대신 하나의 설정 클래스로 관리.
 * Week 2에서 추가된 환경변수(AWS 자격증명 전달 등)까지 포함.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "viral-guard")
public class ScaleOutProperties {

    private ScaleOut scaleOut = new ScaleOut();
    private Cooldown cooldown = new Cooldown();

    @Getter
    @Setter
    public static class ScaleOut {
        /** boto3 Python 스크립트 절대 경로 */
        private String scriptPath = "/opt/viralguard/scripts/scale_out.py";

        /** Python 실행파일 경로 */
        private String pythonPath = "/usr/bin/python3";

        /** 스크립트 최대 실행 대기시간 (초). 초과 시 강제 종료. */
        private int timeoutSeconds = 30;

        /**
         * Mock 모드 여부.
         * true  → 실제 boto3 호출 없이 성공 시뮬레이션 (로컬 개발용)
         * false → ProcessBuilder로 실제 Python 스크립트 실행
         */
        private boolean mockMode = true;

        /**
         * 스크립트 실행 시 주입할 AWS 리전.
         * boto3가 환경변수 AWS_DEFAULT_REGION을 통해 읽음.
         */
        private String awsRegion = "ap-northeast-2";

        /**
         * 스크립트 작업 디렉터리.
         * 스크립트 내에서 상대 경로를 사용하는 경우를 위해 지정.
         */
        private String workingDirectory = "/opt/viralguard/scripts";

        /**
         * 스크립트 실패 시 Cooldown을 즉시 해제할지 여부.
         * true  → 실패하면 Cooldown 해제해서 다음 알람이 재시도 가능
         * false → 실패해도 Cooldown 유지 (과도한 재시도 방지)
         */
        private boolean unlockOnFailure = true;
    }

    @Getter
    @Setter
    public static class Cooldown {
        /** Scale-out 실행 후 Cooldown 유지 시간 (초). EC2 Cold Start 고려 기본 5분. */
        private long durationSeconds = 300;
    }
}
