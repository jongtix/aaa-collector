-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
CREATE TABLE news_headlines (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    serial_no       VARCHAR(20)     NOT NULL COMMENT '내용 조회용 일련번호 (cntt_usiq_srno)',
    published_at    DATETIME        NOT NULL COMMENT '작성일시 (data_dt + data_tm)',
    provider_code   VARCHAR(1)      NOT NULL COMMENT '뉴스 제공 업체 코드',
    title           VARCHAR(400)    NOT NULL COMMENT 'HTS 공시 제목 내용',
    category_code   VARCHAR(8)      NULL     COMMENT '뉴스 대구분 코드',
    source          VARCHAR(20)     NULL     COMMENT '자료원',
    -- KIS API(FHKST01011800)가 iscd1~iscd5 최대 5개만 반환하므로 컬럼 분리로 충분
    stock_code1     VARCHAR(9)      NULL     COMMENT '관련 종목코드1 (KIS iscd1)',
    stock_code2     VARCHAR(9)      NULL     COMMENT '관련 종목코드2 (KIS iscd2)',
    stock_code3     VARCHAR(9)      NULL     COMMENT '관련 종목코드3 (KIS iscd3)',
    stock_code4     VARCHAR(9)      NULL     COMMENT '관련 종목코드4 (KIS iscd4)',
    stock_code5     VARCHAR(9)      NULL     COMMENT '관련 종목코드5 (KIS iscd5)',
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_news_headlines_serial (serial_no),
    INDEX idx_news_headlines_published_at (published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
