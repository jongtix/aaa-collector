-- SPEC-COLLECTOR-DART-001: disclosures 테이블 생성
-- 목적: OpenDART 공시 메타데이터 수집 결과 저장 (watchlist 활성 종목의 공시만 적재).
-- Tier-1 (시계열/이벤트, 쓰기 전용) — SELECT, INSERT 권한만 사용. UPDATE 권한 없음(ADR-026).
-- [HARD] 멱등 삽입: INSERT IGNORE INTO disclosures ... (ON DUPLICATE KEY UPDATE 금지).
-- [HARD] GRANT 문 미포함 — 현 V*.sql GRANT 0개 관례 유지. Tier-1 권한은 DB-wide GRANT로 자동 적용.
-- 타입 규칙(TECHSPEC §4): 시각 컬럼은 DATETIME (TIMESTAMP 금지).
CREATE TABLE disclosures (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    stock_id    BIGINT          NOT NULL COMMENT 'stocks.id FK — watchlist 역해결(REQ-DART-003)',
    corp_code   VARCHAR(8)      NOT NULL COMMENT 'DART 고유번호(8자리)',
    stock_code  VARCHAR(6)      NOT NULL COMMENT 'DART 응답 stock_code(6자리)',
    corp_cls    VARCHAR(1)      NULL COMMENT '법인구분 Y/K/N/E',
    report_nm   VARCHAR(512)    NOT NULL COMMENT '보고서명 (긴 펀드명 대응 512자)',
    rcept_no    VARCHAR(14)     NOT NULL COMMENT '접수번호 14자리 — 멱등 키',
    flr_nm      VARCHAR(255)    NULL COMMENT '공시 제출인명',
    rcept_dt    DATE            NOT NULL COMMENT '접수일자 YYYYMMDD → DATE',
    rm          VARCHAR(255)    NULL COMMENT '비고',
    pblntf_ty   VARCHAR(16)     NULL COMMENT '공시 유형 (필터 옵션, REQ-DART-032)',
    created_at  DATETIME        NOT NULL,
    updated_at  DATETIME        NOT NULL,
    CONSTRAINT pk_disclosures PRIMARY KEY (id),
    -- rcept_no 14자리 전역 UNIQUE — 멱등 키(INSERT IGNORE 기준)
    CONSTRAINT uk_disclosures_rcept_no UNIQUE (rcept_no),
    -- 종목별 날짜 범위 스캔 가속
    INDEX idx_disclosures_stock_rcept_dt (stock_id, rcept_dt)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'DART 공시 메타데이터 — SPEC-COLLECTOR-DART-001';
