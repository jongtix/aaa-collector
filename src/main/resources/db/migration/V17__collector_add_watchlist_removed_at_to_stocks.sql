-- ROLLBACK_SAFE: true
-- 이유: 컬럼 추가만. 기존 레코드는 NULL(현재 수집 중)로 초기화되어 첫 sync 시 자연스럽게 정합성 확보.
ALTER TABLE stocks
    ADD COLUMN watchlist_removed_at DATETIME NULL COMMENT '관심목록 제거 시각 (NULL=현재 수집 중)';

CREATE INDEX idx_stocks_watchlist_removed_at ON stocks (watchlist_removed_at);
