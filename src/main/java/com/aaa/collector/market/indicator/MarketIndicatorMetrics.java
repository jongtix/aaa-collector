package com.aaa.collector.market.indicator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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

    /**
     * Known (indicator → sources) — @PostConstruct 사전 등록용.
     *
     * <p>VIX 항목은 {@code "FRED"}를 포함하지 않는다(SPEC-COLLECTOR-MARKETIND-003 REQ-031 — FRED 제거로 죽은 게이지
     * {@code {indicator="VIX", source="FRED"}} 사전 등록 방지).
     */
    private static final Map<String, List<String>> KNOWN_SOURCES =
            Map.of(
                    "VIX", List.of("CBOE", "YAHOO_VIX"),
                    "USDKRW", List.of("KOREAEXIM", "YAHOO_USDKRW"));

    private final MeterRegistry registry;
    private final Clock clock;
    private final MarketIndicatorLastSuccessRepository lastSuccessRepository;

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
            // 성공 epoch를 1회 캡처해 게이지 stamp와 Redis 기록에 동일 값을 사용한다(REQ-WSR-018).
            long epochSeconds = clock.instant().getEpochSecond();
            getOrCreateLastSuccess(indicator, source).set(epochSeconds);
            activeSourceMap
                    .computeIfAbsent(indicator, k -> new ConcurrentHashMap<>())
                    .values()
                    .forEach(v -> v.set(0L));
            getOrCreateActive(indicator, source).set(1L);
            persistLastSuccess(indicator, source, epochSeconds);
        } catch (Exception e) {
            log.warn("[market-ind-metrics] Success 메트릭 기록 실패 — 무시", e);
        }
    }

    /**
     * 지표×소스 성공 epoch를 Redis에 best-effort로 기록한다 (REQ-WSR-018/019).
     *
     * <p>기록 실패는 게이지 기록·수집 흐름을 중단시키지 않는다 — {@link DataAccessException}을 warn 로깅 후 흡수하고 계속
     * 진행한다(SPEC-COLLECTOR-MARKETIND-002 REQ-012 격리 철학 계승).
     *
     * @param indicator 지표 식별자
     * @param source 성공한 소스 이름
     * @param epochSeconds 게이지에 stamp된 것과 동일한 UTC epoch 초
     */
    private void persistLastSuccess(String indicator, String source, long epochSeconds) {
        try {
            lastSuccessRepository.save(indicator, source, epochSeconds);
        } catch (DataAccessException e) {
            log.warn(
                    "[market-ind-metrics] 성공 시각 Redis 기록 실패 — indicator={}, source={}, 무시하고 계속 진행."
                            + " error={}",
                    indicator,
                    source,
                    e.getMessage());
        }
    }

    /**
     * 부팅 시 Redis에 영속된 마지막 성공 시각으로 {@code source_last_success} gauge를 초기화한다 (REQ-WSR-021, {@code
     * BatchMetrics#warmLastLoad} 미러).
     *
     * <p>{@code active_source}는 건드리지 않는다(REQ-WSR-023, 다음 수집 사이클이 즉시 재설정).
     *
     * @param indicator 지표 식별자
     * @param source 소스 이름
     * @param instant Redis에서 조회한 마지막 성공 시각 (UTC Instant)
     */
    public void warmLastSuccess(String indicator, String source, Instant instant) {
        getOrCreateLastSuccess(indicator, source).set(instant.getEpochSecond());
    }

    /**
     * warm-start 반복 대상 정본 열거를 노출한다 (REQ-WSR-025) — {@code MarketIndicatorMetricsWarmStarter}가 이 단일
     * 소스에서만 (indicator, source) 조합을 유래시켜야 하며 목록을 복제하지 않는다.
     *
     * @return indicator → source 목록의 불변 맵({@link #init()}과 동일한 정본 열거)
     */
    public static Map<String, List<String>> knownSources() {
        return KNOWN_SOURCES;
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
