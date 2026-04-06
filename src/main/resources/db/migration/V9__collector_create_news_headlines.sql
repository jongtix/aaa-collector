CREATE TABLE news_headlines (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    serial_no       VARCHAR(20)     NOT NULL COMMENT '내용 조회용 일련번호 (cntt_usiq_srno)',
    published_at    DATETIME        NOT NULL COMMENT '작성일시 (data_dt + data_tm)',
    provider_code   VARCHAR(1)      NOT NULL COMMENT '뉴스 제공 업체 코드',
    title           VARCHAR(400)    NOT NULL COMMENT 'HTS 공시 제목 내용',
    category_code   VARCHAR(8)      NULL     COMMENT '뉴스 대구분 코드',
    source          VARCHAR(20)     NULL     COMMENT '자료원',
    stock_code1     VARCHAR(9)      NULL     COMMENT '관련 종목코드1',
    stock_code2     VARCHAR(9)      NULL     COMMENT '관련 종목코드2',
    stock_code3     VARCHAR(9)      NULL     COMMENT '관련 종목코드3',
    stock_code4     VARCHAR(9)      NULL     COMMENT '관련 종목코드4',
    stock_code5     VARCHAR(9)      NULL     COMMENT '관련 종목코드5',
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_news_headlines_serial (serial_no),
    INDEX idx_news_headlines_published_at (published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
