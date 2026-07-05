package com.aaa.collector.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 실시간 틱(WebSocket) 종목 단위 계측 — 국내 구독 종목 한정 (REQ-OBSV-010/012/013).
 *
 * <p>종목별로 두 시계열을 노출한다.
 *
 * <ul>
 *   <li>{@value #COUNTER_NAME} — 누적 수신 카운터 (label: symbol, market)
 *   <li>{@value #LAST_SEEN_NAME} — 마지막 수신 시각 gauge (KST epoch 초, ADR-009)
 * </ul>
 *
 * <p>카디널리티 가드(REQ-OBSV-012): 라벨은 국내 구독 종목(ADR-003 물리 상한 102종목)으로만 생성되며, {@code market} 라벨은 항상
 * {@code domestic} 고정이다 — 해외 종목 라벨을 생성하지 않는다. 호출자는 {@link KisTickPublisher}의 단일 발행 지점에서 국내 틱일 때만
 * {@link #recordDomesticTick(String)}을 호출한다.
 *
 * <p>last-seen gauge는 무수신=stale 외부 판정의 기반이다(REQ-OBSV-013): 틱이 끊기면 gauge 값이 더 이상 갱신되지 않아 외부 판정기가 경과를
 * 평가할 수 있다.
 */
// @MX:ANCHOR: [AUTO] 틱 종목 단위 계측 진입점 — KisTickPublisher 단일 발행 지점에서 호출
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-010/012/013 — 발행 지점 + 향후 세션 매니저 등에서 fan_in 증가 예정
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
@Component
@RequiredArgsConstructor
public class TickMetrics {

    static final String COUNTER_NAME = "aaa_collector_tick_received_total";
    static final String LAST_SEEN_NAME = "aaa_collector_tick_last_seen_seconds";
    private static final String MARKET_DOMESTIC = "domestic";
    private static final String MARKET_OVERSEAS = "overseas";

    /** 해외 틱 집계용 고정 키 — symbol 라벨 미생성(카디널리티 가드, REQ-WM-015). */
    private static final String OVERSEAS_KEY = "__overseas__";

    private final MeterRegistry registry;
    private final Clock clock;

    /** symbol(국내) 또는 고정 키(해외) → 누적 수신 카운터. */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** symbol(국내) 또는 고정 키(해외) → last-seen epoch 초 (gauge가 지연 조회하는 가변 상태). */
    private final Map<String, AtomicLong> lastSeen = new ConcurrentHashMap<>();

    /**
     * 국내 구독 종목의 틱 1건 수신을 기록한다 (REQ-OBSV-010).
     *
     * <p>누적 수신 카운터를 증가시키고 last-seen gauge를 현재 KST epoch 초로 갱신한다. 동일 심볼 반복 호출은 시계열을 추가 생성하지
     * 않는다(카디널리티 가드, REQ-OBSV-012).
     *
     * @param symbol 국내 종목 코드 (단축코드)
     */
    public void recordDomesticTick(String symbol) {
        counters.computeIfAbsent(symbol, s -> registerCounter(s, MARKET_DOMESTIC)).increment();
        lastSeen.computeIfAbsent(symbol, s -> registerLastSeenGauge(s, MARKET_DOMESTIC))
                .set(clock.instant().getEpochSecond());
    }

    /**
     * 해외(미국) 틱 1건 수신을 기록한다 (SPEC-OBSV-WATERMARK-001 REQ-WM-015).
     *
     * <p>{@code market="overseas"} 라벨로 단일 집계 시계열을 갱신한다 — 미국 종목 수가 많아 symbol 라벨은 생성하지 않는다(카디널리티 가드).
     * 국내 틱 계측({@link #recordDomesticTick(String)})은 이 메서드와 무관하게 불변 유지된다.
     */
    public void recordOverseasTick() {
        counters.computeIfAbsent(OVERSEAS_KEY, s -> registerCounter(null, MARKET_OVERSEAS))
                .increment();
        lastSeen.computeIfAbsent(OVERSEAS_KEY, s -> registerLastSeenGauge(null, MARKET_OVERSEAS))
                .set(clock.instant().getEpochSecond());
    }

    private Counter registerCounter(String symbol, String market) {
        Counter.Builder builder = Counter.builder(COUNTER_NAME).tag("market", market);
        if (symbol != null) {
            builder = builder.tag("symbol", symbol);
        }
        return builder.register(registry);
    }

    private AtomicLong registerLastSeenGauge(String symbol, String market) {
        AtomicLong holder = new AtomicLong();
        Tags tags =
                symbol != null
                        ? Tags.of("symbol", symbol, "market", market)
                        : Tags.of("market", market);
        return registry.gauge(LAST_SEEN_NAME, tags, holder, AtomicLong::doubleValue);
    }
}
