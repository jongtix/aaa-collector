CREATE TABLE short_sale_overseas (
    id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    stock_id              BIGINT        NOT NULL COMMENT 'stocks.id FK',
    trade_date            DATE          NOT NULL COMMENT 'FINRA Daily 기준 거래일',
    short_volume          BIGINT        NOT NULL DEFAULT 0 COMMENT 'FINRA 공매도 거래량',
    total_volume          BIGINT        NOT NULL DEFAULT 0 COMMENT 'FINRA 전체 거래량',
    short_interest        BIGINT        COMMENT 'FINRA Short Interest 잔고 수량 (LOCF 확장)',
    float_shares          BIGINT        COMMENT '유동주식 수',
    si_pct_float          DECIMAL(7, 4) COMMENT '유동주식 대비 공매도 잔고 비율 (%, LOCF 확장)',
    short_interest_date   DATE          COMMENT 'FINRA Short Interest 실제 발표일 (LOCF 기준점)',
    daily_collected_at    DATETIME      COMMENT 'FINRA Daily 수집 시점',
    interest_collected_at DATETIME      COMMENT 'FINRA Short Interest 수집 시점',
    created_at            DATETIME      NOT NULL,
    updated_at            DATETIME      NOT NULL,
    CONSTRAINT fk_short_sale_overseas_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_short_sale_overseas (stock_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
