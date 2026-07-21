-- ROLLBACK_SAFE: true
-- 이유: nullable 컬럼 추가(ADD COLUMN ... NULL). 기존 행에 영향 없고, 구버전 앱(엔티티가 두 컬럼 미매핑)은
--       ddl-auto=validate 대상이 아닌 신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다.
--       데이터 손실 없음.
--
-- SPEC-COLLECTOR-WLSYNC-008 (T-001, REQ-WLSYNC-140):
-- 상장폐지·거래정지 감지 결과를 반영할 상폐일자·상폐사유 메타데이터 컬럼을 stocks에 추가한다.
-- active(시장 유효성)는 기존 boolean 컬럼을 그대로 재사용(부활)하므로 이 마이그레이션에서는 다루지 않는다.
-- delisted_at은 set-only(비가역) 값으로, 애플리케이션 레이어에서만 그 불변식을 강제한다(DB 제약 아님).
ALTER TABLE stocks
    ADD COLUMN delisted_at DATE NULL COMMENT '상장폐지일자. set-only(비가역) — 최초 감지값 유지, NULL=상폐 아님 (SPEC-COLLECTOR-WLSYNC-008)',
    ADD COLUMN delisting_reason VARCHAR(30) NULL COMMENT '상장폐지 사유(BANKRUPTCY/MERGER/VOLUNTARY/ETF_TERMINATION/UNKNOWN). 초기값 UNKNOWN — DART 공시 기반 자동 분류는 스코프 밖 (SPEC-COLLECTOR-WLSYNC-008)';
