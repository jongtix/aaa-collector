-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE macro_indicators (
    id             BIGINT         AUTO_INCREMENT PRIMARY KEY,
    indicator_code VARCHAR(32)    NOT NULL COMMENT '지표 코드 (예: FRED_DGS10, KIS_COMP_01003, ECOS_BASE_RATE)',
    source         VARCHAR(10)    NOT NULL COMMENT '데이터 소스 (KIS / ECOS / FRED)',
    trade_date     DATE           NOT NULL COMMENT '기준 날짜',
    value          DECIMAL(18, 8) NOT NULL COMMENT '지표 값',
    created_at     DATETIME       NOT NULL,
    updated_at     DATETIME       NOT NULL,
    UNIQUE KEY uk_macro_indicators (indicator_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_macro_indicators_trade_date ON macro_indicators (trade_date);
