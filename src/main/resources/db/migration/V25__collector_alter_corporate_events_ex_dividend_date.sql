-- ROLLBACK_SAFE: true
-- 이유: nullable 컬럼 추가. 구버전 앱은 해당 컬럼을 참조하지 않으므로 롤백 시 영향 없음.
-- RD-8 [확정: A]: 해외 현금배당 수집용 배당락일(KIS div_lock_dt → ex_dividend_date) 1컬럼만 추가.
-- rights_ex_date/delist_date 등 死컬럼은 추가하지 않는다(현금배당만 수집).
ALTER TABLE corporate_events
    ADD COLUMN ex_dividend_date DATE NULL COMMENT '배당락일 (KIS div_lock_dt)' AFTER event_date;
