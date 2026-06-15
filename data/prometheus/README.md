# 📊 Viral-Guard — Data

## 팀 정보
- **팀원:** 최정연 
- **역할:** 메트릭 수집 인프라 구축 및 부하 테스트 시나리오 설계

---

## 📁 디렉토리 구조

```
B팀/
├── jmeter/
│   └── scenarios/
│       └── viral_spike_sudden.jmx       # 급격 부하 시나리오
├── prometheus/
│   ├── prometheus.yml                    # Prometheus 설정 파일
│   └── file_sd.json                      # 동적 타겟 감지 설정
└── README.md
```

---

## 1. JMeter 부하 테스트 시나리오

### 시나리오: 급격 부하 (`viral_spike_sudden.jmx`)

| 설정 항목 | 값 | 설명 |
|---|---|---|
| Number of Threads | 100 | 가상 유저 100명 |
| Ramp-up Period | 10초 | 10초 안에 100명 동시 접속 |
| Loop Count | Infinite | 무한 반복 |
| Protocol | http | - |
| Target | `10.0.2.40:8080` | 앱 서버 1 |
| Path | `/health` | Spring Boot 헬스 엔드포인트 |
| Constant Timer | 500ms | 요청 간격 |

### 실행 방법

**Windows (로컬 PC):**
```bash
# 포트 포워딩 먼저 실행 (Private 서브넷 접근용)
aws ssm start-session \
  --target i-03512866b1c1ba03e \
  --document-name AWS-StartPortForwardingSession \
  --parameters portNumber=8080,localPortNumber=8080 \
  --region ap-northeast-2

# JMeter GUI 실행
D:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat
```

**앱 서버 Spring Boot 실행:**
```bash
# 앱 서버 접속
aws ssm start-session --target i-03512866b1c1ba03e --region ap-northeast-2

# Spring Boot 실행
sudo su ubuntu
cd /home/ubuntu/app
nohup java -jar forescale-backend-0.0.1-SNAPSHOT.jar > app.log 2>&1 &

# 실행 확인 (10초 후)
tail app.log

# 테스트 종료 후 반드시 종료
pkill -f forescale-backend-0.0.1-SNAPSHOT.jar
```

### 테스트 결과 예시

| 항목 | 값 |
|---|---|
| 총 요청 수 | 191,086 |
| 평균 응답시간 | 19ms |
| Throughput | 187.7/sec |
| Error % | 11.17% |

> 결과 CSV: `D:\jmeter\results\sudden_traffic.csv` → C팀에 전달

---

## 2. Prometheus 설정

### 서버 정보

| 역할 | 서버 | IP |
|---|---|---|
| 모니터링 서버 | forescale-monitoring | `10.0.2.202` |
| 앱 서버 1 | forescale-app-vm-1 | `10.0.2.40` |
| 앱 서버 2 | forescale-app-vm-2 | `10.0.2.218` |

### prometheus.yml

```yaml
global:
  scrape_interval: 10s
  evaluation_interval: 10s

scrape_configs:
  - job_name: 'node_dynamic'
    file_sd_configs:
      - files:
          - '/opt/prometheus/file_sd.json'
        refresh_interval: 15s
  - job_name: 'pushgateway'
    honor_labels: true
    static_configs:
      - targets: ['10.0.2.148:9091']
```

### file_sd.json

```json
[
  {
    "targets": ["10.0.2.40:9100"],
    "labels": {
      "instance": "app-server-1",
      "env": "production"
    }
  },
  {
    "targets": ["10.0.2.218:9100"],
    "labels": {
      "instance": "app-server-2",
      "env": "production"
    }
  }
]
```

> EC2 추가 시 이 파일에 IP만 추가하면 Prometheus가 자동 감지 (재시작 불필요)

---

## 3. Node Exporter

각 앱 서버에 Node Exporter v1.8.1 설치 및 systemd 서비스 등록

- **포트:** `9100`
- **수집 메트릭:** CPU, 메모리, 네트워크 등
- **서비스 확인:** `sudo systemctl status node_exporter`

### 주요 수집 메트릭

| 메트릭 | 설명 | 용도 |
|---|---|---|
| `node_cpu_seconds_total` | CPU 사용률 | Prophet_CPU 학습 데이터 |
| `node_network_receive_bytes_total` | 네트워크 수신 바이트 | Prophet_Traffic 학습 데이터 |
| `node_memory_MemAvailable_bytes` | 가용 메모리 | 서버 상태 모니터링 |

### Prometheus에서 쿼리 예시

```promql
# CPU 사용률
100 - (avg by(instance)(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)

# 네트워크 트래픽 유입량 (초당 바이트)
rate(node_network_receive_bytes_total{device="ens5"}[1m])
```
