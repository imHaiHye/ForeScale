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
from typing import Optional
from pathlib import Path
import warnings
warnings.filterwarnings('ignore')


# ============================================================
# 단일 모델 헬퍼 (CPU와 Traffic 모두 동일 로직)
# ============================================================
def _build_model() -> Prophet:
    """Prophet 모델 생성 (공통 설정)"""
    return Prophet(
        changepoint_prior_scale=0.5,
        yearly_seasonality=False,
        weekly_seasonality=False,
        daily_seasonality=True,
        interval_width=0.95,
    )


def _predict_one(model: Prophet, predict_minutes: int) -> dict:
    """단일 모델 N분 뒤 예측"""
    future = model.make_future_dataframe(periods=predict_minutes, freq='min')
    forecast = model.predict(future)
    row = forecast.iloc[-1]
    return {
        'pred_value': float(row['yhat']),
        'pred_lower': float(row['yhat_lower']),
        'pred_upper': float(row['yhat_upper']),
        'pred_at': row['ds'],
    }


def _evaluate(pred_value: float, threshold: float, unit: str) -> dict:
    """임계치 비교 → 트리거 여부와 사유 반환"""
    is_dangerous = pred_value > threshold
    return {
        'trigger': bool(is_dangerous),
        'reason': (
            f'{"dangerous" if is_dangerous else "normal"}: '
            f'predicted {pred_value:.1f} {unit} '
            f'{"exceeds" if is_dangerous else "within"} '
            f'threshold {threshold:.1f} {unit}'
        ),
    }


# ============================================================
# Predictor (메인 클래스)
# ============================================================
class Predictor:
    """
    CPU + Traffic 병렬 추론을 한 곳에서 관리

    Attributes:
        cpu_threshold: CPU 위험 임계치 (%)
        traffic_threshold: Traffic 위험 임계치 (bytes/s)
        cpu_model: Prophet_CPU 모델 (fit 후 채워짐)
        traffic_model: Prophet_Traffic 모델 (fit 후 채워짐)
    """

    def __init__(
        self,
        cpu_threshold: float = 80.0,           # CPU 사용률 80%
        traffic_threshold: float = 30 * 1024,  # 30 KB/s (학습용, 운영에서 조정)
    ):
        self.cpu_threshold = cpu_threshold
        self.traffic_threshold = traffic_threshold
        self.cpu_model: Optional[Prophet] = None
        self.traffic_model: Optional[Prophet] = None

    # --------------------------------------------------------
    # 학습 메서드
    # --------------------------------------------------------
    def fit_cpu(self, df: pd.DataFrame) -> None:
        """CPU 시계열 학습 (df: [ds, y] 형식, y는 CPU %)"""
        self.cpu_model = _build_model()
        self.cpu_model.fit(df)

    def fit_traffic(self, df: pd.DataFrame) -> None:
        """Traffic 시계열 학습 (df: [ds, y] 형식, y는 bytes/s)"""
        self.traffic_model = _build_model()
        self.traffic_model.fit(df)

    # --------------------------------------------------------
    # 추론 메서드
    # --------------------------------------------------------
    def predict_cpu(self, predict_minutes: int = 3) -> dict:
        """CPU 단독 예측 + 트리거 판단"""
        if self.cpu_model is None:
            raise RuntimeError('CPU model not fitted. Call fit_cpu() first.')
        pred = _predict_one(self.cpu_model, predict_minutes)
        eval_result = _evaluate(pred['pred_value'], self.cpu_threshold, '%')
        return {
            'model_type': 'CPU',
            'threshold': self.cpu_threshold,
            **pred,
            **eval_result,
        }

    def predict_traffic(self, predict_minutes: int = 3) -> dict:
        """Traffic 단독 예측 + 트리거 판단"""
        if self.traffic_model is None:
            raise RuntimeError('Traffic model not fitted. Call fit_traffic() first.')
        pred = _predict_one(self.traffic_model, predict_minutes)
        eval_result = _evaluate(pred['pred_value'], self.traffic_threshold, 'B/s')
        return {
            'model_type': 'TRAFFIC',
            'threshold': self.traffic_threshold,
            **pred,
            **eval_result,
        }

    def predict_all(self, predict_minutes: int = 3) -> dict:
        """
        CPU + Traffic 동시 추론 + OR 트리거 판단

        Returns:
            {
              'cpu':     {...},
              'traffic': {...},
              'any_trigger': True if (CPU 위험) OR (Traffic 위험)
            }
        """
        cpu_result = self.predict_cpu(predict_minutes)
        traffic_result = self.predict_traffic(predict_minutes)

        return {
            'cpu': cpu_result,
            'traffic': traffic_result,
            'any_trigger': bool(cpu_result['trigger'] or traffic_result['trigger']),
        }


# ============================================================
# 메인 실행 (테스트)
# ============================================================
if __name__ == '__main__':
    print('=' * 60)
    print('Predictor 통합 테스트')
    print('=' * 60)

    # --- Traffic 데이터: B팀 sudden_traffic.csv ---
    print('\n[1] Loading sudden_traffic.csv (B팀 제공) ...')
    raw = pd.read_csv(Path(__file__).parent.parent / 'data' / 'sudden_traffic.csv', low_memory=False)
    raw['ts'] = pd.to_datetime(raw['timeStamp'], unit='ms')
    traffic_df = raw.groupby(raw['ts'].dt.floor('1min'))['bytes'].sum().reset_index()
    traffic_df.columns = ['ds', 'y']
    traffic_df['y'] = traffic_df['y'] / 60  # bytes/s 단위로 환산
    print(f'    Traffic points: {len(traffic_df)}')

    # --- CPU 데이터: 더미 (실제로는 Prometheus 메트릭) ---
    # sudden_traffic 시간대에 맞춰 가짜 CPU 데이터 생성
    print('\n[2] Generating dummy CPU data (실제 운영에서는 Prometheus) ...')
    import numpy as np
    cpu_df = pd.DataFrame({
        'ds': traffic_df['ds'],
        # 트래픽이 오르면 CPU도 따라 오르도록 흉내
        'y': 20 + (traffic_df['y'] / traffic_df['y'].max()) * 60 + np.random.normal(0, 3, len(traffic_df)),
    })
    cpu_df['y'] = cpu_df['y'].clip(0, 100)
    print(f'    CPU points: {len(cpu_df)}, y range: '
          f'{cpu_df["y"].min():.1f}% ~ {cpu_df["y"].max():.1f}%')

    # --- Predictor 학습 + 추론 ---
    print('\n[3] Predictor 학습 ...')
    p = Predictor(cpu_threshold=80.0, traffic_threshold=30 * 1024)
    p.fit_cpu(cpu_df)
    p.fit_traffic(traffic_df)

    print('\n[4] 병렬 추론 (3분 뒤) ...')
    result = p.predict_all(predict_minutes=3)

    print('\n--- CPU 결과 ---')
    print(f'    pred_value: {result["cpu"]["pred_value"]:.1f}%')
    print(f'    threshold:  {result["cpu"]["threshold"]:.1f}%')
    print(f'    trigger:    {result["cpu"]["trigger"]}')
    print(f'    reason:     {result["cpu"]["reason"]}')

    print('\n--- Traffic 결과 ---')
    print(f'    pred_value: {result["traffic"]["pred_value"]:.0f} B/s '
          f'({result["traffic"]["pred_value"]/1024:.1f} KB/s)')
    print(f'    threshold:  {result["traffic"]["threshold"]:.0f} B/s '
          f'({result["traffic"]["threshold"]/1024:.1f} KB/s)')
    print(f'    trigger:    {result["traffic"]["trigger"]}')
    print(f'    reason:     {result["traffic"]["reason"]}')

    print(f'\n--- OR 트리거 (둘 중 하나라도 위험?) ---')
    print(f'    any_trigger: {result["any_trigger"]}')

    print('\n' + '=' * 60)
    print('Done')
    print('=' * 60)
