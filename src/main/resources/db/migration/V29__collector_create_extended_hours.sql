-- ROLLBACK_SAFE: true
CREATE TABLE extended_hours (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NOT NULL,
  session ENUM('PRE','AFTER') NOT NULL,
  trade_date DATE NOT NULL,
  ext_price DECIMAL(18,4) NOT NULL,
  reference_close DECIMAL(18,4) NOT NULL,
  source VARCHAR(10) NOT NULL,
  collected_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT fk_extended_hours_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  UNIQUE KEY uk_extended_hours (stock_id, session, trade_date),
  INDEX idx_extended_hours_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
