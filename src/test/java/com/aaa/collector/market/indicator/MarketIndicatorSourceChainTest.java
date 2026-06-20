package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MarketIndicatorSourceChain 단위 테스트")
class MarketIndicatorSourceChainTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 20);

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

    @Nested
    @DisplayName("fetchDaily — fallback 체인")
    class FetchDailyChain {

        @Test
        @DisplayName("Primary 성공 — Primary 결과 반환, Fallback 미호출")
        void primary_success_returnsPrimaryResult() {
            MarketIndicatorRow row = vixRow("CBOE");
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(successSource("CBOE", row), failingSource("FRED")));

            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("Primary 빈 결과 — Fallback 1 호출")
        void primary_empty_fallsBackToSecond() {
            MarketIndicatorRow row = vixRow("FRED");
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(emptySource("CBOE"), successSource("FRED", row)));

            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("Primary 예외 — Fallback 순차 진행")
        void primary_exception_fallsBackSequentially() {
            MarketIndicatorRow row = vixRow("YAHOO");
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(
                                    failingSource("CBOE"),
                                    emptySource("FRED"),
                                    successSource("YAHOO", row)));

            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("첫 성공에서 중단 — 두 번째 이후 소스 미호출")
        void stopsAtFirstSuccess() {
            MarketIndicatorRow row = vixRow("PRIMARY");
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(
                                    successSource("PRIMARY", row),
                                    failingSource("SHOULD_NOT_BE_CALLED")));

            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().source()).isEqualTo("PRIMARY");
        }

        @Test
        @DisplayName("전부 실패 — 빈 결과 반환 (예외 전파 없음)")
        void allFail_returnsEmpty_noException() {
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(failingSource("A"), failingSource("B"), emptySource("C")));

            assertThatCode(() -> chain.fetchDaily(DATE)).doesNotThrowAnyException();
            assertThat(chain.fetchDaily(DATE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchHistory — fallback 체인")
    class FetchHistoryChain {

        @Test
        @DisplayName("Primary 성공 — Primary 결과 반환")
        void primary_success_returnsResult() {
            MarketIndicatorRow row = vixRow("CBOE");
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(List.of(successSource("CBOE", row)));

            List<MarketIndicatorRow> result = chain.fetchHistory();

            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName("전부 실패 — 빈 결과 반환")
        void allFail_returnsEmpty() {
            MarketIndicatorSourceChain chain =
                    new MarketIndicatorSourceChain(
                            List.of(failingSource("CBOE"), failingSource("FRED")));

            List<MarketIndicatorRow> result = chain.fetchHistory();

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
}
