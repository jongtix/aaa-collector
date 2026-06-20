-- SPEC-COLLECTOR-BACKFILL-001 §4: backfill_status 테이블 생성
-- 목적: 과거 데이터 백필 슬라이딩 윈도우의 (target_type, target_code, data_table) 단위 진행 상태 관리.
-- Tier-2(상태·in-place 갱신) 테이블 — 시딩(INSERT)은 Tier-1 권한, 진행점(UPDATE)만 Tier-2 권한 필요(REQ-BACKFILL-007).
-- [HARD] GRANT 문 미포함 — 현 V*.sql GRANT 0개 관례 유지. UPDATE GRANT는 aaa-infra collector-tier2-grants.sql에서
--        스키마 생성 후 root 수동 1회 적용(REQ-BACKFILL-035, tasks T8).
-- 타입 규칙(TECHSPEC §4): 시각 컬럼은 DATETIME (TIMESTAMP 금지).
CREATE TABLE backfill_status (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    target_type         VARCHAR(32)     NOT NULL COMMENT '대상 유형 (본 SPEC은 STOCK 고정)',
    target_code         VARCHAR(32)     NOT NULL COMMENT '대상 코드 = 종목 symbol',
    data_table          VARCHAR(64)     NOT NULL COMMENT '대상 데이터 테이블명',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/IN_PROGRESS/COMPLETED/FAILED',
    last_collected_date DATE            NULL COMMENT '도달한 최소(가장 과거) 거래일 = 재개 anchor. NULL=미착수',
    stale_count         INT             NOT NULL DEFAULT 0 COMMENT '그룹 B 연속 무전진 횟수(REQ-014). 전진 시 0 리셋',
    last_row_count      INT             NULL COMMENT '직전 윈도우 응답 행 수(클램프 의심 판정, REQ-014a)',
    attempt_count       INT             NOT NULL DEFAULT 0 COMMENT '누적 윈도우 시도 횟수(관측용)',
    last_error          VARCHAR(512)    NULL COMMENT '마지막 실패 사유(REQ-030)',
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    CONSTRAINT pk_backfill_status PRIMARY KEY (id),
    -- (대상유형, 대상코드, 데이터테이블) 단위 1행 — 멱등 시딩·진행 잠금(REQ-BACKFILL-005)
    CONSTRAINT uk_backfill_status UNIQUE (target_type, target_code, data_table),
    -- 미완료 항목 스캔 가속(REQ-BACKFILL-010)
    INDEX idx_backfill_status_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '과거 데이터 백필 진행 상태 — BACKFILL-001';
