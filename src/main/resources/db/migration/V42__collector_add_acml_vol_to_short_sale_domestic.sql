-- ROLLBACK_SAFE: true
-- 이유: NULL 허용 컬럼 추가(ADD COLUMN ... BIGINT NULL). 기존 행은 자동으로 NULL로 채워지며 데이터
--       손실이 없고, 구버전 앱(엔티티가 이 컬럼을 미매핑)은 ddl-auto=validate 대상이 아닌 신규
--       컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다.
--
-- SPEC-COLLECTOR-SHORTSALE-ACMLVOL-001 (관련: aaa-infra#61):
-- KIS daily-short-sale(FHPST04830000) 응답의 acml_vol(누적 거래량, 수정주가 기준)을 원본 그대로
-- 저장한다. ssts_vol_rlim(공매도 거래량 비중)이 원주 기준 ssts_cntg_qty를 수정주가 기준 acml_vol로
-- 나눈 값이라 분할·병합 이력이 있는 종목에서 100%를 초과하는 왜곡이 발생하는데(#61, 097230 외
-- 659행), 이 컬럼은 그 진단·정정에 필요한 분모를 확보할 뿐 ssts_vol_rlim 자체를 이 마이그레이션에서
-- 정정하지 않는다. 전방향 전용(forward-only) — 기존 행은 acml_vol NULL로 남고 백필하지 않는다.
ALTER TABLE short_sale_domestic
    ADD COLUMN acml_vol BIGINT NULL
        COMMENT 'KIS 누적 거래량(acml_vol, 수정주가 기준, 원본 무변환). 결측 시 NULL(과거 행
미백필). ssts_vol_rlim 진단용 분모 (aaa-infra#61)';
