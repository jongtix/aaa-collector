-- REQ-GRADE-001: 거래대금 순위 스냅샷 테이블 (v2.0.0)
-- 목적: 시장별 일별 RAW 순위 엔트리 영속화 — percentile은 classify 시점 재계산
-- KRX snapshot_date = KST 날짜 (04:00 KST captured_at 기준)
-- US  snapshot_date = ET  날짜 (16:00 KST captured_at → America/New_York 변환)
CREATE TABLE ranking_snapshots (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    market       VARCHAR(10)  NOT NULL COMMENT '시장 구분 (KRX/US)',
    snapshot_date DATE         NOT NULL COMMENT '시장 기준 zone의 캡처 cycle 일자 (C1)',
    symbol       VARCHAR(20)  NOT NULL COMMENT '종목 코드',
    rank_value   DOUBLE       NOT NULL COMMENT '순위 결정 값 (거래대금 또는 역순위 값)',
    rank_position INT         NOT NULL COMMENT '원본 순위 위치',
    captured_at  DATETIME(6)  NOT NULL COMMENT '스냅샷 캡처 시각 (UTC 저장)',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- C2: 동일 cycle 재실행 멱등 보장 — 시장+날짜+종목 유니크
    UNIQUE KEY uk_ranking_snapshots_market_date_symbol (market, snapshot_date, symbol),
    -- 시장+날짜 기반 최신 스냅샷 조회용 인덱스
    INDEX idx_ranking_snapshots_market_date (market, snapshot_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '거래대금 순위 스냅샷 — GRADE-003 v2.0.0';
