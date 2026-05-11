# 🟢 D팀(황준하)에게 — C팀이 보내는 알람 구현 현황

## ✅ 합의된 스펙 그대로 구현 완료

`api-spec-for-cteam.md`에 정의해주신 스펙대로 정확히 보냅니다:

### 엔드포인트
```
POST http://10.0.2.40:8080/api/v1/alerts/scale-out
Content-Type: application/json
```

### 전송 페이로드 예시 (실제 동작 검증됨)

**CPU 알람**
```json
{
  "model_type": "CPU",
  "predicted_value": 87.3,
  "threshold": 80.0,
  "predicted_at": "2026-05-11T14:23:00Z",
  "severity": "CRITICAL",
  "source_instance_id": "i-03512866b1c1ba03e"
}
```

**TRAFFIC 알람**
```json
{
  "model_type": "TRAFFIC",
  "predicted_value": 46765.7,
  "threshold": 30720.0,
  "predicted_at": "2026-05-06T07:07:00Z",
  "severity": "CRITICAL",
  "source_instance_id": "i-03512866b1c1ba03e"
}
```

### 응답 처리
C팀은 D팀 응답 상태별로 다음과 같이 처리합니다:

| HTTP | d_status | C팀 처리 |
|---|---|---|
| 200 | `TRIGGERED` | 정상 전송 완료, 로그 기록 |
| 409 | `COOLDOWN_ACTIVE` | 알람 무시됨, **재시도 안 함** (D팀 cooldown 5분 신뢰) |
| 4xx/5xx | `ERROR` | 에러 로그 기록 |
| 네트워크 에러 | `NETWORK_ERROR` (자체 정의) | 에러 로그 기록, 다음 사이클에 재시도 |

---

## ⚠️ 알아두실 점 두 가지

### 1) CPU와 TRAFFIC은 **따로 전송**
둘 다 동시에 위험하면 POST를 **2번** 호출합니다 (간격 ~수십 ms). 두 번째 호출은 D팀의 5분 cooldown에 걸려서 **409 응답을 돌려받을 것**으로 예상돼요. 이게 정상 동작 시나리오 중 하나입니다.

### 2) 급격 감지(Spike)도 같은 엔드포인트로 보냄
규칙 기반 급격 감지(1분 변화율 30%↑)가 잡힌 경우에도 동일 스펙으로 보냅니다. 다만 `predicted_value`는 **예측값이 아닌 현재 측정값**, `severity`는 항상 `CRITICAL`이에요.

→ D팀 입장에서 처리 로직은 동일해요. "model_type별로 임계치 초과 알람이 왔으니 Scale-out 트리거" 그대로.

---

## 🤝 통합 시 확인할 것

- D팀 Spring Boot가 `10.0.2.40:8080`에서 정상 LISTEN 중인지
- 보안 그룹에서 AI VM → App VM 8080 포트 통신 허용 여부
- D팀 cooldown 락이 어디에 저장되는지 (메모리? DB?) — 컨트롤러 재시작 시 cooldown 초기화되는지 알려주시면 C팀 재시도 로직에 반영하겠습니다.

---

## 🧪 D팀 로컬 테스트용 페이로드 샘플

테스트 시 다음 curl로 C팀이 보내는 것과 동일한 요청을 재현할 수 있어요:

```bash
curl -X POST http://localhost:8080/api/v1/alerts/scale-out \
  -H "Content-Type: application/json" \
  -d '{
    "model_type": "CPU",
    "predicted_value": 87.3,
    "threshold": 80.0,
    "predicted_at": "2026-05-11T14:23:00Z",
    "severity": "CRITICAL",
    "source_instance_id": "i-03512866b1c1ba03e"
  }'
```
