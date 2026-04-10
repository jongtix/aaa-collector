-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE credit_balance (
    id                BIGINT         AUTO_INCREMENT PRIMARY KEY,
    stock_id          BIGINT         NOT NULL COMMENT 'stocks.id FK',
    trade_date        DATE           NOT NULL COMMENT '매매 일자',
    loan_new_qty      BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 신규 주수 (주)',
    loan_repay_qty    BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 상환 주수 (주)',
    loan_balance_qty  BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 잔고 주수 (주)',
    loan_new_amt      BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 신규 금액 (만원)',
    loan_repay_amt    BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 상환 금액 (만원)',
    loan_balance_amt  BIGINT         NOT NULL DEFAULT 0 COMMENT '융자 잔고 금액 (만원)',
    loan_balance_rate DECIMAL(7, 4)  NOT NULL DEFAULT 0 COMMENT '융자 잔고 비율 (%)',
    loan_supply_rate  DECIMAL(7, 4)  NOT NULL DEFAULT 0 COMMENT '융자 공여율 (%)',
    lend_new_qty      BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 신규 주수 (주)',
    lend_repay_qty    BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 상환 주수 (주)',
    lend_balance_qty  BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 잔고 주수 (주)',
    lend_new_amt      BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 신규 금액 (만원)',
    lend_repay_amt    BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 상환 금액 (만원)',
    lend_balance_amt  BIGINT         NOT NULL DEFAULT 0 COMMENT '대주 잔고 금액 (만원)',
    lend_balance_rate DECIMAL(7, 4)  NOT NULL DEFAULT 0 COMMENT '대주 잔고 비율 (%)',
    lend_supply_rate  DECIMAL(7, 4)  NOT NULL DEFAULT 0 COMMENT '대주 공여율 (%)',
    created_at        DATETIME       NOT NULL,
    updated_at        DATETIME       NOT NULL,
    CONSTRAINT fk_credit_balance_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_credit_balance (stock_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
