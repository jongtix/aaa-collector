-- SPEC-COLLECTOR-BACKFILL-013 T5 (REQ-BACKFILL-170, -171, -172)
--
-- 목적: GROUP_B(short_sale_domestic·investor_trend·credit_balance)의 "전결손 위장" 행을 재-walk
-- 가능한 신선 상태로 리셋한다 — 코드 수정(T1~T4) 배포·검증 이전에는 이 스크립트를 실행하지 않는다.
-- 순서를 뒤집어(코드 배포 전 리셋) 실행하면 리셋된 행이 구 로직으로 재처리되어 즉시 재발한다.
--
-- [HARD] 이 파일은 의도적으로 db/migration/ 바깥(src/main/resources/scripts/ops/)에 위치한다 —
-- Flyway는 classpath:db/migration만 스캔하므로 애플리케이션 기동 시 자동 실행되지 않는다.
-- 실행은 root 계정(Tier-2 backfill_status UPDATE 권한, ADR-026) 운영 스크립트로 수동 1회 수행한다.
-- collector 계정에 신규 grant를 추가하지 않는다.
--
-- 대상 식별(일반식, 하드코딩 행 ID 없음, REQ-171):
--   status='COMPLETED' AND last_collected_date IS NULL AND attempt_count=1
--   AND data_table IN ('short_sale_domestic', 'investor_trend', 'credit_balance')
--
-- 이 조건은 정상 소진(예: short_sale_domestic 0021E0 — attempt_count=35, last_collected_date 비-NULL)을
-- attempt_count와 last_collected_date 두 조건으로 이중 배제한다(REQ-171/A7).
--
-- last_collected_date는 NULL로 유지한다 — 재실행 시 수정된 resolveAnchor(REQ-161/163/166)가
-- delisted_at/어제 기준 초기 anchor 로직을 온전히 재적용하도록 한다.
-- last_row_count는 리셋 대상 행이 구조상 이미 NULL이므로 별도로 건드리지 않는다(방어적 확인,
-- probe 판별 신호 last_row_count의 정합성을 위해 명시적으로 값을 바꾸지 않음).
UPDATE backfill_status
   SET status = 'PENDING',
       attempt_count = 0,
       stale_count = 0,
       last_error = NULL,
       covered_until_date = NULL
 WHERE status = 'COMPLETED'
   AND last_collected_date IS NULL
   AND attempt_count = 1
   AND data_table IN ('short_sale_domestic', 'investor_trend', 'credit_balance');
