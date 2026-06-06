package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.Market;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 종목 마스터 저장소. */
public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findBySymbolAndMarket(String symbol, Market market);

    List<Stock> findAllBySymbolIn(Collection<String> symbols);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Stock s SET s.watchlistRemovedAt = CURRENT_TIMESTAMP WHERE s.id IN :ids AND s.watchlistRemovedAt IS NULL")
    void markWatchlistRemoved(@Param("ids") Set<Long> ids);

    /**
     * 관심 목록에서 제거되지 않은 종목을 조회한다.
     *
     * <p>"활성"의 기준: {@code watchlistRemovedAt IS NULL}. {@code active} 불리언 필드는 사용하지 않는다.
     * watchlistRemovedAt이 null인 종목은 가장 최근 sync에서 제거 대상으로 마킹되지 않은 종목이다.
     */
    @Query("SELECT s FROM Stock s WHERE s.watchlistRemovedAt IS NULL")
    List<Stock> findAllActive();
}
