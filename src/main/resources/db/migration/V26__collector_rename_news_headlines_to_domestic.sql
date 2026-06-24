-- ROLLBACK_SAFE: false
-- 이유: 테이블·인덱스 RENAME. 구버전 앱(news_headlines 참조)은 RENAME 후 table-not-found가 되므로 롤백 비호환.
-- RD-1: 국내/해외 뉴스 대칭 명명(domestic_news_headlines / overseas_news_headlines)을 위해 기존 테이블을 개명한다.
-- 데이터는 RENAME으로 보존된다(복사 없음).
RENAME TABLE news_headlines TO domestic_news_headlines;

-- NEW-1: 엔티티가 @UniqueConstraint(name="...")로 제약명을 실제 선언하므로 엔티티-DB 제약명 정합을 위해 인덱스명도 함께 개명.
ALTER TABLE domestic_news_headlines
    RENAME INDEX uk_news_headlines_serial TO uk_domestic_news_headlines_serial;

-- W2: 보조 인덱스(V9 idx_news_headlines_published_at)도 테이블 RENAME에 맞춰 개명해 인덱스명 정합 유지.
ALTER TABLE domestic_news_headlines
    RENAME INDEX idx_news_headlines_published_at TO idx_domestic_news_headlines_published_at;
