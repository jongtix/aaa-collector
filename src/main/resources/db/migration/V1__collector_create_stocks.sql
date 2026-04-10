-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE stocks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(16)  NOT NULL COMMENT '종목코드 (005930, AAPL, 0001 등)',
    name_ko     VARCHAR(100)          COMMENT '한글 종목명',
    name_en     VARCHAR(100)          COMMENT '영문 종목명',
    market      VARCHAR(10)  NOT NULL COMMENT '시장 (KOSPI, KOSDAQ, NYSE, NASDAQ, AMEX, KRX, US)',
    asset_type  VARCHAR(10)  NOT NULL COMMENT '자산 유형 (STOCK, ETF, INDEX)',
    listed_date DATE                  COMMENT '상장일',
    active      BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '수집 활성 여부',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_stocks_symbol_market (symbol, market)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_stocks_market_asset ON stocks (market, asset_type);
