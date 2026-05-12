"""
Spike Detector — 규칙 기반 급격 변화 감지

Prophet은 "추세 학습" 기반이라 천천히 오르는 패턴은 잘 잡지만,
1분 이내에 5배 폭증하는 바이럴 스파이크는 학습할 시간이 없음.

이 모듈은 단순 규칙(변화율)로 그 사각지대를 메꿈:
  "직전 1분 평균 대비 30% 이상 튀면 즉시 위험"

Prophet과 병렬로 돌려서 둘 중 하나라도 위험하면 알람.

사용:
    detector = SpikeDetector(threshold_pct=30.0)
    result = detector.check(recent_values)
    # result = {spike: bool, change_rate: float, reason: str}
"""

import pandas as pd
from pathlib import Path
from typing import List, Union


class SpikeDetector:
    """
    1분 변화율 기반 급격 증가 감지

    "직전 N분 평균 대비 현재 값이 threshold_pct% 이상 증가했나?"
    """

    def __init__(
        self,
        threshold_pct: float = 30.0,    # 30% 증가 시 알람
        baseline_window: int = 5,        # 직전 5개 포인트로 baseline 계산
        min_baseline: float = 1.0,       # baseline이 너무 작으면(0 근처) 무시
    ):
        self.threshold_pct = threshold_pct
        self.baseline_window = baseline_window
        self.min_baseline = min_baseline

    def check(self, recent_values: Union[List[float], pd.Series]) -> dict:
        """
        최근 시계열 값들을 받아서 급격 증가 감지

        Args:
            recent_values: 시간 순서대로 정렬된 메트릭 값 리스트
                           (마지막이 가장 최근)
                           최소 baseline_window + 1 개 필요

        Returns:
            {
              'spike':        bool,
              'change_rate':  float (%),   # 양수면 증가, 음수면 감소
              'baseline':     float,
              'current':      float,
              'reason':       str,
            }
        """
        values = list(recent_values)

        # 데이터 부족 → 판단 불가, 안전하게 spike=False
        if len(values) < self.baseline_window + 1:
            return {
                'spike': False,
                'change_rate': 0.0,
                'baseline': 0.0,
                'current': values[-1] if values else 0.0,
                'reason': f'insufficient data: need {self.baseline_window + 1} '
                          f'points, got {len(values)}',
            }

        # baseline = 마지막 값 제외한 직전 N개 평균
        baseline = sum(values[-(self.baseline_window + 1):-1]) / self.baseline_window
        current = values[-1]

        # baseline이 너무 작으면 변화율 계산이 의미 없음 (0에서 1로 가도 무한대 %)
        if baseline < self.min_baseline:
            return {
                'spike': False,
                'change_rate': 0.0,
                'baseline': baseline,
                'current': current,
                'reason': f'baseline too small ({baseline:.2f} < {self.min_baseline}), skip',
            }

        # 변화율 계산
        change_rate = ((current - baseline) / baseline) * 100
        is_spike = change_rate >= self.threshold_pct

        return {
            'spike': bool(is_spike),
            'change_rate': float(change_rate),
            'baseline': float(baseline),
            'current': float(current),
            'reason': (
                f'{"SPIKE" if is_spike else "normal"}: '
                f'current={current:.1f}, baseline(last {self.baseline_window})={baseline:.1f}, '
                f'change={change_rate:+.1f}% '
                f'({"≥" if is_spike else "<"} {self.threshold_pct}%)'
            ),
        }


# ============================================================
# 단독 실행 테스트
# ============================================================
if __name__ == '__main__':
    print('=' * 60)
    print('SpikeDetector 테스트')
    print('=' * 60)

    detector = SpikeDetector(threshold_pct=30.0, baseline_window=5)

    # 케이스 1: 평탄한 패턴 (정상)
    print('\n[케이스 1] 평탄한 CPU 사용률 (40~45%)')
    case1 = [40, 42, 41, 43, 44, 45]
    r1 = detector.check(case1)
    print(f'    values: {case1}')
    print(f'    spike: {r1["spike"]}, change: {r1["change_rate"]:+.1f}%')
    print(f'    reason: {r1["reason"]}')

    # 케이스 2: 갑작스러운 폭증 (바이럴 스파이크)
    print('\n[케이스 2] 평탄 → 갑자기 2배 폭증')
    case2 = [40, 42, 41, 43, 44, 95]
    r2 = detector.check(case2)
    print(f'    values: {case2}')
    print(f'    spike: {r2["spike"]}, change: {r2["change_rate"]:+.1f}%')
    print(f'    reason: {r2["reason"]}')

    # 케이스 3: 점진적 증가 (Prophet 영역, 급격 감지는 잡지 않음)
    print('\n[케이스 3] 점진적 증가 (10% 정도)')
    case3 = [40, 42, 44, 46, 48, 49]
    r3 = detector.check(case3)
    print(f'    values: {case3}')
    print(f'    spike: {r3["spike"]}, change: {r3["change_rate"]:+.1f}%')
    print(f'    reason: {r3["reason"]}')

    # 케이스 4: 데이터 부족
    print('\n[케이스 4] 데이터 부족 (3개만)')
    case4 = [40, 42, 95]
    r4 = detector.check(case4)
    print(f'    values: {case4}')
    print(f'    spike: {r4["spike"]}')
    print(f'    reason: {r4["reason"]}')

    # 케이스 5: 실제 B팀 데이터로 테스트
    print('\n[케이스 5] B팀 sudden_traffic.csv 초반 (1초당 요청)')
    raw = pd.read_csv(Path(__file__).parent.parent / 'data' / 'sudden_traffic.csv', low_memory=False)
    raw['ts'] = pd.to_datetime(raw['timeStamp'], unit='ms')
    per_sec = raw.groupby(raw['ts'].dt.floor('1s')).size().reset_index()
    # 첫 10초만 보기 (11 → 200 폭증 구간)
    first10 = per_sec.head(10)
    print(f'    values: {list(first10[0])}')
    r5 = detector.check(first10[0].tolist())
    print(f'    spike: {r5["spike"]}, change: {r5["change_rate"]:+.1f}%')
    print(f'    reason: {r5["reason"]}')

    print('\n' + '=' * 60)
    print('Done')
    print('=' * 60)
