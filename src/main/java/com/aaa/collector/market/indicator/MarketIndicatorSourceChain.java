package com.aaa.collector.market.indicator;

import static com.aaa.collector.market.indicator.SensitiveDataSanitizer.sanitize;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * 시장 지표 소스 Fallback 체인 (SPEC-COLLECTOR-MARKETIND-001 / SPEC-COLLECTOR-MARKETIND-002).
 *
 * <p>Primary 소스가 빈 결과를 반환하거나 예외를 던지면 다음 소스를 순차 시도한다(REQ-014, REQ-022). 첫 번째 성공 소스에서 반환하며, 전부 실패하면 빈
 * 리스트를 반환한다(예외 전파 없음).
 *
 * <p>소스 전환·성공·탈진 이벤트는 {@link MarketIndicatorMetrics}에 기록된다(SPEC-COLLECTOR-MARKETIND-002
 * REQ-007~011). 메트릭 기록 실패는 수집 결과에 영향을 주지 않는다(REQ-012).
 */
@Slf4j
public class MarketIndicatorSourceChain {

    // @MX:ANCHOR: [AUTO] fetchDaily/fetchHistory — 체인 진입점,
    // VixCollectionService/UsdkrwCollectionService 공유
    // @MX:REASON: [AUTO] fan_in >= 3 (VixCollectionService, UsdkrwCollectionService, 백필 오케스트레이터)
    private final List<MarketIndicatorSource> sources;
    private final String indicator;
    private final MarketIndicatorMetrics metrics;

    // @MX:NOTE: [AUTO] primary가 대상 날짜에 빈 결과를 반환할 것으로 예상되는지 판별하는 조건
    // @MX:REASON: [AUTO] 3-인자 생성자(this(..., d -> false))로 위임되어 VIX·기존 테스트 무회귀 보장
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-006 REQ-005~007
    private final Predicate<LocalDate> primaryExpectedEmpty;

    public MarketIndicatorSourceChain(
            List<MarketIndicatorSource> sources, String indicator, MarketIndicatorMetrics metrics) {
        this(sources, indicator, metrics, date -> false);
    }

    /**
     * primary 예상-빈 조건을 명시적으로 주입하는 생성자 (SPEC-COLLECTOR-MARKETIND-006 REQ-007).
     *
     * @param sources 순서대로 시도할 소스 목록
     * @param indicator 지표 식별자 ("VIX" / "USDKRW")
     * @param metrics 소스 체인 계측
     * @param primaryExpectedEmpty primary가 대상 날짜에 빈 결과를 반환할 것으로 예상되는지 판별하는 조건. 조건이 참이고 primary가 실제로
     *     빈 결과를 반환하면 {@link #fetchDaily(LocalDate)}는 실패 대신 예상 무데이터로 기록한다(REQ-008).
     */
    public MarketIndicatorSourceChain(
            List<MarketIndicatorSource> sources,
            String indicator,
            MarketIndicatorMetrics metrics,
            Predicate<LocalDate> primaryExpectedEmpty) {
        this.sources = List.copyOf(sources);
        this.indicator = indicator;
        this.metrics = metrics;
        this.primaryExpectedEmpty = primaryExpectedEmpty;
    }

    /**
     * 일봉 수집: 소스를 순서대로 시도하여 첫 성공 결과를 반환한다.
     *
     * @param date 수집 대상 날짜
     * @return 행 목록 (전부 실패 시 빈 리스트)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Fallback 체인 — 소스별 예외 격리 필요
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        for (int i = 0; i < sources.size(); i++) {
            MarketIndicatorSource source = sources.get(i);
            String nextSourceName = nextName(i);
            try {
                List<MarketIndicatorRow> rows = source.fetchDaily(date);
                if (!rows.isEmpty()) {
                    metrics.recordSuccess(indicator, source.sourceName());
                    return rows;
                }
                // REQ-008/009: primary(i==0)이고 대상 날짜가 확실한 휴장일(predicate=true)일 때만 예상
                // 무데이터로 기록한다. 그 외(비-primary 또는 predicate=false)는 기존 WARN을 그대로 보존한다
                // — 불확실한 상황(fail-open)은 항상 실패로 집계되어 오검출(알람 누락) 방향으로 넘어가지 않는다.
                if (i == 0 && primaryExpectedEmpty.test(date)) {
                    metrics.recordExpectedNoData(indicator, source.sourceName());
                    log.info(
                            "[market-ind-chain] {} fetchDaily 휴장일 예상 무데이터 — 다음 소스로",
                            source.sourceName());
                } else {
                    log.warn("[market-ind-chain] {} fetchDaily 빈 결과 — 다음 소스로", source.sourceName());
                }
                if (nextSourceName != null) {
                    metrics.recordFallback(
                            indicator, source.sourceName(), nextSourceName, "empty_result");
                }
            } catch (Exception e) {
                log.warn(
                        "[market-ind-chain] {} fetchDaily 예외 — 다음 소스로: {}",
                        source.sourceName(),
                        sanitize(e.getMessage()));
                if (nextSourceName != null) {
                    metrics.recordFallback(indicator, source.sourceName(), nextSourceName, "error");
                }
            }
        }
        metrics.recordExhausted(indicator, "daily");
        return List.of();
    }

    /**
     * 범위 수집: 소스를 순서대로 시도하여 첫 성공 결과를 반환한다 (SPEC-COLLECTOR-MARKETIND-003, REQ-020~024).
     *
     * <p>{@link #fetchDaily(LocalDate)}의 Fallback/계측 의미와 동형이다. 탈진 시 기존 2값 라벨 계약({@code method ∈
     * {daily, history}})의 {@code "daily"}를 재사용한다(REQ-023 — {@code method="daily"} exhausted 시계열의
     * 연속성 보존).
     *
     * @param from 시작 날짜 (포함)
     * @param to 종료 날짜 (포함)
     * @return 행 목록 (전부 실패 시 빈 리스트)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Fallback 체인 — 소스별 예외 격리 필요
    public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
        for (int i = 0; i < sources.size(); i++) {
            MarketIndicatorSource source = sources.get(i);
            String nextSourceName = nextName(i);
            try {
                List<MarketIndicatorRow> rows = source.fetchRange(from, to);
                if (!rows.isEmpty()) {
                    metrics.recordSuccess(indicator, source.sourceName());
                    return rows;
                }
                log.warn("[market-ind-chain] {} fetchRange 빈 결과 — 다음 소스로", source.sourceName());
                if (nextSourceName != null) {
                    metrics.recordFallback(
                            indicator, source.sourceName(), nextSourceName, "empty_result");
                }
            } catch (Exception e) {
                log.warn(
                        "[market-ind-chain] {} fetchRange 예외 — 다음 소스로: {}",
                        source.sourceName(),
                        sanitize(e.getMessage()));
                if (nextSourceName != null) {
                    metrics.recordFallback(indicator, source.sourceName(), nextSourceName, "error");
                }
            }
        }
        // REQ-023: range 조회도 exhausted 라벨은 "daily"를 재사용한다 — "history"와의 2값 계약을 유지해
        // {method="daily"} 시계열의 연속성을 보존한다(단일 날짜→윈도우 전환으로도 끊기지 않음).
        metrics.recordExhausted(indicator, "daily");
        return List.of();
    }

    /**
     * 전체 이력 수집: 소스를 순서대로 시도하여 첫 성공 결과를 반환한다.
     *
     * @return 행 목록 (전부 실패 시 빈 리스트)
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Fallback 체인 — 소스별 예외 격리 필요
    public List<MarketIndicatorRow> fetchHistory() {
        for (int i = 0; i < sources.size(); i++) {
            MarketIndicatorSource source = sources.get(i);
            String nextSourceName = nextName(i);
            try {
                List<MarketIndicatorRow> rows = source.fetchHistory();
                if (!rows.isEmpty()) {
                    metrics.recordSuccess(indicator, source.sourceName());
                    return rows;
                }
                log.warn("[market-ind-chain] {} fetchHistory 빈 결과 — 다음 소스로", source.sourceName());
                if (nextSourceName != null) {
                    metrics.recordFallback(
                            indicator, source.sourceName(), nextSourceName, "empty_result");
                }
            } catch (Exception e) {
                log.warn(
                        "[market-ind-chain] {} fetchHistory 예외 — 다음 소스로: {}",
                        source.sourceName(),
                        sanitize(e.getMessage()));
                if (nextSourceName != null) {
                    metrics.recordFallback(indicator, source.sourceName(), nextSourceName, "error");
                }
            }
        }
        metrics.recordExhausted(indicator, "history");
        return List.of();
    }

    private String nextName(int currentIndex) {
        int next = currentIndex + 1;
        return (next < sources.size()) ? sources.get(next).sourceName() : null;
    }
}
