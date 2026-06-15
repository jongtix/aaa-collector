-- ROLLBACK_SAFE: false
-- 이유: TEXT로 컬럼 변환. 롤백 시 기존 VARCHAR(400) 초과 데이터가 손실될 수 있음.
ALTER TABLE news_headlines
    MODIFY title TEXT NOT NULL COMMENT 'HTS 공시 제목 내용 (KIS htsPbntTitlCntt, 무제한 길이)';
