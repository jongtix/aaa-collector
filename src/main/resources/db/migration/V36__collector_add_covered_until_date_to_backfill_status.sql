-- ROLLBACK_SAFE: true
-- 이유: nullable 컬럼 추가(ADD COLUMN ... NULL). 기존 행에 영향 없고, 구버전 앱(엔티티가 covered_until_date
--       미매핑)은 ddl-auto=validate 대상이 아닌 신규 컬럼을 무시하므로 롤백 시에도 앱-스키마 정합이 유지된다.
--       데이터 손실 없음.
--
-- SPEC-COLLECTOR-BACKFILL-011 (TASK-001):
-- backfill_status에 covered_until_date를 도입해 "연속 커버 상단 경계"를 별도로 추적한다. 기존
-- last_collected_date는 backward walk 은유로 "지금까지 도달한 최소(가장 과거) 거래일"을 의미하며 매일
-- 과거 방향으로 전진(감소)한다. 반대로 covered_until_date는 매일 최신 방향으로 전진(증가)하는 상단 경계이며,
-- 두 컬럼은 방향이 비대칭이다(하단=backward walk / 상단=forward walk).
--
-- 기존 행은 전부 covered_until_date IS NULL(미설정)로 남으며 소급 채움하지 않는다.
ALTER TABLE backfill_status
    ADD COLUMN covered_until_date DATE NULL COMMENT '연속 커버 상단 경계. 매일 전진(증가)하며, 하단 경계인 last_collected_date(backward walk 은유, 매일 과거 방향 전진)와 방향이 비대칭이다. NULL=미설정 (SPEC-COLLECTOR-BACKFILL-011)';
