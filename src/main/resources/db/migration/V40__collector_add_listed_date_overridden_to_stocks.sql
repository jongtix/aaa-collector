-- ROLLBACK_SAFE: true
-- 이유: NOT NULL DEFAULT FALSE 컬럼 추가(ADD COLUMN ... NOT NULL DEFAULT FALSE). 기존 행은 전부 자동으로
--       FALSE로 채워지므로 데이터 손실이 없고, 구버전 앱(엔티티가 이 컬럼을 미매핑)은 ddl-auto=validate
--       대상이 아닌 신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다.
--
-- SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 (T3a, REQ-SSOG-029):
-- 운영자가 1차 자료로 판정한 상장일이 자동 동기화(Yahoo firstTradeDate 기반 하향 정정 등)에 의해 되돌려지지
-- 않도록, "이 상장일은 운영자 수동 오버라이드다"를 표시하는 컬럼을 stocks에 추가한다. 이 마이그레이션은
-- 스키마만 변경하며, 엔티티 매핑·도메인 로직은 T3b에서 별도 커밋으로 다룬다.
ALTER TABLE stocks
    ADD COLUMN listed_date_overridden BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '상장일이 운영자 수동 오버라이드인지 여부. TRUE면 자동 경로가 방향 무관 미변경 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 REQ-SSOG-029)';
