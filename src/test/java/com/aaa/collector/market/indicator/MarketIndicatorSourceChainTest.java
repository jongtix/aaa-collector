package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aaa.collector.market.enums.IndicatorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MarketIndicatorSourceChain 단위 테스트")
class MarketIndicatorSourceChainTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 20);
    private static final Instant FIXED_INSTANT = Instant.ofEpochSecond(1_700_000_000L);

    private MeterRegistry registry;
    private MarketIndicatorMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MarketIndicatorMetrics(registry, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        metrics.init();
    }

    private MarketIndicatorRow vixRow(String source) {
        return new MarketIndicatorRow(
                IndicatorCode.VIX,
                DATE,
                new BigDecimal("18.0000"),
                new BigDecimal("19.0000"),
                new BigDecimal("17.0000"),
                new BigDecimal("18.5000"),
                source);
    }

    /** 항상 빈 리스트를 반환하는 소스 */
    private MarketIndicatorSource emptySource(String name) {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                return List.of();
            }

            @Override
            public List<MarketIndicatorRow> fetchHistory() {
                return List.of();
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    /** 항상 예외를 던지는 소스 */
    private MarketIndicatorSource failingSource(String name) {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                throw new IllegalStateException(name + " 호출 실패");
            }

            @Override
            public List<MarketIndicatorRow> fetchHistory() {
                throw new IllegalStateException(name + " 호출 실패");
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    /** 지정 행을 반환하는 소스 */
    private MarketIndicatorSource successSource(String name, MarketIndicatorRow row) {
        return new MarketIndicatorSource() {
            @Override
            public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                return List.of(row);
            }

            @Override
            public List<MarketIndicatorRow> fetchHistory() {
                return List.of(row);
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    private MarketIndicatorSourceChain chain(MarketIndicatorSource... sources) {
        return new MarketIndicatorSourceChain(List.of(sources), "VIX", metrics);
    }

    @Nested
    @DisplayName("fetchDaily — fallback 체인")
    class FetchDailyChain {

        @Test
        @DisplayName("Primary 성공 — Primary 결과 반환, Fallback 미호출")
        void primary_success_returnsPrimaryResult() {
            MarketIndicatorRow row = vixRow("CBOE");
            MarketIndicatorSourceChain c = chain(successSource("CBOE", row), failingSource("FRED"));

            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("Primary 빈 결과 — Fallback 1 호출")
        void primary_empty_fallsBackToSecond() {
            MarketIndicatorRow row = vixRow("FRED");
            MarketIndicatorSourceChain c = chain(emptySource("CBOE"), successSource("FRED", row));

            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("Primary 예외 — Fallback 순차 진행")
        void primary_exception_fallsBackSequentially() {
            MarketIndicatorRow row = vixRow("YAHOO");
            MarketIndicatorSourceChain c =
                    chain(failingSource("CBOE"), emptySource("FRED"), successSource("YAHOO", row));

            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("첫 성공에서 중단 — 두 번째 이후 소스 미호출")
        void stopsAtFirstSuccess() {
            MarketIndicatorRow row = vixRow("PRIMARY");
            MarketIndicatorSourceChain c =
                    chain(successSource("PRIMARY", row), failingSource("SHOULD_NOT_BE_CALLED"));

            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().source()).isEqualTo("PRIMARY");
        }

        @Test
        @DisplayName("전부 실패 — 빈 결과 반환 (예외 전파 없음)")
        void allFail_returnsEmpty_noException() {
            MarketIndicatorSourceChain c =
                    chain(failingSource("A"), failingSource("B"), emptySource("C"));

            assertThatCode(() -> c.fetchDaily(DATE)).doesNotThrowAnyException();
            assertThat(c.fetchDaily(DATE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchHistory — fallback 체인")
    class FetchHistoryChain {

        @Test
        @DisplayName("Primary 성공 — Primary 결과 반환")
        void primary_success_returnsResult() {
            MarketIndicatorRow row = vixRow("CBOE");
            MarketIndicatorSourceChain c = chain(successSource("CBOE", row));

            List<MarketIndicatorRow> result = c.fetchHistory();

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("전부 실패 — 빈 결과 반환")
        void allFail_returnsEmpty() {
            MarketIndicatorSourceChain c = chain(failingSource("CBOE"), failingSource("FRED"));

            List<MarketIndicatorRow> result = c.fetchHistory();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("MarketIndicatorRow record")
    class RowRecord {

        @Test
        @DisplayName("필드 접근자 정상 동작")
        void rowFieldAccessors() {
            MarketIndicatorRow row =
                    new MarketIndicatorRow(
                            IndicatorCode.VIX,
                            DATE,
                            new BigDecimal("18.0000"),
                            new BigDecimal("19.0000"),
                            new BigDecimal("17.0000"),
                            new BigDecimal("18.5000"),
                            "CBOE");

            assertThat(row.indicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(row.tradeDate()).isEqualTo(DATE);
            assertThat(row.closeValue()).isEqualByComparingTo("18.5000");
            assertThat(row.source()).isEqualTo("CBOE");
        }
    }

    @Nested
    @DisplayName("메트릭 계측 — fetchDaily")
    class MetricsDailyRecording {

        @Test
        @DisplayName("Primary 성공 시 success 기록, fallback 미기록")
        void primarySuccess_recordsSuccess_noFallback() {
            MarketIndicatorRow row = vixRow("CBOE");
            chain(successSource("CBOE", row)).fetchDaily(DATE);

            // last_success 갱신 확인
            Gauge lastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "VIX")
                            .tag("source", "CBOE")
                            .gauge();
            assertThat(lastSuccess).isNotNull();
            assertThat(lastSuccess.value()).isEqualTo((double) FIXED_INSTANT.getEpochSecond());

            // fallback 미기록 확인
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .counter();
            assertThat(fallback).isNull();
        }

        @Test
        @DisplayName("Primary 빈 결과 → FRED 성공 — fallback(empty_result) + success(FRED) 기록")
        void primaryEmpty_recordsFallbackAndSuccess() {
            // Arrange
            MarketIndicatorRow row = vixRow("FRED");
            chain(emptySource("CBOE"), successSource("FRED", row)).fetchDaily(DATE);

            // Assert: fallback 기록
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "FRED")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);

            // Assert: FRED success 기록
            Gauge lastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "VIX")
                            .tag("source", "FRED")
                            .gauge();
            assertThat(lastSuccess).isNotNull();
            assertThat(lastSuccess.value()).isEqualTo((double) FIXED_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("Primary 예외 → Fallback 성공 — fallback(error) 기록")
        void primaryError_recordsFallbackError() {
            MarketIndicatorRow row = vixRow("FRED");
            chain(failingSource("CBOE"), successSource("FRED", row)).fetchDaily(DATE);

            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "FRED")
                            .tag("reason", "error")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("전부 탈진 — exhausted_total(daily) 기록")
        void allExhausted_recordsExhausted() {
            chain(emptySource("CBOE"), emptySource("FRED")).fetchDaily(DATE);

            Counter exhausted =
                    registry.find(MarketIndicatorMetrics.EXHAUSTED_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("method", "daily")
                            .counter();
            assertThat(exhausted).isNotNull();
            assertThat(exhausted.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("전부 탈진 시 마지막 소스 fallback 미기록")
        void lastSource_noFallbackWhenExhausted() {
            chain(emptySource("CBOE")).fetchDaily(DATE);

            // 소스가 1개뿐이므로 fallback 기록 없음
            Counter fallback = registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL).counter();
            assertThat(fallback).isNull();
        }
    }

    @Nested
    @DisplayName("메트릭 계측 — fetchHistory")
    class MetricsHistoryRecording {

        @Test
        @DisplayName("전부 탈진 — exhausted_total(history) 기록")
        void allExhausted_recordsExhaustedHistory() {
            chain(emptySource("CBOE"), failingSource("FRED")).fetchHistory();

            Counter exhausted =
                    registry.find(MarketIndicatorMetrics.EXHAUSTED_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("method", "history")
                            .counter();
            assertThat(exhausted).isNotNull();
            assertThat(exhausted.count()).isEqualTo(1.0);
        }
    }
}
