-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE short_sale_domestic (
    id                      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    stock_id                BIGINT        NOT NULL COMMENT 'stocks.id FK',
    trade_date              DATE          NOT NULL COMMENT '영업 일자',
    short_sell_qty          BIGINT        NOT NULL DEFAULT 0 COMMENT '공매도 체결 수량 (주)',
    short_sell_vol_rate     DECIMAL(7, 4) NOT NULL DEFAULT 0 COMMENT '공매도 거래량 비중 (%)',
    short_sell_amt          BIGINT        NOT NULL DEFAULT 0 COMMENT '공매도 거래 대금 (원)',
    short_sell_amt_rate     DECIMAL(7, 4) NOT NULL DEFAULT 0 COMMENT '공매도 거래대금 비중 (%)',
    short_sell_acc_qty      BIGINT        NOT NULL DEFAULT 0 COMMENT '누적 공매도 체결 수량 (주)',
    short_sell_acc_qty_rate DECIMAL(7, 4) NOT NULL DEFAULT 0 COMMENT '누적 공매도 체결 수량 비중 (%)',
    short_sell_acc_amt      BIGINT        NOT NULL DEFAULT 0 COMMENT '누적 공매도 거래 대금 (원)',
    short_sell_acc_amt_rate DECIMAL(7, 4) NOT NULL DEFAULT 0 COMMENT '누적 공매도 거래대금 비중 (%)',
    created_at              DATETIME      NOT NULL,
    updated_at              DATETIME      NOT NULL,
    CONSTRAINT fk_short_sale_domestic_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_short_sale_domestic (stock_id, trade_date),
    KEY idx_short_sale_domestic_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
