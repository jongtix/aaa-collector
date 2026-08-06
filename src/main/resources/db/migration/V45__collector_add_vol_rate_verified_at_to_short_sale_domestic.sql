-- ROLLBACK_SAFE: true
-- 이유: NULL 허용 컬럼 추가(ADD COLUMN ... DATETIME NULL). 기존 행은 자동으로 NULL로 채워지며
--       데이터 손실이 없고, 구버전 앱(엔티티가 이 컬럼을 미매핑)은 ddl-auto=validate 대상이 아닌
--       신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다.
--
-- SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 (M8, REQ-SSVC-034/-036):
-- short_sell_vol_rate가 정정 공식(REQ-SSVC-011)으로 검증·정정된 시각을 기록하는 Track 2 마커
-- 컬럼이다. NULL이면 미검증 — Track 2(상시 재계산 스윕, REQ-SSVC-034)의 pending 대상 판별
-- 기준이자, Track 1 원자적 UPDATE(REQ-SSVC-036)가 acml_vol·rate와 함께 이 컬럼도 반영해 해당
-- 행을 즉시 Track 2 재조회 대상에서 제외시킨다. 조회 패턴
-- (acml_vol IS NOT NULL AND vol_rate_verified_at IS NULL)의 성능은 인덱스 없이 우선 관찰하고,
-- 필요 여부는 M9에서 결정한다(plan.md §M8). 전방향 전용(forward-only) — 기존 행은 NULL로 남고
-- 소급 백필하지 않는다.
ALTER TABLE short_sale_domestic
    ADD COLUMN vol_rate_verified_at DATETIME NULL
        COMMENT 'short_sell_vol_rate가 정정 공식으로 검증된 시각. NULL이면 미검증 — Track 2(상시
재계산 스윕) pending 마커 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-034/-036)';
