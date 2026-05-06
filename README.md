# Viral-Guard AIOps — Backend (Spring Boot)

> **담당자**: 황준하 (Backend D)  
> **역할**: Spring Boot 중앙 제어 컨트롤러 — AI 알람 Webhook 수신 + AWS Scale-out 트리거

---

## 주차별 진행 상태

| 주차 | Phase | 상태 | 주요 구현 |
|------|-------|------|-----------|
| Week 1 | 컨트롤러 뼈대 + Mock AI 수신 | 완료 | AlertController, CooldownManager, DTO |
| Week 2 | boto3 연동 + 실행 흐름 완성 | 완료 | ProcessBuilder, StreamGobbler, 이력 API |
| Week 3 | Cooldown 완성 + 통합 테스트 | 예정 | |
| Week 4 | 전체 파이프라인 데모 | 예정 | |

---

## 프로젝트 구조 (Week 2 기준)

```
src/main/java/com/viralguard/
├── ViralGuardApplication.java
├── config/
│   ├── AsyncConfig.java
│   ├── GlobalExceptionHandler.java
│   └── ScaleOutProperties.java         [NEW] @ConfigurationProperties
├── controller/
│   ├── AlertController.java
│   ├── HealthController.java
│   └── ScaleOutController.java         [NEW] 이력/통계 API
├── service/
│   ├── AlertService.java
│   ├── ScaleOutExecutor.java           [UPDATED] 실제 ProcessBuilder 완성
│   └── ScaleOutHistoryService.java     [NEW] 인메모리 이력 관리
├── dto/
│   ├── ScaleOutRequestDTO.java
│   ├── ScaleOutResponseDTO.java
│   └── ScaleOutResult.java             [NEW] 스크립트 실행 결과 레코드
└── component/
    ├── CooldownManager.java            [UPDATED]
    └── ProcessStreamGobbler.java       [NEW] 데드락 방지 스트림 소비
```

---

## 실행 방법

```bash
# 빌드
./gradlew build

# Mock 모드 실행 (기본값 - boto3 없이 테스트 가능)
./gradlew bootRun

# 실제 boto3 연동 모드 (EC2 배포 시)
VIRAL_GUARD_SCALE_OUT_MOCK_MODE=false ./gradlew bootRun

# 전체 테스트
./gradlew test
```

---

## Week 2 핵심 구현: ProcessBuilder 흐름

```
FastAPI -> POST /api/v1/alerts/scale-out
              |
         AlertService (Cooldown 체크 -> Lock -> 즉시 200 반환)
              |
         @Async ScaleOutExecutor.executeReal()
              |
         [1] 스크립트 파일 검증
         [2] ProcessBuilder: python3 scale_out.py {instanceId} {modelType}
         [3] pb.start() -> 프로세스 기동
         [4] ProcessStreamGobbler x2 (stdout/stderr 동시 소비 -> 데드락 방지)
         [5] process.waitFor(30s)
         [6] exitCode == 0 -> SUCCESS, else -> FAILED + Cooldown 해제
         [7] ScaleOutHistoryService.record(result)
```

---

## 테스트 curl 명령어

```bash
# 알람 수신
curl -X POST http://localhost:8080/api/v1/alerts/scale-out \
  -H "Content-Type: application/json" \
  -d '{"model_type":"CPU","predicted_value":87.3,"threshold":85.0,"predicted_at":"2024-01-15T14:23:00Z"}'

# Week 2 신규 API
curl http://localhost:8080/api/v1/scale-out/history
curl http://localhost:8080/api/v1/scale-out/latest
curl http://localhost:8080/api/v1/scale-out/stats
curl http://localhost:8080/api/v1/status/cooldown
curl http://localhost:8080/health
```

---

## 팀 간 연동 인터페이스

Spring Boot -> boto3 스크립트 실행 계약:
```bash
python3 /opt/viralguard/scripts/scale_out.py {instanceId} {modelType}
# 환경변수: AWS_DEFAULT_REGION=ap-northeast-2
# exitCode 0 = 성공 / 그 외 = 실패
```
