CREATE TABLE daily_ohlcv (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id      BIGINT          NOT NULL COMMENT 'stocks.id FK',
    trade_date    DATE            NOT NULL COMMENT '거래일 (ML 조인키/정렬 기준)',
    open_price    DECIMAL(18, 4)  NOT NULL COMMENT '시가',
    high_price    DECIMAL(18, 4)  NOT NULL COMMENT '고가',
    low_price     DECIMAL(18, 4)  NOT NULL COMMENT '저가',
    close_price   DECIMAL(18, 4)  NOT NULL COMMENT '종가',
    volume        BIGINT          NOT NULL DEFAULT 0 COMMENT '거래량',
    trading_value BIGINT          NOT NULL DEFAULT 0 COMMENT '거래대금 (원)',
    created_at    DATETIME        NOT NULL,
    updated_at    DATETIME        NOT NULL,
    CONSTRAINT fk_daily_ohlcv_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_daily_ohlcv (stock_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_daily_ohlcv_trade_date ON daily_ohlcv (trade_date);
