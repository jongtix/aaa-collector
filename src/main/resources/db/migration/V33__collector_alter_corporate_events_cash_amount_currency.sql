-- ROLLBACK_SAFE: false (forward-only)
-- 이유: cash_amount BIGINT→DECIMAL(15,5)는 무손실 전진(정수→소수 확대). 그러나 신규 currency_code
--       컬럼·event_subtype NOT NULL·4컬럼 unique key를 참조하는 앱이 배포된 뒤 스키마만 되돌리면
--       런타임 오류가 나므로 forward-only로 둔다. 대상 행 소수(국내 4·해외 52 등)뿐이고 값 손실이
--       없어 롤백이 필요한 장애 시나리오가 없다. 긴급 복구는 앱을 이전 버전으로 되돌린 뒤 수동 DDL로 역전.
-- SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-040/041/045

-- (1) cash_amount 정밀도 확장 + currency_code 신규 컬럼 (REQ-ODA-040/041)
ALTER TABLE corporate_events
    MODIFY COLUMN cash_amount DECIMAL(15,5) NULL COMMENT '현금배당금 (통화=currency_code)',
    ADD COLUMN currency_code VARCHAR(3) NULL COMMENT '배당 통화 (KRW/USD 등)' AFTER cash_amount;

-- 경로 A: event_subtype를 4컬럼 unique key 구성원으로 승격하기 위한 NOT NULL 전환 (REQ-ODA-045).
-- (2) 기존 잔여 NULL 백필 — 현 3개 writer는 non-null을 채우나 과거 데이터의 방어적 백필.
UPDATE corporate_events SET event_subtype = 'UNKNOWN' WHERE event_subtype IS NULL;

-- (3) NOT NULL 전환.
ALTER TABLE corporate_events
    MODIFY COLUMN event_subtype VARCHAR(20) NOT NULL COMMENT '이벤트 세부 유형(일반배당/특별배당/분할/병합 등) — unique key 구성원';

-- (4) unique key를 3컬럼 → 4컬럼으로 재구성(동일일자 03+75 별도 행 보존).
ALTER TABLE corporate_events
    DROP INDEX uk_corporate_events,
    ADD CONSTRAINT uk_corporate_events UNIQUE (stock_id, event_type, event_date, event_subtype);
