package com.aaa.collector.market.indicator;

import static com.aaa.collector.market.indicator.SensitiveDataSanitizer.sanitize;

import java.time.LocalDate;
import java.util.List;
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

    public MarketIndicatorSourceChain(
            List<MarketIndicatorSource> sources, String indicator, MarketIndicatorMetrics metrics) {
        this.sources = List.copyOf(sources);
        this.indicator = indicator;
        this.metrics = metrics;
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
                log.warn("[market-ind-chain] {} fetchDaily 빈 결과 — 다음 소스로", source.sourceName());
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
