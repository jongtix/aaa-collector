-- ROLLBACK_SAFE: true
-- SPEC-ETF-001: etf_representative_history table
-- Append-only log of ETF representative changes per group_key.
-- INSERT-only: UPDATE and DELETE are forbidden (REQ-ETFHIST-001).
CREATE TABLE etf_representative_history (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    group_key       VARCHAR(200)    NOT NULL COMMENT 'ETF group key: {exchange}:{index_code}:{leverage}:{direction}:{hedged}',
    stock_id        BIGINT          NOT NULL COMMENT 'Newly selected representative stock',
    prev_stock_id   BIGINT          COMMENT 'Previous representative stock (NULL for first selection)',
    effective_from  DATETIME        NOT NULL COMMENT 'When this representative became effective',
    CONSTRAINT pk_etf_rep_history PRIMARY KEY (id),
    CONSTRAINT fk_etf_rep_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    CONSTRAINT fk_etf_rep_prev_stock FOREIGN KEY (prev_stock_id) REFERENCES stocks (id),
    INDEX idx_etf_rep_group_effective (group_key, effective_from DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
