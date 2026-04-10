-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE financials (
    id                          BIGINT          NOT NULL AUTO_INCREMENT,
    stock_id                    BIGINT          NOT NULL,
    period_type                 VARCHAR(10)     NOT NULL COMMENT 'ANNUAL / QUARTERLY',
    period_date                 DATE            NOT NULL COMMENT '결산년월 (해당 월의 1일, YYYY-MM-01)',
    revenue_growth              DECIMAL(12,4)   NULL     COMMENT '매출액 증가율 (%)',
    operating_profit_growth     DECIMAL(12,4)   NULL     COMMENT '영업이익 증가율 (%)',
    net_income_growth           DECIMAL(12,4)   NULL     COMMENT '순이익 증가율 (%)',
    roe                         DECIMAL(12,4)   NULL     COMMENT 'ROE (%)',
    eps                         BIGINT          NULL     COMMENT 'EPS (원)',
    sps                         BIGINT          NULL     COMMENT '주당매출액 (원)',
    bps                         BIGINT          NULL     COMMENT 'BPS (원)',
    retention_rate              DECIMAL(12,4)   NULL     COMMENT '유보비율 (%)',
    debt_ratio                  DECIMAL(12,4)   NULL     COMMENT '부채비율 (%)',
    created_at                  DATETIME        NOT NULL,
    updated_at                  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_financials (stock_id, period_type, period_date),
    CONSTRAINT fk_financials_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
