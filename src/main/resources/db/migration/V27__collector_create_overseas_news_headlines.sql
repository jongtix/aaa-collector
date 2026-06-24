-- ROLLBACK_SAFE: true
-- 이유: 테이블 신규 생성. 구버전 앱에서 해당 테이블을 참조하지 않으므로 롤백 시 영향 없음.
-- RD-1: 해외 뉴스 전용 Tier-1 테이블. outblock1(HHPSTH60100C1) 매핑. 멱등 저장 유니크 키 = news_key.
CREATE TABLE overseas_news_headlines (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    news_key     VARCHAR(20)  NOT NULL COMMENT '뉴스 고유키 (KIS news_key, 멱등 저장 유니크 키)',
    published_at DATETIME     NULL     COMMENT '발행 일시 (KIS data_dt + data_tm 합성)',
    info_gb      VARCHAR(1)   NULL     COMMENT '뉴스구분 (KIS info_gb, 예: e)',
    class_cd     VARCHAR(2)   NULL     COMMENT '중분류 코드 (KIS class_cd)',
    class_name   VARCHAR(20)  NULL     COMMENT '중분류명 (KIS class_name)',
    source       VARCHAR(20)  NULL     COMMENT '자료원 (KIS source)',
    nation_cd    VARCHAR(2)   NULL     COMMENT '국가코드 (KIS nation_cd)',
    exchange_cd  VARCHAR(3)   NULL     COMMENT '거래소코드 (KIS exchange_cd, 종목 무관 뉴스는 빈 문자열)',
    symbol       VARCHAR(20)  NULL     COMMENT '종목코드 (KIS symb, 종목 무관 뉴스는 빈 문자열)',
    symbol_name  VARCHAR(48)  NULL     COMMENT '종목명 (KIS symb_name, 종목 무관 뉴스는 빈 문자열)',
    title        VARCHAR(255) NOT NULL COMMENT '제목 (KIS title, 본문 없음)',
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    -- 멱등 INSERT IGNORE 보장 — news_key 유니크
    UNIQUE KEY uk_overseas_news_headlines_key (news_key),
    -- 발행 일시 기반 조회용 인덱스
    INDEX idx_published_at (published_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '해외 뉴스 제목 — SPEC-COLLECTOR-OVERSEAS-ETC-001';
