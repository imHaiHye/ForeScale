"""
Predictor: CPU + Traffic 병렬 추론 통합 클래스

CPU(Prophet_CPU)와 Traffic(Prophet_Traffic)을 동시에 돌려서
하나의 결과로 모아주는 모듈.

사용 예:
    predictor = Predictor()
    predictor.fit_cpu(cpu_df)
    predictor.fit_traffic(traffic_df)
    result = predictor.predict_all(predict_minutes=3)
    # result = {
    #   'cpu':     {pred_value, threshold, trigger, reason, ...},
    #   'traffic': {pred_value, threshold, trigger, reason, ...},
    #   'any_trigger': True/False,
    # }
"""

import pandas as pd
from prophet import Prophet
ubuntu@ip-10-0-2-148:~/ForeScale/ai/model$ grep -n "stable\|counter\|shutdown\|terminate\|scale_in\|헬스" ~/ForeScale/ai/api/main.py
26:from recovery.provisioner import scale_out, scale_in, get_recovery_vms
45:    'stable_threshold':       5,
56:stable_counter = 0
108:    global _is_scaling, stable_counter
159:        stable_counter = 0
177:        stable_counter += 1
178:        logger.info('[Scheduler] 안정 카운터: ' + str(stable_counter) + '/' + str(CONFIG['stable_threshold']))
180:        if stable_counter >= CONFIG['stable_threshold']:
183:                logger.info('[Scheduler] scale_in 실행: ' + str(len(recovery_vms)) + '대')
186:                        scale_in(vm['instance_id'])
187:                        logger.info('[Scheduler] scale_in 완료: ' + vm['instance_id'])
189:                        logger.error('[Scheduler] scale_in 실패: ' + str(e))
191:                logger.info('[Scheduler] recovery VM 없음, scale_in 스킵')
192:            stable_counter = 0
220:        'stable_counter': stable_counter,
ubuntu@ip-10-0-2-148:~/ForeScale/ai/model$ cd ~/ForeScale && git remote -v && git config user.name && git config user.email
origin  https://github.com/imHaiHye/ForeScale.git (fetch)
origin  https://github.com/imHaiHye/ForeScale.git (push)
ubuntu@ip-10-0-2-148:~/ForeScale$ git config user.name "imHaiHye"
error: could not lock config file .git/config: Permission denied
ubuntu@ip-10-0-2-148:~/ForeScale$ ^C
ubuntu@ip-10-0-2-148:~/ForeScale$-148:~/ForeScaleubuntu@ip-10-0-2-148:~/ForeScale$-148:~/ForeScaleubuntu@ip-10-0-2-148:~/ForeScale$ ^C
ubuntu@ip-10-0-2-148:~/ForeScale$ cat ~/ForeScale/ai/api/main.py
"""
FastAPI 서버 (최종 통합본)
# v5.2.0 — threading 스케줄러 + Prometheus + Pushgateway + auto scale-in
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import logging
import pandas as pd
import sys
import os
import time
import threading
import requests
from datetime import datetime

_AI_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
sys.path.insert(0, _AI_DIR)
_ROOT_DIR = os.path.join(_AI_DIR, '..')
sys.path.insert(0, _ROOT_DIR)

from model.predictor import Predictor
from model.spike_detector import SpikeDetector
from api.alert_sender import AlertSender
from recovery.provisioner import scale_out, scale_in, get_recovery_vms

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

app = FastAPI(title='ForeScale AI Inference API', version='5.2.0')

CONFIG = {
    'cpu_threshold':          80.0,
    'traffic_threshold':      30 * 1024,
    'spike_threshold_pct':    30.0,
    'spike_baseline_window':  5,
    'predict_minutes':        3,
    'd_team_url':             'http://10.0.2.40:8080/api/v1/alerts/scale-out',
    'prometheus_url':         'http://10.0.2.202:9090',
    'pushgateway_url':        'http://10.0.2.148:9091',
    'target_instance':        '10.0.2.40:9100',
    'source_instance_id':     'i-03512866b1c1ba03e',
    'scheduler_interval_sec': 60,
    'stable_threshold':       5,
}

sender = AlertSender(d_team_url=CONFIG['d_team_url'])
spike_detector = SpikeDetector(
    threshold_pct=CONFIG['spike_threshold_pct'],
    baseline_window=CONFIG['spike_baseline_window'],
)

_scaling_lock  = threading.Lock()
_is_scaling    = False
stable_counter = 0


def push_metrics(pred_cpu, pred_traffic, any_trigger):
    try:
        metrics = (
            'forescale_pred_cpu '    + str(pred_cpu)          + '\n'
            'forescale_pred_traffic '+ str(pred_traffic)      + '\n'
            'forescale_any_trigger ' + str(int(any_trigger))  + '\n'
        )
        requests.post(
            CONFIG['pushgateway_url'] + '/metrics/job/forescale',
            data=metrics,
            timeout=3,
        )
        logger.info('[Pushgateway] 예측값 push 완료')
    except Exception as e:
        logger.error('[Pushgateway] push 실패: ' + str(e))


def fetch_prometheus_data():
    end   = int(time.time())
    start = end - 600

    try:
        r = requests.get(CONFIG['prometheus_url'] + '/api/v1/query_range', params={
            'query': '100-(avg by(instance)(rate(node_cpu_seconds_total{mode="idle",instance="' + CONFIG['target_instance'] + '"}[1m]))*100)',
            'start': start, 'end': end, 'step': '60'
        }, timeout=5)
        cpu_vals = r.json()['data']['result'][0]['values']

        tr = requests.get(CONFIG['prometheus_url'] + '/api/v1/query_range', params={
            'query': 'rate(node_network_receive_bytes_total{instance="' + CONFIG['target_instance'] + '",device="ens5"}[1m])',
            'start': start, 'end': end, 'step': '60'
        }, timeout=5)
        t_vals = tr.json()['data']['result'][0]['values']

        n = min(len(cpu_vals), len(t_vals))
        if n < 10:
            logger.warning('[Scheduler] 데이터 포인트 부족: ' + str(n))
            return None, None

        cpu_series     = [{'ds': datetime.utcfromtimestamp(cpu_vals[i][0]).strftime('%Y-%m-%dT%H:%M:%S'), 'y': float(cpu_vals[i][1])} for i in range(n)]
        traffic_series = [{'ds': datetime.utcfromtimestamp(t_vals[i][0]).strftime('%Y-%m-%dT%H:%M:%S'),   'y': float(t_vals[i][1])}  for i in range(n)]
        return cpu_series, traffic_series

    except Exception as e:
        logger.error('[Scheduler] Prometheus 조회 실패: ' + str(e))
        return None, None


def run_predict_job():
    global _is_scaling, stable_counter

    with _scaling_lock:
        if _is_scaling:
            logger.info('[Scheduler] 이미 scale_out 진행 중 — 스킵')
            return

    logger.info('[Scheduler] Prometheus 데이터 조회 중...')
    cpu_series, traffic_series = fetch_prometheus_data()
    if cpu_series is None:
        return

    cpu_df = pd.DataFrame(cpu_series)
    cpu_df['ds'] = pd.to_datetime(cpu_df['ds'])
    traffic_df = pd.DataFrame(traffic_series)
    traffic_df['ds'] = pd.to_datetime(traffic_df['ds'])

    predictor = Predictor(
        cpu_threshold=CONFIG['cpu_threshold'],
        traffic_threshold=CONFIG['traffic_threshold'],
    )
    predictor.fit_cpu(cpu_df)
    predictor.fit_traffic(traffic_df)
    pred_result = predictor.predict_all(predict_minutes=CONFIG['predict_minutes'])

    cpu_spike     = spike_detector.check(cpu_df['y'].tolist())
    traffic_spike = spike_detector.check(traffic_df['y'].tolist())

    if cpu_spike['current'] < CONFIG['cpu_threshold']:
        cpu_spike['spike'] = False
    if traffic_spike['current'] < CONFIG['traffic_threshold']:
        traffic_spike['spike'] = False

    any_trigger = bool(pred_result['any_trigger'] or cpu_spike['spike'] or traffic_spike['spike'])

    reasons = []
    if pred_result['cpu']['trigger']:
        reasons.append('[Prophet-CPU] ' + pred_result['cpu']['reason'])
    if pred_result['traffic']['trigger']:
        reasons.append('[Prophet-TRAFFIC] ' + pred_result['traffic']['reason'])
    if cpu_spike['spike']:
        reasons.append('[Spike-CPU] ' + cpu_spike['reason'])
    if traffic_spike['spike']:
        reasons.append('[Spike-TRAFFIC] ' + traffic_spike['reason'])
    reason_text = ' | '.join(reasons) if reasons else 'all normal'

    logger.info('[Scheduler] any_trigger=' + str(any_trigger) + '| ' + reason_text)

    push_metrics(pred_result['cpu']['pred_value'], pred_result['traffic']['pred_value'], any_trigger)

    if any_trigger:
        stable_counter = 0
        with _scaling_lock:
            _is_scaling = True
        try:
            scale_out_result = scale_out(reason=reason_text)
            sender.notify(
                pred_result=pred_result,
                scale_out_result=scale_out_result,
                source_instance_id=CONFIG['source_instance_id'],
            )
            logger.info('[Scheduler] scale_out + D팀 알람 완료')
        except Exception as e:
            logger.error('[Scheduler] scale_out 실패: ' + str(e))
        finally:
            with _scaling_lock:
                _is_scaling = False

    else:
        stable_counter += 1
        logger.info('[Scheduler] 안정 카운터: ' + str(stable_counter) + '/' + str(CONFIG['stable_threshold']))

        if stable_counter >= CONFIG['stable_threshold']:
            recovery_vms = get_recovery_vms()
            if recovery_vms:
                logger.info('[Scheduler] scale_in 실행: ' + str(len(recovery_vms)) + '대')
                for vm in recovery_vms:
                    try:
                        scale_in(vm['instance_id'])
                        logger.info('[Scheduler] scale_in 완료: '+ vm['instance_id'])
                    except Exception as e:
                        logger.error('[Scheduler] scale_in 실패: ' + str(e))
            else:
                logger.info('[Scheduler] recovery VM 없음, scale_in 스킵')
            stable_counter = 0


def start_scheduler():
    def loop():
        while True:
            try:
                run_predict_job()
            except Exception as e:
                logger.error('[Scheduler] 예외: ' + str(e))
            time.sleep(CONFIG['scheduler_interval_sec'])

    t = threading.Thread(target=loop, daemon=True)
    t.start()
    logger.info('[Scheduler] 시작 — 60초 간격으로 실행')


@app.on_event('startup')
def on_startup():
    start_scheduler()


@app.get('/health')
def health():
    return {
        'status':         'ok',
        'version':        '5.2.0',
        'scheduler':      'running',
        'stable_counter': stable_counter,
    }


class TimeseriesPoint(BaseModel):
    ds: str
    y:  float


class PredictRequest(BaseModel):
    cpu_series:         List[TimeseriesPoint]
    traffic_series:     List[TimeseriesPoint]
    source_instance_id: Optional[str] = None


class PredictResponse(BaseModel):
    pred_cpu:        float
    pred_traffic:    float
    cpu_trigger:     bool
    traffic_trigger: bool
    spike_cpu:       bool
    spike_traffic:   bool
    any_trigger:     bool
    reason:          str
    alerts_sent:     List[dict]


@app.post('/predict', response_model=PredictResponse)
def predict(req: PredictRequest):
    if len(req.cpu_series) < 10 or len(req.traffic_series) < 10:
        raise HTTPException(status_code=400, detail='cpu_series and traffic_series each need >= 10 points')

    cpu_df = pd.DataFrame([{'ds': p.ds, 'y': p.y} for p in req.cpu_series])
    cpu_df['ds'] = pd.to_datetime(cpu_df['ds'])
    traffic_df = pd.DataFrame([{'ds': p.ds, 'y': p.y} for p in req.traffic_series])
    traffic_df['ds'] = pd.to_datetime(traffic_df['ds'])

    predictor = Predictor(
        cpu_threshold=CONFIG['cpu_threshold'],
        traffic_threshold=CONFIG['traffic_threshold'],
    )
    predictor.fit_cpu(cpu_df)
    predictor.fit_traffic(traffic_df)
    pred_result = predictor.predict_all(predict_minutes=CONFIG['predict_minutes'])

    cpu_spike     = spike_detector.check(cpu_df['y'].tolist())
    traffic_spike = spike_detector.check(traffic_df['y'].tolist())

    if cpu_spike['current'] < CONFIG['cpu_threshold']:
        cpu_spike['spike'] = False
    if traffic_spike['current'] < CONFIG['traffic_threshold']:
        traffic_spike['spike'] = False

    any_trigger = bool(pred_result['any_trigger'] or cpu_spike['spike'] or traffic_spike['spike'])

    reasons = []
    if pred_result['cpu']['trigger']:
        reasons.append('[Prophet-CPU] ' + pred_result['cpu']['reason'])
    if pred_result['traffic']['trigger']:
        reasons.append('[Prophet-TRAFFIC] ' + pred_result['traffic']['reason'])
    if cpu_spike['spike']:
        reasons.append('[Spike-CPU] ' + cpu_spike['reason'])
    if traffic_spike['spike']:
        reasons.append('[Spike-TRAFFIC] ' + traffic_spike['reason'])
    reason_text = ' | '.join(reasons) if reasons else 'all normal'

    push_metrics(pred_result['cpu']['pred_value'], pred_result['traffic']['pred_value'], any_trigger)
    logger.info('[/predict] any_trigger=' + str(any_trigger) + ' | ' + reason_text)

    alerts_sent = []
    if any_trigger:
        try:
            scale_out_result = scale_out(reason=reason_text)
            alerts_sent = [sender.notify(
                pred_result=pred_result,
                scale_out_result=scale_out_result,
                source_instance_id=req.source_instance_id,
            )]
        except Exception as e:
            logger.error('[/predict] scale_out 실패: ' + str(e))
            alerts_sent = [{'status': 'SCALE_OUT_FAILED', 'reason': str(e)}]

    return PredictResponse(
        pred_cpu        = pred_result['cpu']['pred_value'],
        pred_traffic    = pred_result['traffic']['pred_value'],
        cpu_trigger     = pred_result['cpu']['trigger'],
        traffic_trigger = pred_result['traffic']['trigger'],
        spike_cpu       = cpu_spike['spike'],
        spike_traffic   = traffic_spike['spike'],
        any_trigger     = any_trigger,
        reason          = reason_text,
        alerts_sent     = alerts_sent,
    )
