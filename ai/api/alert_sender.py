"""
Alert Sender — D팀(황준하) Spring Boot로 알람 전송

D팀과 합의한 스펙(api-spec-for-cteam.md)에 정확히 맞춤:
  - POST http://10.0.2.40:8080/api/v1/alerts/scale-out
  - 필수 필드: model_type, predicted_value, threshold, predicted_at
  - 선택 필드: source_instance_id, severity
  - 응답: 200 TRIGGERED / 409 COOLDOWN_ACTIVE / 4xx,5xx ERROR

CPU와 TRAFFIC은 따로 전송. (둘 다 위험하면 POST 두 번 쏨)

사용:
    sender = AlertSender(d_team_url='http://10.0.2.40:8080/api/v1/alerts/scale-out')
    sender.send_from_predictor_result(predictor_result, source_instance_id='i-xxx')
"""

import requests
from datetime import datetime, timezone
from typing import Optional
import logging

logger = logging.getLogger(__name__)


class AlertSender:
    """D팀에 Scale-out 알람을 POST로 전송"""

    def __init__(
        self,
        d_team_url: str = 'http://10.0.2.40:8080/api/v1/alerts/scale-out',
        timeout_sec: float = 5.0,
    ):
        self.url = d_team_url
        self.timeout = timeout_sec

    # --------------------------------------------------------
    # 단일 알람 전송 (CPU 또는 TRAFFIC 하나)
    # --------------------------------------------------------
    def send_one(
        self,
        model_type: str,            # "CPU" or "TRAFFIC"
        predicted_value: float,
        threshold: float,
        predicted_at: datetime,
        source_instance_id: Optional[str] = None,
        severity: str = 'CRITICAL',
    ) -> dict:
        """
        D팀 스펙 그대로 POST 전송

        Returns:
            {
              'http_status': int,
              'd_status': str,        # TRIGGERED / COOLDOWN_ACTIVE / ERROR / NETWORK_ERROR
              'message': str,
              'sent_payload': dict,
            }
        """
        # 페이로드 구성 (D팀 스펙 그대로)
        payload = {
            'model_type': model_type,
            'predicted_value': float(predicted_value),
            'threshold': float(threshold),
            # ISO 8601, UTC, 끝에 Z
            'predicted_at': self._to_iso8601(predicted_at),
            'severity': severity,
        }
        if source_instance_id:
            payload['source_instance_id'] = source_instance_id

        logger.info(f'[AlertSender] POST {self.url} payload={payload}')

        # 실제 전송 (네트워크 에러까지 처리)
        try:
            resp = requests.post(self.url, json=payload, timeout=self.timeout)
        except requests.exceptions.RequestException as e:
            logger.error(f'[AlertSender] Network error: {e}')
            return {
                'http_status': 0,
                'd_status': 'NETWORK_ERROR',
                'message': str(e),
                'sent_payload': payload,
            }

        # 응답 파싱 (D팀 스펙: 200=TRIGGERED, 409=COOLDOWN_ACTIVE, 4xx/5xx=ERROR)
        try:
            body = resp.json()
        except ValueError:
            body = {}

        return {
            'http_status': resp.status_code,
            'd_status': body.get('status', 'UNKNOWN'),
            'message': body.get('message', resp.text[:200]),
            'sent_payload': payload,
        }

    # --------------------------------------------------------
    # Predictor 결과를 받아서 위험한 모델만 알람 전송
    # --------------------------------------------------------
    def send_from_predictor_result(
        self,
        result: dict,
        source_instance_id: Optional[str] = None,
    ) -> list:
        """
        Predictor.predict_all() 결과를 받아서
        trigger=True인 모델만 골라 D팀에 전송

        Args:
            result: {'cpu': {...}, 'traffic': {...}, 'any_trigger': ...}
            source_instance_id: 알람 발생 EC2 ID

        Returns:
            [{model_type, http_status, d_status, message}, ...]
        """
        sent_results = []

        for key in ('cpu', 'traffic'):
            sub = result.get(key)
            if not sub or not sub.get('trigger'):
                continue  # 위험하지 않으면 전송 안 함

            # severity 결정: 예측값이 임계치 대비 얼마나 초과했나
            severity = self._decide_severity(sub['pred_value'], sub['threshold'])

            sent = self.send_one(
                model_type=sub['model_type'],         # "CPU" or "TRAFFIC"
                predicted_value=sub['pred_value'],
                threshold=sub['threshold'],
                predicted_at=sub['pred_at'],
                source_instance_id=source_instance_id,
                severity=severity,
            )
            sent_results.append(sent)

        return sent_results

    # --------------------------------------------------------
    # 헬퍼
    # --------------------------------------------------------
    @staticmethod
    def _to_iso8601(dt) -> str:
        """datetime/Timestamp → ISO 8601 UTC (D팀 스펙 형식: 2024-01-15T14:23:00Z)"""
        if hasattr(dt, 'to_pydatetime'):  # pandas Timestamp
            dt = dt.to_pydatetime()
        # tzinfo 없으면 UTC로 간주
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        else:
            dt = dt.astimezone(timezone.utc)
        # 'Z' suffix
        return dt.strftime('%Y-%m-%dT%H:%M:%SZ')

    @staticmethod
    def _decide_severity(pred_value: float, threshold: float) -> str:
        """초과 정도에 따라 WARNING / CRITICAL 결정"""
        if threshold <= 0:
            return 'WARNING'
        ratio = pred_value / threshold
        # 임계치 대비 1.2배 이상 → CRITICAL, 그 미만 → WARNING
        return 'CRITICAL' if ratio >= 1.2 else 'WARNING'


# ============================================================
# 단독 실행 테스트
# ============================================================
if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')

    print('=' * 60)
    print('AlertSender 테스트 (실제 전송은 안 됨 — D팀 서버 없을 때)')
    print('=' * 60)

    # 가짜 Predictor 결과 만들기
    fake_result = {
        'cpu': {
            'model_type': 'CPU',
            'pred_value': 87.3,
            'threshold': 80.0,
            'pred_at': datetime(2026, 5, 11, 14, 23, 0, tzinfo=timezone.utc),
            'trigger': True,
            'reason': 'dangerous: predicted 87.3 % exceeds threshold 80.0 %',
        },
        'traffic': {
            'model_type': 'TRAFFIC',
            'pred_value': 46766.0,
            'threshold': 30720.0,
            'pred_at': datetime(2026, 5, 11, 14, 23, 0, tzinfo=timezone.utc),
            'trigger': True,
            'reason': 'dangerous: predicted 46766 B/s exceeds threshold 30720 B/s',
        },
        'any_trigger': True,
    }

    # 실제 D팀 서버가 없으면 NETWORK_ERROR가 뜸 — 그게 정상
    sender = AlertSender(d_team_url='http://10.0.2.40:8080/api/v1/alerts/scale-out')
    sent_list = sender.send_from_predictor_result(
        fake_result,
        source_instance_id='i-03512866b1c1ba03e',
    )

    for i, s in enumerate(sent_list, 1):
        print(f'\n--- 알람 #{i} ({s["sent_payload"]["model_type"]}) ---')
        print(f'    sent_payload: {s["sent_payload"]}')
        print(f'    http_status:  {s["http_status"]}')
        print(f'    d_status:     {s["d_status"]}')
        print(f'    message:      {s["message"]}')

    print('\n' + '=' * 60)
    print('Done — 실제 D팀 서버가 떠있으면 200/409 응답이 옴')
    print('=' * 60)
