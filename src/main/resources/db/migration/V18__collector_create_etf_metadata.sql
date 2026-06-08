-- ROLLBACK_SAFE: true
-- SPEC-ETF-001: etf_metadata table
-- Stores ETF-specific metadata: underlying index, leverage, inverse, hedged, trading halt.
-- One row per stock (UNIQUE on stock_id).
CREATE TABLE etf_metadata (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    stock_id                BIGINT          NOT NULL,
    underlying_index_code   VARCHAR(50)     COMMENT 'Underlying index code (e.g., 069500)',
    leverage                TINYINT         NOT NULL DEFAULT 1 COMMENT 'Leverage multiplier (absolute value)',
    inverse                 BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'True if inverse ETF',
    hedged                  BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'True if currency-hedged',
    tr_stop                 BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'True if trading is halted',
    created_at              DATETIME(6)     NOT NULL,
    updated_at              DATETIME(6)     NOT NULL,
    CONSTRAINT pk_etf_metadata PRIMARY KEY (id),
    CONSTRAINT uk_etf_metadata_stock UNIQUE (stock_id),
    CONSTRAINT fk_etf_metadata_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
