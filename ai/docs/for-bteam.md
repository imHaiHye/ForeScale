# 🔵 B팀(최정연)에게 — C팀이 데이터를 어떻게 쓰는지

## ✅ 받은 데이터 활용 현황

`sudden_traffic.csv` (JMeter 결과, 17만 줄) 잘 받았습니다. 다음과 같이 쓰고 있어요:

### 변환 과정
1. `timeStamp` (밀리초 epoch) → 1분 단위로 그룹핑
2. 각 그룹에서 `bytes` 컬럼 합산 → 60초로 나눠서 **bytes/s** 단위로 변환
3. → Prophet 학습 데이터 (`ds`, `y`)

→ 결과: 평균 **46.8 KB/s**, 최대 **51 KB/s** 트래픽 시계열 16개 포인트

### 왜 bytes/s 단위로 변환했나?
운영 환경에서 B팀이 Prometheus로 쏴줄 메트릭이 `node_network_receive_bytes_total` 이라서 **단위를 미리 맞춰뒀어요**. 나중에 진짜 Prometheus 데이터로 바꿔도 C팀 코드 수정 거의 없음.

---

## 📥 추가로 받고 싶은 데이터 (3주차 ~ 4주차 중)

현재 `sudden_traffic.csv`는 **처음부터 끝까지 부하 상황**이에요. 그래서 "정상 트래픽이 어떤 모양인지"를 Prophet이 학습할 데이터가 없어요. 두 가지 시나리오를 추가로 부탁드려요:

### 1) 평상시 시나리오 (Baseline)
- 약 10~20 req/s 수준으로 **30분 이상** 꾸준히
- 목적: Prophet이 "정상 상태"를 학습 → false positive 줄이기

### 2) 점진적 부하 시나리오 (Gradual Spike)
- 10 req/s → 5분에 걸쳐 200 req/s 로 서서히 증가
- 목적: Prophet의 추세 학습 능력 검증 (Spike Detector랑 분리 검증)

### 파일 포맷
- **현재처럼 JMeter CSV 그대로** 주시면 됩니다. C팀에서 변환 코드 동일 적용.
- 파일명만 구분: `baseline.csv`, `gradual_traffic.csv` 정도?

---

## 🛠️ 3~4주차 통합 시 메트릭 수집

`prometheus.yml`에 추가하실 항목 (계획 문서에 이미 적힌 대로):

```yaml
scrape_configs:
  - job_name: 'node_network'
    static_configs:
      - targets: ['app-vm-1:9100', 'app-vm-2:9100']
```

C팀 FastAPI가 Prometheus API (`/api/v1/query_range`)를 호출해서 다음 두 메트릭을 시계열로 가져와요:
- `100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)` → CPU %
- `rate(node_network_receive_bytes_total[1m])` → bytes/s

⇒ **C팀에서 Prometheus 쿼리는 우리가 알아서 할 테니, B팀은 메트릭이 잘 노출되는지만 확인해주세요.**
