package com.aaa.collector.market.indicator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 시장 지표 소스 체인 Fallback 가시성 메트릭 (SPEC-COLLECTOR-MARKETIND-002).
 *
 * <p>소스 체인의 Fallback 이벤트·성공·탈진을 Prometheus 메트릭으로 노출한다.
 *
 * <ul>
 *   <li>{@value #FALLBACK_TOTAL} — Fallback 이벤트 카운터 (indicator, from_source, to_source, reason)
 *   <li>{@value #ACTIVE_SOURCE} — 현재 성공 소스 Gauge 1/0 (indicator, source)
 *   <li>{@value #LAST_SUCCESS} — 마지막 성공 epoch 초 Gauge (indicator, source)
 *   <li>{@value #EXHAUSTED_TOTAL} — 전 소스 탈진 카운터 (indicator, method)
 * </ul>
 */
// @MX:ANCHOR: [AUTO] 시장지표 소스 체인 계측 진입점 — VixCollectionService/UsdkrwCollectionService/백필 공유
// @MX:REASON: [AUTO] fan_in >= 3 (MarketIndicatorSourceChain, vixChain, usdkrwChain)
// @MX:SPEC: SPEC-COLLECTOR-MARKETIND-002
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorMetrics {

    static final String FALLBACK_TOTAL = "aaa_collector_market_indicator_fallback_total";
    static final String ACTIVE_SOURCE = "aaa_collector_market_indicator_active_source";
    static final String LAST_SUCCESS = "aaa_collector_market_indicator_source_last_success_seconds";
    static final String EXHAUSTED_TOTAL = "aaa_collector_market_indicator_exhausted_total";

    private static final String TAG_INDICATOR = "indicator";
    private static final String TAG_SOURCE = "source";

    /** Known (indicator → sources) — @PostConstruct 사전 등록용. */
    private static final Map<String, List<String>> KNOWN_SOURCES =
            Map.of(
                    "VIX", List.of("CBOE", "FRED", "YAHOO_VIX"),
                    "USDKRW", List.of("KOREAEXIM", "YAHOO_USDKRW"));

    private final MeterRegistry registry;
    private final Clock clock;

    /** indicator → (source → 마지막 성공 epoch 초). */
    private final Map<String, Map<String, AtomicLong>> lastSuccessMap = new ConcurrentHashMap<>();

    /** indicator → (source → active 1/0). */
    private final Map<String, Map<String, AtomicLong>> activeSourceMap = new ConcurrentHashMap<>();

    /** 모든 known (indicator, source) 조합을 0.0으로 사전 등록한다 (REQ-005). */
    @PostConstruct
    public void init() {
        KNOWN_SOURCES.forEach(
                (indicator, sources) ->
                        sources.forEach(
                                source -> {
                                    AtomicLong lastSuccessHolder =
                                            getOrCreateLastSuccess(indicator, source);
                                    AtomicLong activeHolder = getOrCreateActive(indicator, source);
                                    Gauge.builder(
                                                    LAST_SUCCESS,
                                                    lastSuccessHolder,
                                                    AtomicLong::doubleValue)
                                            .tag(TAG_INDICATOR, indicator)
                                            .tag(TAG_SOURCE, source)
                                            .register(registry);
                                    Gauge.builder(
                                                    ACTIVE_SOURCE,
                                                    activeHolder,
                                                    AtomicLong::doubleValue)
                                            .tag(TAG_INDICATOR, indicator)
                                            .tag(TAG_SOURCE, source)
                                            .register(registry);
                                }));
    }

    /**
     * Fallback 이벤트를 기록한다 (REQ-001).
     *
     * @param indicator 지표 식별자 ("VIX" / "USDKRW")
     * @param fromSource Fallback 이전 소스
     * @param toSource Fallback 이후 소스
     * @param reason 전환 사유 ("empty_result" / "error")
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 메트릭 실패 격리 — REQ-012
    public void recordFallback(
            String indicator, String fromSource, String toSource, String reason) {
        try {
            Counter.builder(FALLBACK_TOTAL)
                    .tag(TAG_INDICATOR, indicator)
                    .tag("from_source", fromSource)
                    .tag("to_source", toSource)
                    .tag("reason", reason)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.warn("[market-ind-metrics] Fallback 메트릭 기록 실패 — 무시", e);
        }
    }

    /**
     * 소스 성공을 기록한다 (REQ-002, REQ-003).
     *
     * <p>last_success를 현재 epoch 초로 갱신하고, 같은 indicator의 모든 소스 active_source를 0으로 리셋한 후 해당 소스를 1로
     * 설정한다.
     *
     * @param indicator 지표 식별자
     * @param source 성공한 소스 이름
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 메트릭 실패 격리 — REQ-012
    public void recordSuccess(String indicator, String source) {
        try {
            getOrCreateLastSuccess(indicator, source).set(clock.instant().getEpochSecond());
            activeSourceMap
                    .computeIfAbsent(indicator, k -> new ConcurrentHashMap<>())
                    .values()
                    .forEach(v -> v.set(0L));
            getOrCreateActive(indicator, source).set(1L);
        } catch (Exception e) {
            log.warn("[market-ind-metrics] Success 메트릭 기록 실패 — 무시", e);
        }
    }

    /**
     * 전 소스 탈진을 기록한다 (REQ-004).
     *
     * @param indicator 지표 식별자
     * @param method 탈진 발생 메서드 ("daily" / "history")
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 메트릭 실패 격리 — REQ-012
    public void recordExhausted(String indicator, String method) {
        try {
            Counter.builder(EXHAUSTED_TOTAL)
                    .tag(TAG_INDICATOR, indicator)
                    .tag("method", method)
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.warn("[market-ind-metrics] Exhausted 메트릭 기록 실패 — 무시", e);
        }
    }

    private AtomicLong getOrCreateLastSuccess(String indicator, String source) {
        return lastSuccessMap
                .computeIfAbsent(indicator, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(source, k -> new AtomicLong(0L));
    }

    private AtomicLong getOrCreateActive(String indicator, String source) {
        return activeSourceMap
                .computeIfAbsent(indicator, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(source, k -> new AtomicLong(0L));
    }
}
