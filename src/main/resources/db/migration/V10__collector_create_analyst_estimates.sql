-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE analyst_estimates (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    stock_id            BIGINT          NOT NULL,
    trade_date          DATE            NOT NULL COMMENT '주식영업일자',
    institution_name    VARCHAR(50)     NOT NULL DEFAULT '' COMMENT '회원사명',
    opinion             VARCHAR(40)     NULL     COMMENT '투자의견',
    opinion_code        VARCHAR(2)      NULL     COMMENT '투자의견 구분코드',
    prev_opinion        VARCHAR(40)     NULL     COMMENT '직전투자의견',
    prev_opinion_code   VARCHAR(2)      NULL     COMMENT '직전투자의견 구분코드',
    target_price        BIGINT          NULL     COMMENT 'HTS 목표가격',
    prev_close          BIGINT          NULL     COMMENT '주식전일종가',
    gap_n_day           DECIMAL(12,4)   NULL     COMMENT '주식N일괴리도',
    gap_rate_n_day      DECIMAL(12,4)   NULL     COMMENT 'N일괴리율',
    gap_futures         DECIMAL(12,4)   NULL     COMMENT '주식선물괴리도',
    gap_rate_futures    DECIMAL(12,4)   NULL     COMMENT '괴리율',
    created_at          DATETIME        NOT NULL,
    updated_at          DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_analyst_estimates (stock_id, trade_date, institution_name),
    CONSTRAINT fk_analyst_estimates_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
