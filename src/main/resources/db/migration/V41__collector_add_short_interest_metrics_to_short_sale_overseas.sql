-- ROLLBACK_SAFE: true
-- 이유: nullable 컬럼 2개 추가(ADD COLUMN, 기본값 NULL). 기존 행은 NULL로 채워지고, 구버전 앱(엔티티가 이
--       컬럼을 미매핑)은 ddl-auto=validate 대상이 아닌 신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이
--       유지된다.
--
-- SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 (M2, REQ-SSOI-010):
-- FINRA consolidatedShortInterest가 제공하는 daysToCoverQuantity(커버 소요일)·averageDailyVolumeQuantity
-- (평균 일거래량)를 신규 컬럼으로 적재한다. 값이 없거나 파싱 불가해도 행 자체는 거부하지 않고 해당 컬럼만
-- NULL로 남긴다(REQ-SSOI-011). 타입 근거(api-specs/finra/01-공매도잔고.md 실측): daysToCoverQuantity는 소수
-- (예 3.39) → DECIMAL(10,2), averageDailyVolumeQuantity는 정수 규모 거래량(예 39674165) → BIGINT(기존
-- short_interest 컬럼과 동일 타입).
ALTER TABLE short_sale_overseas
    ADD COLUMN days_to_cover DECIMAL(10, 2) COMMENT 'FINRA daysToCoverQuantity — 커버 소요일(잔고/평균일거래량, REQ-SSOI-010)',
    ADD COLUMN avg_daily_volume BIGINT COMMENT 'FINRA averageDailyVolumeQuantity — 평균 일거래량(미디어 거래 제외, REQ-SSOI-010)';
