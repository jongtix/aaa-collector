-- ROLLBACK_SAFE: true
-- 이유: nullable 컬럼 추가(ADD COLUMN ... NULL). 기존 행에 영향 없고, 구버전 앱(엔티티가 verified_at 미매핑)은
--       ddl-auto=validate 대상이 아닌 신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다. 데이터 손실 없음.
--
-- SPEC-COLLECTOR-BACKFILL-010 (REQ-BACKFILL-151, Axis 2 검증 마커):
-- GROUP_A daily_ohlcv 백필의 종료 확인 프로브가 진짜 데이터 하한 도달을 검증한 완료만 신뢰 기준선(baseline)으로
-- 승격하도록 verified_at 마커를 도입한다. 검증된 완료(verified_at IS NOT NULL)의 last_collected_date만
-- 하한 기준선으로 사용된다(REQ-152/-153) — 2026-06-28 재구축 사고처럼 오염된 완료가 스스로를 신뢰 기준으로
-- 축복하는 순환 신뢰를 차단한다.
--
-- 기존 COMPLETED 행은 전부 verified_at IS NULL(미검증)로 남으며 소급 검증하지 않는다(REQ-151).
-- backfill_status는 Tier-2(UPDATE grant 보유)이므로 verified_at 갱신은 평이한 JPA dirty-check UPDATE로 가능하며
-- DbGrantVerifier.TIER2_TABLES(5개)를 변경하지 않는다.
ALTER TABLE backfill_status
    ADD COLUMN verified_at DATETIME NULL COMMENT '종료 확인 프로브로 검증된 완료 시각(KST). NULL=미검증 (SPEC-COLLECTOR-BACKFILL-010 REQ-151)';
