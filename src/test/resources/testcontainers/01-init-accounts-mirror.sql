-- =============================================================================
-- Testcontainers 계정 미러 — flyway/collector 프로덕션 계정 분리 재현
-- =============================================================================
-- SPEC-COLLECTOR-DBGRANT-003 M1-T1
--
-- [SYNC HEADER — 필수 동기화 헤더, REQ-DBGRANT3-005/-031]
--   canonical 원본:
--     - aaa-infra/config/mysql/initdb.d/01-init-collector.sh (계정 생성 + Tier-1 db 단위 GRANT)
--     - aaa-infra/config/mysql/grants/collector-tier2-grants.sql (Tier-2 테이블 단위 UPDATE — 본 M1 파일에는
--       미포함. MySQL 8.4는 존재하지 않는 테이블에 대한 테이블 단위 GRANT를 거부하므로(ERROR 1146),
--       Tier-2 GRANT는 Flyway가 스키마를 만든 이후 M2의 grant 순서 훅에서 별도 적용한다 — spec §3 D4)
--   미러 기준일: 2026-07-03
--   규칙: canonical이 항상 우선한다. aaa-infra의 두 스크립트가 변경되면 이 미러 파일을
--         canonical에 맞게 갱신한다 — 미러를 근거로 canonical을 역방향 수정하지 않는다.
--
-- 프로덕션과의 차이점(의도된 것만):
--   - DB명: 프로덕션은 ${MYSQL_DATABASE}(=aaa) 환경변수 치환, 본 미러는 Testcontainers
--     MySQLContainer 기본 스키마명 `test`로 고정 치환(권한 "집합"은 축자 동일하게 유지).
--   - 비밀번호: 프로덕션은 .env.mysql 시크릿 주입, 본 미러는 테스트 전용 고정 상수(시크릿 아님).
--   - 실행 시점: 프로덕션은 root 셸에서 mysql CLI로 실행, 본 미러는 MySQLContainer의
--     /docker-entrypoint-initdb.d/ 표준 진입점을 통해 동일한 "스키마 생성 전" 시점에 실행된다.
-- =============================================================================

-- flyway 계정: DDL 전용, UPDATE 없음 (ADR-016 결정 3)
CREATE USER IF NOT EXISTS 'flyway'@'%' IDENTIFIED BY 'flyway-test-password';
GRANT SELECT, INSERT, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON test.* TO 'flyway'@'%';

-- collector 계정: 런타임 DML 전용, Tier-1 db 단위 SELECT/INSERT만 (ADR-026)
-- Tier-2 테이블 단위 UPDATE는 M2에서 스키마 생성 후 별도 훅으로 적용한다.
CREATE USER IF NOT EXISTS 'collector'@'%' IDENTIFIED BY 'collector-test-password';
GRANT SELECT, INSERT ON test.* TO 'collector'@'%';

FLUSH PRIVILEGES;
