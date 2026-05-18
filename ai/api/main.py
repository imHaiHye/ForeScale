"""
FastAPI 서버 (최종 통합본)
# v4.0.0 — B안 확정: FastAPI 직접 실행 + D팀 결과 통보

엔드포인트:
  GET  /health              - 헬스 체크
  POST /predict             - CPU + Traffic 병렬 예측 + 급격 감지 + 자동 scale-out

핵심 흐름 (한 번의 /predict 호출):
  1. 입력으로 받은 최근 시계열 데이터로 Prophet 두 모델 학습
  2. 3분 뒤 예측 → 임계치 비교
  3. SpikeDetector로 직전 1분 변화율 30%↑ 체크 (병행)
  4. 결과 통합:
        any_trigger = (Prophet_CPU 위험) OR (Prophet_Traffic 위험) OR (Spike 감지)
  5. any_trigger=True면 provisioner.scale_out() 직접 호출
  6. scale-out 완료 후 D팀 Spring Boot로 결과 통보 POST 전송
  7. 응답 반환

실행:
    uvicorn main:app --host 0.0.0.0 --port 8000
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import List, Optional
import logging
import pandas as pd

import sys
import os
# ai/ 디렉토리를 path에 추가
_AI_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
sys.path.insert(0, _AI_DIR)
# ForeScale 루트(recovery/ 등)를 path에 추가 (ai-vm 기준 /home/ubuntu/ForeScale/)
_ROOT_DIR = os.path.join(_AI_DIR, '..')
sys.path.insert(0, _ROOT_DIR)

from model.predictor import Predictor
from model.spike_detector import SpikeDetector
from api.alert_sender import AlertSender
from recovery.provisioner import scale_out

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

app = FastAPI(title='ForeScale AI Inference API', version='3.0.0')

# ============================================================
# 설정값 (운영에서 조정)
# ============================================================
CONFIG = {
    'cpu_threshold': 80.0,           # CPU % 임계치
    'traffic_threshold': 30 * 1024,  # Traffic bytes/s 임계치
    'spike_threshold_pct': 30.0,     # 급격 감지 변화율 임계치
    'spike_baseline_window': 5,      # 급격 감지 baseline 윈도우 크기
    'predict_minutes': 3,            # 몇 분 뒤 예측
    'd_team_url': 'http://10.0.2.40:8080/api/v1/alerts/scale-out',
}

# 전역 인스턴스 (요청마다 새로 만들지 않고 재사용)
sender = AlertSender(d_team_url=CONFIG['d_team_url'])
spike_detector = SpikeDetector(
    threshold_pct=CONFIG['spike_threshold_pct'],
    baseline_window=CONFIG['spike_baseline_window'],
)


# ============================================================
# 요청/응답 스키마
# ============================================================
class TimeseriesPoint(BaseModel):
    ds: str = Field(..., description='ISO 8601 timestamp, e.g. 2026-05-11T14:23:00Z')
    y: float = Field(..., description='해당 시점의 메트릭 값')


class PredictRequest(BaseModel):
    """
    /predict 요청
    - cpu_series: CPU 사용률 시계열 (%)
    - traffic_series: 트래픽 시계열 (bytes/s)
    - source_instance_id: 알람 발생 EC2 ID (옵션)
    """
    cpu_series: List[TimeseriesPoint]
    traffic_series: List[TimeseriesPoint]
    source_instance_id: Optional[str] = None


class PredictResponse(BaseModel):
    """
    /predict 응답 (디버깅/모니터링용 — 어떤 판단을 했는지 투명하게 공개)
    """
    # Prophet 예측 결과
    pred_cpu: float
    pred_traffic: float
    cpu_trigger: bool
    traffic_trigger: bool
    # 급격 감지 결과
    spike_cpu: bool
    spike_traffic: bool
    # 최종 OR 트리거
    any_trigger: bool
    # 사유 (Grafana 어노테이션에 표시)
    reason: str
    # 알람 전송 결과 (D팀에서 받은 응답)
    alerts_sent: List[dict]


# ============================================================
# 엔드포인트
# ============================================================
@app.get('/health')
def health():
    return {'status': 'ok', 'version': '3.0.0'}


@app.post('/predict', response_model=PredictResponse)
def predict(req: PredictRequest):
    """
    핵심 엔드포인트:
      1. Prophet 두 모델 학습/추론
      2. 급격 감지 병행
      3. OR 조건으로 트리거 결정
      4. 위험하면 D팀에 자동 알람 전송
    """
    # ---- 입력 검증 ----
    if len(req.cpu_series) < 10 or len(req.traffic_series) < 10:
        raise HTTPException(
            status_code=400,
            detail='cpu_series and traffic_series each need >= 10 points'
        )

    # ---- DataFrame 변환 ----
    cpu_df = pd.DataFrame([{'ds': p.ds, 'y': p.y} for p in req.cpu_series])
    cpu_df['ds'] = pd.to_datetime(cpu_df['ds'])
    traffic_df = pd.DataFrame([{'ds': p.ds, 'y': p.y} for p in req.traffic_series])
    traffic_df['ds'] = pd.to_datetime(traffic_df['ds'])

    # ---- 1) Prophet 병렬 추론 ----
    predictor = Predictor(
        cpu_threshold=CONFIG['cpu_threshold'],
        traffic_threshold=CONFIG['traffic_threshold'],
    )
    predictor.fit_cpu(cpu_df)
    predictor.fit_traffic(traffic_df)
    pred_result = predictor.predict_all(predict_minutes=CONFIG['predict_minutes'])

    # ---- 2) 급격 감지 병행 ----
    cpu_spike = spike_detector.check(cpu_df['y'].tolist())
    traffic_spike = spike_detector.check(traffic_df['y'].tolist())

    # ---- 3) OR 트리거 결정 ----
    any_trigger = bool(
        pred_result['any_trigger']
        or cpu_spike['spike']
        or traffic_spike['spike']
    )

    # 사유 조립
    reasons = []
    if pred_result['cpu']['trigger']:
        reasons.append(f'[Prophet-CPU] {pred_result["cpu"]["reason"]}')
    if pred_result['traffic']['trigger']:
        reasons.append(f'[Prophet-TRAFFIC] {pred_result["traffic"]["reason"]}')
    if cpu_spike['spike']:
        reasons.append(f'[Spike-CPU] {cpu_spike["reason"]}')
    if traffic_spike['spike']:
        reasons.append(f'[Spike-TRAFFIC] {traffic_spike["reason"]}')
    reason_text = ' | '.join(reasons) if reasons else 'all normal'

    logger.info(f'[/predict] any_trigger={any_trigger} | {reason_text}')

    # ---- 4) 위험 시 직접 scale_out() 호출 → 완료 후 D팀 결과 통보 ----
    alerts_sent = []
    if any_trigger:
        try:
            scale_out_result = scale_out(reason=reason_text)
            logger.info(f'[/predict] scale_out completed: {scale_out_result}')
            alerts_sent = [sender.notify(
                pred_result=pred_result,
                scale_out_result=scale_out_result,
                source_instance_id=req.source_instance_id,
            )]
        except Exception as e:
            logger.error(f'[/predict] scale_out failed: {e}')
            alerts_sent = [{'status': 'SCALE_OUT_FAILED', 'reason': str(e)}]

    # ---- 5) 응답 ----
    return PredictResponse(
        pred_cpu=pred_result['cpu']['pred_value'],
        pred_traffic=pred_result['traffic']['pred_value'],
        cpu_trigger=pred_result['cpu']['trigger'],
        traffic_trigger=pred_result['traffic']['trigger'],
        spike_cpu=cpu_spike['spike'],
        spike_traffic=traffic_spike['spike'],
        any_trigger=any_trigger,
        reason=reason_text,
        alerts_sent=alerts_sent,
    )
