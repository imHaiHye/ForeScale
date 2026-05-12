"""
Threshold 초기 튜닝

B팀이 준 sudden_traffic.csv는 "전형적인 바이럴 스파이크"의 sample이다.
이 데이터에 대해:
  - 임계치가 너무 낮으면 → 평상시에도 알람 (false positive)
  - 임계치가 너무 높으면 → 진짜 위험 상황을 놓침 (false negative)

여러 후보 임계치를 돌려보면서 어디가 sweet spot인지 찾는다.

실행:
    python tune_threshold.py
"""

import pandas as pd
import numpy as np
from pathlib import Path
from prophet import Prophet
import warnings
warnings.filterwarnings('ignore')


def load_traffic(path) -> pd.DataFrame:
    raw = pd.read_csv(path, low_memory=False)
    raw['ts'] = pd.to_datetime(raw['timeStamp'], unit='ms')
    df = raw.groupby(raw['ts'].dt.floor('1min'))['bytes'].sum().reset_index()
    df.columns = ['ds', 'y']
    df['y'] = df['y'] / 60  # bytes/s
    return df


def rolling_predict(df: pd.DataFrame, predict_minutes: int = 3,
                    train_window: int = 8) -> pd.DataFrame:
    """
    슬라이딩 윈도우로 매 시점마다 N분 뒤 예측해서 예측값 시계열 생성

    - 각 시점 t에서: t-train_window ~ t 데이터로 학습 → t+N 분 예측
    - 실제 사용 패턴과 동일한 방식
    """
    results = []
    for end_idx in range(train_window, len(df)):
        train = df.iloc[max(0, end_idx - train_window):end_idx]
        if len(train) < 4:
            continue
        m = Prophet(
            changepoint_prior_scale=0.5,
            yearly_seasonality=False, weekly_seasonality=False,
            daily_seasonality=True, interval_width=0.95,
        )
        m.fit(train)
        future = m.make_future_dataframe(periods=predict_minutes, freq='min')
        forecast = m.predict(future).iloc[-1]
        results.append({
            'ds': train.iloc[-1]['ds'],
            'actual': float(train.iloc[-1]['y']),
            'pred': float(forecast['yhat']),
        })
    return pd.DataFrame(results)


def evaluate_thresholds(preds_df: pd.DataFrame, candidates: list) -> pd.DataFrame:
    """
    각 임계치 후보별로 알람 횟수와 비율 계산
    """
    rows = []
    for th in candidates:
        n_trigger = int((preds_df['pred'] > th).sum())
        ratio = n_trigger / len(preds_df) * 100
        rows.append({
            'threshold_Bps': th,
            'threshold_KBps': th / 1024,
            'n_trigger': n_trigger,
            'total_points': len(preds_df),
            'trigger_ratio_%': round(ratio, 1),
        })
    return pd.DataFrame(rows)


if __name__ == '__main__':
    print('=' * 60)
    print('Threshold Tuning — B팀 sudden_traffic.csv')
    print('=' * 60)

    # 1) 데이터 로드
    df = load_traffic(Path(__file__).parent.parent / 'data' / 'sudden_traffic.csv')
    print(f'\n[1] 데이터 로드: {len(df)} points')
    print(f'    Actual y stats: min={df["y"].min():.0f} B/s, '
          f'max={df["y"].max():.0f} B/s, mean={df["y"].mean():.0f} B/s, '
          f'p95={df["y"].quantile(0.95):.0f} B/s')

    # 2) 슬라이딩 예측
    print(f'\n[2] Rolling forecast 시뮬레이션 (3분 뒤 예측)...')
    preds = rolling_predict(df, predict_minutes=3, train_window=8)
    print(f'    예측 시점 수: {len(preds)}')
    print(f'    Pred y stats:   min={preds["pred"].min():.0f} B/s, '
          f'max={preds["pred"].max():.0f} B/s, mean={preds["pred"].mean():.0f} B/s')

    # 3) 임계치 후보 평가
    # 5 KB/s ~ 80 KB/s 범위에서 sweep
    candidates = [5*1024, 10*1024, 15*1024, 20*1024, 25*1024,
                  30*1024, 40*1024, 50*1024, 60*1024, 80*1024]
    print(f'\n[3] 임계치 후보별 알람 비율')
    table = evaluate_thresholds(preds, candidates)
    print(table.to_string(index=False))

    # 4) 추천
    print('\n[4] 권장 임계치')
    print('    - 학습 데이터(sudden_traffic.csv)는 "전부 위험한 부하" 상황이므로')
    print('      알람 비율이 60~80% 정도여야 적절 (대부분 위험 인지)')
    print('    - 평상시 데이터(B팀이 점진적 부하 시나리오로 추가 제공 예정)에서')
    print('      알람 비율이 5% 미만이 되도록 추가 튜닝 필요')
    print()
    # 알람 비율 60~80% 사이 후보 추천
    target = table[(table['trigger_ratio_%'] >= 60) & (table['trigger_ratio_%'] <= 80)]
    if len(target) > 0:
        rec = target.iloc[0]
        print(f'    👉 1차 권장: {int(rec["threshold_Bps"])} B/s '
              f'({rec["threshold_KBps"]:.0f} KB/s) — alarm ratio {rec["trigger_ratio_%"]}%')
    else:
        # 가장 가까운 것
        diff = (table['trigger_ratio_%'] - 70).abs()
        rec = table.iloc[diff.idxmin()]
        print(f'    👉 1차 권장: {int(rec["threshold_Bps"])} B/s '
              f'({rec["threshold_KBps"]:.0f} KB/s) — alarm ratio {rec["trigger_ratio_%"]}%')

    print('\n' + '=' * 60)
    print('Done. main.py의 CONFIG["traffic_threshold"]를 위 값으로 업데이트.')
    print('=' * 60)
