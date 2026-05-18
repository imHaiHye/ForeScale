"""
Alert Sender — D팀(황준하) Spring Boot로 결과 통보
# v4.0.0 — B안 확정: FastAPI 직접 실행 + D팀 결과 통보

D팀 새 스펙(api-spec-for-cteam.md v4) 기준:
  - POST http://10.0.2.40:8080/api/v1/scale-out/event
  - 필수 필드: event_type, trigger.model_type, scale_out_result.instance_id, source_instance_id
  - 응답: 200=SUCCESS, 4xx/5xx=ERROR, 네트워크 에러=NETWORK_ERROR

역할 변경: "scale-out 실행 요청" → "scale-out 완료 결과 통보"
  C팀이 직접 provisioner.scale_out()을 호출하고 완료 후 D팀에 알림

사용:
    sender = AlertSender(d_team_url='http://10.0.2.40:8080/api/v1/scale-out/event')
    result = sender.notify(pred_result, scale_out_result, source_instance_id='i-xxx')
"""

import requests
from datetime import datetime, timezone
from typing import Optional
import logging

logger = logging.getLogger(__name__)


class AlertSender:
    """scale-out 완료 결과를 D팀에 POST로 통보"""

    def __init__(
        self,
        d_team_url: str = 'http://10.0.2.40:8080/api/v1/scale-out/event',
        timeout_sec: float = 5.0,
    ):
        self.url = d_team_url
        self.timeout = timeout_sec

    # --------------------------------------------------------
    # 결과 통보 (scale_out() 완료 후)
    # --------------------------------------------------------
    def notify(
        self,
        pred_result: dict,
        scale_out_result: dict,
        source_instance_id: Optional[str] = None,
    ) -> dict:
        """
        scale_out() 완료 직후 D팀에 결과 통보

        Args:
            pred_result:        Predictor.predict_all() 결과
                                {'cpu': {..., trigger, pred_value, threshold, pred_at, reason},
                                 'traffic': {...}, 'any_trigger': bool}
            scale_out_result:   provisioner.scale_out() 반환값
                                {'instance_id', 'private_ip', 'vm_name', 'status'}
            source_instance_id: 알람 발생 EC2 인스턴스 ID

        Returns:
            {
              'http_status': int,
              'd_status': str,        # SUCCESS / ERROR / NETWORK_ERROR
              'message': str,
              'sent_payload': dict,
            }
        """
        trigger_sub = self._pick_trigger(pred_result)
        severity = self._decide_severity(trigger_sub['pred_value'], trigger_sub['threshold'])
        executed_at = self._to_iso8601(datetime.now(tz=timezone.utc))

        payload = {
            'event_type': 'SCALE_OUT_COMPLETED',
            'trigger': {
                'model_type': trigger_sub['model_type'],
                'predicted_value': float(trigger_sub['pred_value']),
                'threshold': float(trigger_sub['threshold']),
                'predicted_at': self._to_iso8601(trigger_sub['pred_at']),
                'severity': severity,
                'reason': trigger_sub.get('reason', ''),
            },
            'scale_out_result': {
                'instance_id': scale_out_result.get('instance_id', ''),
                'private_ip': scale_out_result.get('private_ip', ''),
                'vm_name': scale_out_result.get('vm_name', ''),
                'status': scale_out_result.get('status', 'provisioned'),
            },
            'source_instance_id': source_instance_id or '',
            'executed_at': executed_at,
        }

        logger.info(f'[AlertSender] POST {self.url} payload={payload}')

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

        try:
            body = resp.json()
        except ValueError:
            body = {}

        d_status = 'SUCCESS' if resp.status_code == 200 else 'ERROR'
        return {
            'http_status': resp.status_code,
            'd_status': d_status,
            'message': body.get('message', resp.text[:200]),
            'sent_payload': payload,
        }

    # --------------------------------------------------------
    # 헬퍼
    # --------------------------------------------------------
    @staticmethod
    def _pick_trigger(pred_result: dict) -> dict:
        """trigger=True인 모델 반환. 둘 다면 CPU 우선. 둘 다 False면 CPU 반환(Spike 전용 케이스)."""
        cpu = pred_result.get('cpu', {})
        traffic = pred_result.get('traffic', {})
        if cpu.get('trigger'):
            return cpu
        if traffic.get('trigger'):
            return traffic
        return cpu if cpu else traffic

    @staticmethod
    def _to_iso8601(dt) -> str:
        """datetime/Timestamp → ISO 8601 UTC (D팀 스펙 형식: 2024-01-15T14:23:00Z)"""
        if hasattr(dt, 'to_pydatetime'):  # pandas Timestamp
            dt = dt.to_pydatetime()
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        else:
            dt = dt.astimezone(timezone.utc)
        return dt.strftime('%Y-%m-%dT%H:%M:%SZ')

    @staticmethod
    def _decide_severity(pred_value: float, threshold: float) -> str:
        """초과 정도에 따라 WARNING / CRITICAL 결정"""
        if threshold <= 0:
            return 'WARNING'
        ratio = pred_value / threshold
        return 'CRITICAL' if ratio >= 1.2 else 'WARNING'


# ============================================================
# 단독 실행 테스트
# ============================================================
if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')

    print('=' * 60)
    print('AlertSender 테스트 (실제 전송은 안 됨 — D팀 서버 없을 때)')
    print('=' * 60)

    fake_pred_result = {
        'cpu': {
            'model_type': 'CPU',
            'pred_value': 87.3,
            'threshold': 80.0,
            'pred_at': datetime(2026, 5, 18, 14, 20, 0, tzinfo=timezone.utc),
            'trigger': True,
            'reason': '[Prophet-CPU] dangerous: predicted 87.3% exceeds threshold 80.0%',
        },
        'traffic': {
            'model_type': 'TRAFFIC',
            'pred_value': 46766.0,
            'threshold': 30720.0,
            'pred_at': datetime(2026, 5, 18, 14, 20, 0, tzinfo=timezone.utc),
            'trigger': False,
            'reason': 'ok',
        },
        'any_trigger': True,
    }

    fake_scale_out_result = {
        'instance_id': 'i-0abc1234',
        'private_ip': '10.0.2.100',
        'vm_name': 'forescale-recovery-20260518-abcdef',
        'status': 'provisioned',
    }

    sender = AlertSender()
    result = sender.notify(
        pred_result=fake_pred_result,
        scale_out_result=fake_scale_out_result,
        source_instance_id='i-03512866b1c1ba03e',
    )

    print(f'\n--- 결과 통보 ---')
    print(f'    http_status:  {result["http_status"]}')
    print(f'    d_status:     {result["d_status"]}')
    print(f'    message:      {result["message"]}')
    print(f'    sent_payload: {result["sent_payload"]}')

    print('\n' + '=' * 60)
    print('Done — 실제 D팀 서버가 떠있으면 200 SUCCESS 응답이 옴')
    print('=' * 60)
