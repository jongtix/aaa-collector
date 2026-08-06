-- ROLLBACK_SAFE: true
-- 이유: 컬럼 COMMENT만 변경(ALTER TABLE ... MODIFY COLUMN ... COMMENT). 저장값·제약 조건
--       무변경, 데이터 손실 없음.
--
-- SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 (관련: aaa-infra#61, aaa-infra#133):
-- short_sell_acc_qty_rate는 short_sell_vol_rate와 동일한 원주/수정주가 혼합 기준 결함을
-- 갖지만, KIS의 누적 윈도우 정의가 미상이라 올바른 분모를 재계산할 근거가 없어 이 SPEC의
-- 정정 대상에서 제외한다(D3). 향후 참조자를 위해 known-distorted 상태만 문서화한다.
ALTER TABLE short_sale_domestic
    MODIFY COLUMN short_sell_acc_qty_rate DECIMAL(7,4)
        COMMENT 'KNOWN-DISTORTED (aaa-infra#61): 원주/수정주가 혼합 기준 왜곡 가능성 있음.
KIS 누적 윈도우 정의 미상으로 재계산 불가 — 정정 대상 아님(SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 D3).
short_sell_vol_rate(같은 종류 왜곡)는 daily_ohlcv 조인으로 정정됨, 이 컬럼은 미정정.';
