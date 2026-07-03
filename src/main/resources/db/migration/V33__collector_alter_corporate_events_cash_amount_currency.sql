-- ROLLBACK_SAFE: false (forward-only)
-- 이유: cash_amount BIGINT→DECIMAL(15,5)는 무손실 전진(정수→소수 확대). 그러나 신규 currency_code
--       컬럼·event_subtype NOT NULL·4컬럼 unique key를 참조하는 앱이 배포된 뒤 스키마만 되돌리면
--       런타임 오류가 나므로 forward-only로 둔다. 값 손실이 없어 롤백이 필요한 장애 시나리오가 없다.
--       긴급 복구는 앱을 이전 버전으로 되돌린 뒤 수동 DDL로 역전.
-- SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-040/041/045
--
-- [정정 2026-07-03] 최초 버전은 event_subtype NULL 백필을 위한 UPDATE 문을 포함했으나,
-- (a) 라이브 NAS DB 실측 결과 corporate_events에 NULL event_subtype 행이 0건이었고
--     (레거시 해외배당 행도 기존 코드가 caTitle="현금배당"을 non-null로 저장해왔음),
-- (b) `flyway` 계정은 ADR-026 Tier-1 불변성 설계상 UPDATE 권한을 의도적으로 갖지 않으므로
--     (aaa-infra config/mysql/initdb.d/01-init-collector.sh) 배포 시 SQL 1142로 실패했다.
-- 백필이 불필요함이 실측으로 확인되어 해당 UPDATE 문을 제거했다(GRANT 추가 대신 스크립트로 해결
-- — aaa-collector/CLAUDE.md HARD 규칙: "Tier-1 SQL 1142를 GRANT 추가로 해결하지 않는다").

-- (1) cash_amount 정밀도 확장 + currency_code 신규 컬럼 (REQ-ODA-040/041)
ALTER TABLE corporate_events
    MODIFY COLUMN cash_amount DECIMAL(15,5) NULL COMMENT '현금배당금 (통화=currency_code)',
    ADD COLUMN currency_code VARCHAR(3) NULL COMMENT '배당 통화 (KRW/USD 등)' AFTER cash_amount;

-- 경로 A: event_subtype를 4컬럼 unique key 구성원으로 승격하기 위한 NOT NULL 전환 (REQ-ODA-045).
-- (2) NOT NULL 전환. 현 3개 writer(RevSplit/국내배당/CTRGT011R)와 기존 데이터 전량이 이미
--     non-null이므로(실측 확인) 별도 백필 없이 바로 전환한다.
ALTER TABLE corporate_events
    MODIFY COLUMN event_subtype VARCHAR(20) NOT NULL COMMENT '이벤트 세부 유형(일반배당/특별배당/분할/병합 등) — unique key 구성원';

-- (3) unique key를 3컬럼 → 4컬럼으로 재구성(동일일자 03+75 별도 행 보존).
ALTER TABLE corporate_events
    DROP INDEX uk_corporate_events,
    ADD CONSTRAINT uk_corporate_events UNIQUE (stock_id, event_type, event_date, event_subtype);
