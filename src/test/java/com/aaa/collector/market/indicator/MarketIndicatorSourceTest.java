package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketIndicatorSource#fetchRange(LocalDate, LocalDate)} 기본 구현 단위 테스트
 * (SPEC-COLLECTOR-MARKETIND-003, REQ-010, AC-11).
 */
@DisplayName("MarketIndicatorSource.fetchRange — 기본 구현 (날짜 순회 + fetchDaily 위임)")
class MarketIndicatorSourceTest {

    /** fetchDaily만 구현하고 fetchRange를 오버라이드하지 않는 소스가 호출한 날짜 — USDKRW 경로 구현체 모사. */
    private final List<LocalDate> calledDates = new ArrayList<>();

    private MarketIndicatorRow row(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW,
                date,
                new BigDecimal("1380.0000"),
                new BigDecimal("1390.0000"),
                new BigDecimal("1370.0000"),
                new BigDecimal("1385.0000"),
                "KOREAEXIM");
    }

    /** fetchDaily만 구현하고 fetchRange를 오버라이드하지 않는 소스 — USDKRW 경로 구현체(KOREAEXIM/YAHOO_USDKRW) 모사. */
    private MarketIndicatorSource fetchDailyOnlySource() {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                calledDates.add(date);
                return List.of(row(date));
            }

            @Override
            public String sourceName() {
                return "FETCH_DAILY_ONLY";
            }
        };
    }

    @Test
    @DisplayName("기본 구현은 [from, to]를 하루 단위로 순회하며 fetchDaily를 위임·연결한다 (REQ-010)")
    void defaultFetchRange_iteratesDayByDay_delegatesToFetchDaily() {
        MarketIndicatorSource source = fetchDailyOnlySource();
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 4);

        List<MarketIndicatorRow> rows = source.fetchRange(from, to);

        assertThat(calledDates)
                .containsExactly(
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 2),
                        LocalDate.of(2026, 6, 3),
                        LocalDate.of(2026, 6, 4));
        assertThat(rows).hasSize(4);
    }

    @Test
    @DisplayName("from == to(단일일) — fetchDaily 1회만 호출")
    void defaultFetchRange_singleDay_callsFetchDailyOnce() {
        MarketIndicatorSource source = fetchDailyOnlySource();
        LocalDate date = LocalDate.of(2026, 6, 10);

        List<MarketIndicatorRow> rows = source.fetchRange(date, date);

        assertThat(calledDates).containsExactly(date);
        assertThat(rows).hasSize(1);
    }
}
