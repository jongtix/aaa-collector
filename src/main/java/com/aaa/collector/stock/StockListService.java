package com.aaa.collector.stock;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 관심 종목 목록 조회 서비스.
 *
 * <p>캐시 우선(cache-first) 전략: Redis 캐시 조회 → 미스 시 DB 조회 → 캐시 워밍업.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockListService {

    private final StockListCacheRepository cacheRepository;
    private final StockRepository stockRepository;

    /**
     * 활성 종목 목록을 반환한다.
     *
     * <p>캐시가 존재하면 DB를 조회하지 않고 캐시 데이터를 반환한다. 캐시 미스 시 DB에서 조회하고 캐시를 워밍업한 후 반환한다.
     *
     * @return 활성 종목 DTO 목록
     */
    // @MX:NOTE: [AUTO] 캐시 우선 → DB fallback → warm-up 흐름
    public List<CachedStock> findActiveStocks() {
        return cacheRepository.findAll().orElseGet(this::fetchFromDbAndWarmUp);
    }

    private List<CachedStock> fetchFromDbAndWarmUp() {
        List<CachedStock> stocks =
                stockRepository.findAllActive().stream().map(CachedStock::from).toList();
        cacheRepository.save(stocks);
        return stocks;
    }
}
