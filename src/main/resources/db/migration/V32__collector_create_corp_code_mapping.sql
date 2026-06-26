-- SPEC-COLLECTOR-DART-001: corp_code_mapping 테이블 생성
-- 목적: DART corpCode.xml에서 추출한 stock_code → corp_code 매핑 캐시 (백필 전용 lookup).
-- 방안 A (채택): 별도 캐시 테이블 — stocks 마스터와 디커플링, 상장사 3,920건만 적재.
-- Tier-1 append-only — SELECT, INSERT 권한만 사용. UPDATE 권한 없음(ADR-026).
-- [HARD] 멱등 삽입: INSERT IGNORE INTO corp_code_mapping ... (ON DUPLICATE KEY UPDATE 금지).
-- [HARD] GRANT 문 미포함 — 현 V*.sql GRANT 0개 관례 유지.
-- stock_code UNIQUE 제약이 멱등 키 — stock_code는 PK가 아님(surrogate id PK).
-- 수용된 트레이드오프: 사명변경·상장폐지 등 기존 행 변경 미반영(corp_code 불변 → 백필 기준값 안정).
-- 타입 규칙(TECHSPEC §4): 시각 컬럼은 DATETIME (TIMESTAMP 금지).
CREATE TABLE corp_code_mapping (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    stock_code      VARCHAR(6)      NOT NULL COMMENT 'DART stock_code — 멱등 키 (UNIQUE)',
    corp_code       VARCHAR(8)      NOT NULL COMMENT 'DART 고유번호 8자리',
    corp_name       VARCHAR(255)    NOT NULL COMMENT '기업명',
    modify_date     DATE            NULL COMMENT 'corpCode.xml의 modify_date (최종 변경일)',
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    CONSTRAINT pk_corp_code_mapping PRIMARY KEY (id),
    -- stock_code 단위 1행 — INSERT IGNORE 멱등 키
    CONSTRAINT uk_corp_code_mapping_stock_code UNIQUE (stock_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'DART corp_code ↔ stock_code 매핑 캐시 — SPEC-COLLECTOR-DART-001 방안 A';
