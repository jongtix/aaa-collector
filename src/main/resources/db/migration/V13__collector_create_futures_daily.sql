CREATE TABLE futures_daily (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    series_code     VARCHAR(10)     NOT NULL COMMENT '선물 시리즈 코드 (ES, NQ, CL, VX)',
    contract_code   VARCHAR(20)     NOT NULL COMMENT '계약 코드 (ESM24, ESZ24 등) 또는 CONTINUOUS',
    exchange_code   VARCHAR(10)     NOT NULL COMMENT '거래소 코드 (CME 등)',
    trade_date      DATE            NOT NULL COMMENT '거래일',
    open            DECIMAL(18,6)   NULL     COMMENT '시가',
    high            DECIMAL(18,6)   NULL     COMMENT '고가',
    low             DECIMAL(18,6)   NULL     COMMENT '저가',
    close           DECIMAL(18,6)   NULL     COMMENT '종가 (체결가격)',
    volume          BIGINT          NULL     COMMENT '누적거래수량',
    open_interest   BIGINT          NULL     COMMENT '미결제약정',
    is_continuous   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '연속선물(롤오버 기준) 여부',
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_futures_daily (series_code, contract_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
