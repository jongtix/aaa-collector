-- ROLLBACK_SAFE: false
-- 이유: BIGINT → DECIMAL(20,6) 확대 변환. 기존 정수 데이터는 무손실 승격되나, 구버전 앱(엔티티가 long 매핑)은
--       DECIMAL 컬럼을 long으로 읽으려다 ddl-auto=validate 불일치로 부팅 실패하므로 롤백 시 앱-스키마 정합이 깨진다.
--       데이터 자체는 손실되지 않는다(DECIMAL → BIGINT 역변환은 소수부가 있으면 손실).
--
-- SPEC-COLLECTOR-SHORTSALE-DECIMAL-001 (REQ-SSD-001/002/014):
-- FINRA regSHO Daily 수량 필드가 2026-02-23부로 소수(최대 6자리) 정밀도로 항구 전환됨(실측 확정).
-- 무손실 정수 변환을 전제한 파서가 소수 행을 전량 skip해 라이브 수집이 침묵 실패했다.
-- short_volume/total_volume을 소수 6자리를 무손실 보존하는 DECIMAL(20,6)으로 확대한다.
-- 정수부 14자리(~100조)로 활성 종목 total_volume(수천만~수억) 대비 충분하다.
--
-- short_interest(공매도 잔고)는 대조군 전건 정수(실측)이므로 BIGINT 유지 — 증거 없는 스키마 churn 회피(REQ-SSD-014).
ALTER TABLE short_sale_overseas
    MODIFY COLUMN short_volume DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT 'FINRA 공매도 거래량 (소수 6자리 무손실)',
    MODIFY COLUMN total_volume DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT 'FINRA 전체 거래량 (소수 6자리 무손실)';
