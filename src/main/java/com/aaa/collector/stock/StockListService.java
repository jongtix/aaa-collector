package com.aaa.collector.stock;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심 종목 목록 조회 서비스.
 *
 * <p>캐시 우선(cache-first) 전략: Redis 캐시 조회 → 미스 시 DB 조회 → 캐시 워밍업.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockListService {

    private final StockListCacheRepository cacheRepository;
    private final StockRepository stockRepository;

    /**
     * 관심 목록의 활성 종목 목록을 반환한다.
     *
     * <p>캐시가 존재하면 DB를 조회하지 않고 캐시 데이터를 반환한다. 캐시 미스 시 DB에서 조회하고, 조회 결과가 비어있지 않으면 캐시를 워밍업한 후 반환한다.
     *
     * @return 활성 종목 DTO 목록. null을 반환하지 않으며, DB와 캐시가 모두 비어있으면 빈 목록을 반환한다. {@code nameEn}, {@code
     *     listedDate}, {@code nameKo}는 null일 수 있다. {@code symbol}과 {@code market}은 항상 non-null이다.
     */
    // @MX:NOTE: [AUTO] 캐시 우선 → DB fallback → warm-up 흐름
    public List<CachedStock> findActiveStocks() {
        return cacheRepository.findAll().orElseGet(this::fetchFromDbAndWarmUp);
    }

    /** sync 완료 후 외부에서 캐시를 강제 갱신할 때 사용한다. */
    public void refreshCache() {
        fetchFromDbAndWarmUp();
    }

    private List<CachedStock> fetchFromDbAndWarmUp() {
        List<CachedStock> stocks =
                stockRepository.findAllActive().stream().map(CachedStock::from).toList();
        if (!stocks.isEmpty()) {
            cacheRepository.save(stocks);
            log.debug("캐시 미스 — DB {}건 조회, 캐시 워밍업 완료", stocks.size());
        } else {
            log.debug("캐시 미스 — DB 빈 목록, 캐시 워밍업 건너뜀");
        }
        return stocks;
    }
}
