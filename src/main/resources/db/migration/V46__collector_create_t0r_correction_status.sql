-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE) + 초기 행 1개 INSERT. 기존 테이블·엔티티에 영향 없고,
--       다른 스키마와 독립적이므로 롤백(테이블 삭제) 시에도 데이터 정합성 문제가 없다. 앱 계정은
--       이 테이블을 SELECT만 하므로(REQ-T0R-045 게이트 조회) 신규 GRANT가 불요하다(기존 스키마
--       레벨 SELECT로 충분, Tier 분류 대상 아님).
--
-- SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 (M8, REQ-T0R-043~046):
-- 근본원인 B(라이브 재조회 T0 리비전) 소급 정정이 "닫히는 창" 구간(REQ-T0R-011) 전체에 대해
-- 완료됐음을 표시하는 단일 행 완료 마커 테이블이다. completed_at이 NULL인 동안 M7의 코드 레벨
-- 게이트가 [2026-06-29, closing_window_end_date] 구간에 속하는 Track 1 ②단계·Track 2의
-- recompute 호출을 defer한다(REQ-T0R-044). completed_at이 NOT NULL이 되면 이 검사를
-- 생략한다(REQ-T0R-045). 오퍼레이터가 (a) 이 서비스와 (b) daily_ohlcv/investor_trend 진단
-- 스크립트(M5, 수동 SQL) 양쪽 모두 닫히는 창 전체에 대해 완료됐음을 확인한 후, root 권한으로
-- `UPDATE t0r_correction_status SET completed_at = NOW()`를 수동 실행한다(REQ-T0R-046). 앱은
-- UPDATE 경로를 갖지 않는다.
--
-- id 컬럼: 이 테이블은 정의상 정확히 1행만 존재한다(단일 행 완료 마커). 다른 도메인 테이블과
-- 동일하게 PRIMARY KEY를 두어 InnoDB 클러스터드 인덱스를 명시적으로 확보하되, 자연키가 없으므로
-- DEFAULT 1의 기술적 PK로 단일 행 성격을 드러낸다(강제 CHECK 제약은 이 SPEC 범위 밖).
--
-- closing_window_end_date 시딩값: plan.md는 "M2 배포일"로 시딩하도록 지시하나, 오케스트레이터
-- 판단으로 M8을 M4 직후·M2 프로덕션 배포 이전에 실행하므로 실제 M2 배포일을 아직 확정할 수 없다.
-- 이 마이그레이션 작성일(2026-08-06)로 잠정 대체하며, M7 구현 착수 시점에 실제 M2 배포일 기준으로
-- 이 값의 재검토가 필요하다(오퍼레이터 UPDATE로 조정 가능 — 스키마 변경 불요, REQ-T0R-043).
CREATE TABLE t0r_correction_status
(
    id                       TINYINT   NOT NULL DEFAULT 1 COMMENT '기술적 PK — 이 테이블은 정의상 정확히 1행만 존재한다',
    closing_window_end_date  DATE      NOT NULL COMMENT '근본원인 B 소급 정정 대상 구간의 종료일. [2026-06-29, 이 값] 구간이 M7 게이트 대상 (REQ-T0R-043). 잠정값 2026-08-06 — M7 착수 시 재검토',
    completed_at             TIMESTAMP NULL     COMMENT 'NULL이면 게이트 활성(defer 발생). 오퍼레이터가 root 권한으로 수동 UPDATE — 앱은 UPDATE 경로 없음 (REQ-T0R-045/-046)',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='T0R(근본원인 B) 소급 정정 완료 마커 — 단일 행 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 M8)';

INSERT INTO t0r_correction_status (id, closing_window_end_date, completed_at)
VALUES (1, '2026-08-06', NULL);
