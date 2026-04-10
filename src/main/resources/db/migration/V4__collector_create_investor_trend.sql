-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE investor_trend (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_id              BIGINT   NOT NULL COMMENT 'stocks.id FK',
    trade_date            DATE     NOT NULL COMMENT '거래일',
    foreign_net_qty       BIGINT   NOT NULL DEFAULT 0 COMMENT '외국인 순매수 수량 (주)',
    institution_net_qty   BIGINT   NOT NULL DEFAULT 0 COMMENT '기관계 순매수 수량 (주)',
    individual_net_qty    BIGINT   NOT NULL DEFAULT 0 COMMENT '개인 순매수 수량 (주)',
    foreign_net_value     BIGINT   NOT NULL DEFAULT 0 COMMENT '외국인 순매수 거래대금 (원)',
    institution_net_value BIGINT   NOT NULL DEFAULT 0 COMMENT '기관계 순매수 거래대금 (원)',
    individual_net_value  BIGINT   NOT NULL DEFAULT 0 COMMENT '개인 순매수 거래대금 (원)',
    total_volume          BIGINT   NOT NULL DEFAULT 0 COMMENT '누적 거래량',
    total_trading_value   BIGINT   NOT NULL DEFAULT 0 COMMENT '누적 거래대금 (원)',
    created_at            DATETIME NOT NULL,
    updated_at            DATETIME NOT NULL,
    CONSTRAINT fk_investor_trend_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    UNIQUE KEY uk_investor_trend (stock_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
