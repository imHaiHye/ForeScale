"""
Prophet_Traffic 프로토타입
- B팀(최정연)이 제공한 JMeter 결과(sudden_traffic.csv)를 학습 데이터로 사용
- bytes/s 단위로 집계 (운영 환경의 Prometheus node_network_receive_bytes_total과 동일 단위)
- Prophet으로 3분 뒤 트래픽 예측
- 임계치 초과 여부 판단 (dangerous / normal)

실행:
    python prophet_traffic_test.py
"""

import pandas as pd
from prophet import Prophet
import warnings
warnings.filterwarnings('ignore')
import os



# ============================================================
# 1. 데이터 로드 및 시계열 변환
# ============================================================
def load_jmeter_csv(path: str, freq: str = '1min') -> pd.DataFrame:
    """
    JMeter CSV → Prophet 입력 형식(ds, y)
    bytes 컬럼을 합산해서 bytes/s 단위로 변환

    Args:
        path: CSV 경로
        freq: 집계 단위 ('1s', '10s', '1min' 등)
              운영(Prometheus 10초 스크랩)과 맞추려면 '10s' 권장
              학습 안정성을 위해 기본은 '1min'

    Returns:
        DataFrame[ds: datetime, y: 평균 bytes/s]
    """
    df = pd.read_csv(path, low_memory=False)
    df['ts'] = pd.to_datetime(df['timeStamp'], unit='ms')

    # freq 단위로 floor 후 bytes 합산
    agg = df.groupby(df['ts'].dt.floor(freq))['bytes'].sum().reset_index()
    agg.columns = ['ds', 'y']

    # 단위를 bytes/s로 정규화
    seconds_per_bucket = pd.Timedelta(freq).total_seconds()
    agg['y'] = agg['y'] / seconds_per_bucket

    return agg


# ============================================================
# 2. Prophet 학습 및 예측
# ============================================================
def train_and_predict(df: pd.DataFrame, predict_minutes: int = 3) -> dict:
    """
    Prophet 모델로 N분 뒤 트래픽 예측
    """
    model = Prophet(
        changepoint_prior_scale=0.5,
        yearly_seasonality=False,
        weekly_seasonality=False,
        daily_seasonality=True,
        interval_width=0.95,
    )
    model.fit(df)

    future = model.make_future_dataframe(periods=predict_minutes, freq='min')
    forecast = model.predict(future)

    pred_row = forecast.iloc[-1]
    return {
        'pred_value': float(pred_row['yhat']),
        'pred_lower': float(pred_row['yhat_lower']),
        'pred_upper': float(pred_row['yhat_upper']),
        'pred_at': pred_row['ds'],
        'model': model,
        'forecast': forecast,
    }


# ============================================================
# 3. 트리거 판단
# ============================================================
def evaluate_trigger(pred_value: float, threshold: float) -> dict:
    """
    예측값이 임계치를 넘었는지 판단
    """
    if pred_value > threshold:
        return {
            'trigger': bool(True),
            'reason': f'dangerous: predicted {pred_value:.0f} B/s '
                      f'({pred_value/1024:.1f} KB/s) exceeds threshold '
                      f'{threshold:.0f} B/s ({threshold/1024:.1f} KB/s)'
        }
    return {
        'trigger': bool(False),
        'reason': f'normal: predicted {pred_value:.0f} B/s '
                  f'({pred_value/1024:.1f} KB/s) within threshold '
                  f'{threshold:.0f} B/s ({threshold/1024:.1f} KB/s)'
    }


# ============================================================
# 4. 메인 실행
# ============================================================
if __name__ == '__main__':
    # CSV_PATH = 'sudden_traffic.csv'
    CSV_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'data', 'sudden_traffic.csv')
    PREDICT_MINUTES = 3
    # 학습 데이터(sudden_traffic) 최대 트래픽이 51 KB/s
    # 일단 임계치는 30 KB/s 로 잡고 Step 7에서 튜닝
    TRAFFIC_THRESHOLD = 30 * 1024  # 30 KB/s

    print('=' * 60)
    print('Prophet_Traffic Test')
    print('=' * 60)

    print(f'\n[1] Loading {CSV_PATH} ...')
    df = load_jmeter_csv(CSV_PATH, freq='1min')
    print(f'    Loaded {len(df)} points')
    print(f'    Range: {df["ds"].min()} ~ {df["ds"].max()}')
    print(f'    y stats: min={df["y"].min():.0f} B/s, '
          f'max={df["y"].max():.0f} B/s, mean={df["y"].mean():.0f} B/s')

    print(f'\n[2] Training Prophet & predicting {PREDICT_MINUTES} min ahead ...')
    result = train_and_predict(df, predict_minutes=PREDICT_MINUTES)
    print(f'    Predicted at: {result["pred_at"]}')
    print(f'    Predicted value: {result["pred_value"]:.0f} B/s '
          f'({result["pred_value"]/1024:.1f} KB/s)')
    print(f'    95% CI: [{result["pred_lower"]:.0f}, {result["pred_upper"]:.0f}] B/s')

    print(f'\n[3] Trigger evaluation '
          f'(threshold={TRAFFIC_THRESHOLD} B/s = {TRAFFIC_THRESHOLD/1024:.0f} KB/s)')
    trigger = evaluate_trigger(result['pred_value'], TRAFFIC_THRESHOLD)
    print(f'    Trigger: {trigger["trigger"]}')
    print(f'    Reason: {trigger["reason"]}')

    print('\n' + '=' * 60)
    print('Done')
    print('=' * 60)
