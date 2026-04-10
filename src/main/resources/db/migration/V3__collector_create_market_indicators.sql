-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE market_indicators (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    indicator_code VARCHAR(20)     NOT NULL COMMENT '지표 코드 (USDKRW, VIX)',
    trade_date     DATE            NOT NULL COMMENT '거래일',
    open_value     DECIMAL(20, 4)           COMMENT '시가',
    high_value     DECIMAL(20, 4)           COMMENT '고가',
    low_value      DECIMAL(20, 4)           COMMENT '저가',
    close_value    DECIMAL(20, 4)  NOT NULL COMMENT '종가',
    source         VARCHAR(30)              COMMENT '데이터 소스 (KOREAEXIM, CBOE, FRED 등)',
    created_at     DATETIME        NOT NULL,
    updated_at     DATETIME        NOT NULL,
    UNIQUE KEY uk_market_indicators (indicator_code, trade_date),
    KEY idx_market_indicators_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
