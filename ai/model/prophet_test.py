import pandas as pd
from prophet import Prophet

# 테스트용 더미 데이터 생성 (CPU 사용률)
data = {
    'ds': pd.date_range(start='2024-01-01', periods=100, freq='1min'),
    'y': [30 + i * 0.5 + (i % 10) * 2 for i in range(100)]  # 서서히 오르는 CPU
}

df = pd.DataFrame(data)

# Prophet 모델 학습
model = Prophet()
model.fit(df)

# 3분 뒤까지 예측
future = model.make_future_dataframe(periods=3, freq='1min')
forecast = model.predict(future)

# 결과 출력
print("=== 예측 결과 (마지막 3개 = 미래) ===")
print(forecast[['ds', 'yhat', 'yhat_lower', 'yhat_upper']].tail(3))


# 임계치 판단 (80% 넘으면 위험)
THRESHOLD = 80.0

future_preds = forecast.tail(3)
for _, row in future_preds.iterrows():
    status = "🚨 위험" if row['yhat'] > THRESHOLD else "✅ 정상"
    print(f"{row['ds']} → 예측 CPU: {row['yhat']:.1f}% {status}")