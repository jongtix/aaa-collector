-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE corporate_events (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    stock_id            BIGINT          NOT NULL,
    event_type          VARCHAR(20)     NOT NULL COMMENT 'DIVIDEND / RIGHTS_ISSUE / SPLIT / EARNINGS',
    event_date          DATE            NOT NULL COMMENT '기준일 (배당 기준일, 권리락일 등)',
    event_subtype       VARCHAR(20)     NULL     COMMENT '이벤트 세부 유형 (결산배당/중간배당 등)',
    pay_date            DATE            NULL     COMMENT '현금 지급일',
    stock_pay_date      DATE            NULL     COMMENT '주식 배당 지급일',
    odd_pay_date        DATE            NULL     COMMENT '단주대금 지급일',
    cash_amount         BIGINT          NULL     COMMENT '현금배당금 (원)',
    cash_rate           DECIMAL(12,4)   NULL     COMMENT '현금배당률 (%)',
    stock_rate          DECIMAL(12,4)   NULL     COMMENT '주식배당률 (%)',
    face_value          BIGINT          NULL     COMMENT '액면가 (원)',
    stock_kind          VARCHAR(10)     NULL     COMMENT '주식종류',
    high_dividend_flag  VARCHAR(1)      NULL     COMMENT '고배당종목여부',
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_corporate_events (stock_id, event_type, event_date),
    CONSTRAINT fk_corporate_events_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
