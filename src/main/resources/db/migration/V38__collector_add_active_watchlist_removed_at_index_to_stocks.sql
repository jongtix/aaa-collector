-- ROLLBACK_SAFE: true
-- 이유: 인덱스 추가만 수행(DDL). 데이터 변경 없음, InnoDB 온라인 DDL(ALGORITHM=INPLACE)로 무중단 적용.
--       롤백은 DROP INDEX로 가능하며 앱 동작에는 영향 없다(쿼리 정확성은 인덱스 유무와 무관, 성능만 영향).
--
-- SPEC-COLLECTOR-WLSYNC-008 /moai review 후속(W-001):
-- findAllActive* 5종 JPQL이 `active = true AND watchlist_removed_at IS NULL` 2축 필터를 사용하도록
-- 부활했으나(V37 이후), active 컬럼에는 인덱스가 없었다(watchlist_removed_at 단독 인덱스만 V17에 존재).
-- 23개+ 배치·백필 소비처가 이 쿼리를 매 사이클 호출하므로 복합 인덱스를 추가한다.
ALTER TABLE stocks
    ADD INDEX idx_stocks_active_watchlist_removed_at (active, watchlist_removed_at),
    ALGORITHM=INPLACE, LOCK=NONE;
