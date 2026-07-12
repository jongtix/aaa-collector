package com.aaa.collector.warmstart;

import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시장 필터 기반 warm-start seed 소스 (SPEC-COLLECTOR-WARMSTART-REDIS-001 결합도 관리).
 *
 * <p>{@link ExtendedHoursWarmSource}·{@link WatchlistSyncWarmSource}와 동일한 이유로 추출됐다 — {@code
 * BatchMetricsWarmStarter}의 import/결합도 상한(PMD CouplingBetweenObjects) 회피. 시장 필터 {@code
 * List<Market>} 상수 + {@link DailyOhlcvRepository}·{@link CorporateEventRepository} 의존을 이 소스로 수렴해,
 * warm-starter가 Redis 영속화 의존({@code BatchLastLoadRepository})을 새로 편입해도 결합도 상한을 넘지 않게 한다.
 *
 * <p>각 메서드는 프록시 폴백 seed 값(테이블 최신 적재 시각)을 {@code Optional<LocalDateTime>}로 반환한다 — {@code
 * BatchMetricsWarmStarter.warm}이 Redis 우선 조회 후 값이 없을 때만 이 프록시 값을 사용한다(REQ-WSR-006).
 */
@Component
@RequiredArgsConstructor
public class MarketWarmSource {

    private static final List<Market> DOMESTIC_MARKETS =
            List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    private static final List<Market> OVERSEAS_MARKETS =
            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);

    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final CorporateEventRepository corporateEventRepository;

    /** {@code domestic-daily} 프록시 seed — 국내 시장 daily_ohlcv 최신 적재 시각. */
    Optional<LocalDateTime> domesticDailyLastLoad() {
        return dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(DOMESTIC_MARKETS);
    }

    /** {@code overseas-daily} 프록시 seed — 해외 시장 daily_ohlcv 최신 적재 시각. */
    Optional<LocalDateTime> overseasDailyLastLoad() {
        return dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(OVERSEAS_MARKETS);
    }

    /**
     * {@code overseas-rights}·{@code overseas-split} 공통 프록시 seed — 해외 시장 corporate_events 최신 적재 시각.
     *
     * <p>두 라벨 모두 동일한 시장 필터 쿼리로 seed된다(REQ-XR-018(b) — split 전용 쿼리는 희소성 때문에 신설하지 않는다).
     */
    Optional<LocalDateTime> overseasCorporateEventLastLoad() {
        return corporateEventRepository.findMaxCreatedAtByMarketsIn(OVERSEAS_MARKETS);
    }
}
