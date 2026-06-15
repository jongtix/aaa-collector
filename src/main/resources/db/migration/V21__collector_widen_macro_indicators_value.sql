-- ROLLBACK_SAFE: true
-- 이유: 컬럼 폭 확대(DECIMAL(18,8) → DECIMAL(24,8)). 기존 값 보존, 폭 축소 아님.
--       증시자금 원 정규화(×10^8) 수용 — 예탁금 ~10^14 원이 기존 정수부 10자리를 초과하므로 16자리로 확대.
--       참조: REQ-BATCH3-045, SPEC-COLLECTOR-BATCH-003 §2.5 D-NEED-1.
ALTER TABLE macro_indicators
    MODIFY value DECIMAL(24, 8) NOT NULL COMMENT '지표 값 (금액 지표: 원 정규화, 금리: %, 지수: 포인트)';
