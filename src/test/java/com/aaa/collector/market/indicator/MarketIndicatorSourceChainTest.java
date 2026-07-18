package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximQuotaExhaustedException;
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
import java.util.function.Predicate;
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
        metrics =
                new MarketIndicatorMetrics(
                        registry,
                        Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                        mock(MarketIndicatorLastSuccessRepository.class));
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

    private MarketIndicatorSourceChain usdkrwChain(
            Predicate<LocalDate> primaryExpectedEmpty, MarketIndicatorSource... sources) {
        return new MarketIndicatorSourceChain(
                List.of(sources), "USDKRW", metrics, primaryExpectedEmpty);
    }

    private MarketIndicatorRow usdkrwRow(String source) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW,
                DATE,
                new BigDecimal("1300.00"),
                new BigDecimal("1310.00"),
                new BigDecimal("1290.00"),
                new BigDecimal("1305.00"),
                source);
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

        @Test
        @DisplayName(
                "AC-B2: KOREAEXIM 쿼터 예외를 체인이 흡수 — Yahoo 폴백(reason=error), 체인 코드 무수정"
                        + " (SPEC-COLLECTOR-MARKETIND-005 TASK-B)")
        void quotaExhaustedException_absorbedByChain_fallsBackToYahoo() {
            MarketIndicatorRow row = vixRow("YAHOO_USDKRW");
            MarketIndicatorSource quotaExhaustedSource =
                    new MarketIndicatorSource() {
                        @Override
                        public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                            throw new KoreaeximQuotaExhaustedException("쿼터 소진 — date=" + date);
                        }

                        @Override
                        public List<MarketIndicatorRow> fetchHistory() {
                            return List.of();
                        }

                        @Override
                        public String sourceName() {
                            return "KOREAEXIM";
                        }
                    };
            MarketIndicatorSourceChain c =
                    chain(quotaExhaustedSource, successSource("YAHOO_USDKRW", row));

            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            assertThat(result).containsExactly(row);
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "KOREAEXIM")
                            .tag("to_source", "YAHOO_USDKRW")
                            .tag("reason", "error")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("fetchDaily — primary 예상-빈 조건 (SPEC-COLLECTOR-MARKETIND-006)")
    class PrimaryExpectedEmptyPredicate {

        @Test
        @DisplayName("휴장일(predicate=true) primary 빈 결과 — Yahoo 폴백 확정값 반환 (AC-01)")
        void holiday_primaryEmpty_fallsBackToYahoo() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> true,
                            emptySource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            // Assert: Yahoo 폴백 확정값 반환
            assertThat(result).containsExactly(row);
        }

        @Test
        @DisplayName(
                "휴장일(predicate=true) primary 빈 결과 — KOREAEXIM 신선도 게이지 전진(recordExpectedNoData) (AC-01)")
        void holiday_primaryEmpty_advancesKoreaeximLastSuccess() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> true,
                            emptySource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            c.fetchDaily(DATE);

            // Assert: KOREAEXIM 신선도 게이지는 recordExpectedNoData로 전진
            Gauge koreaeximLastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "USDKRW")
                            .tag("source", "KOREAEXIM")
                            .gauge();
            assertThat(koreaeximLastSuccess).isNotNull();
            assertThat(koreaeximLastSuccess.value())
                    .isEqualTo((double) FIXED_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName(
                "휴장일(predicate=true) primary 빈 결과 — active_source는 KOREAEXIM 무변경·YAHOO_USDKRW만 플립"
                        + " (AC-01)")
        void holiday_primaryEmpty_activeSourceOnlyFlipsOnRealSuccess() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> true,
                            emptySource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            c.fetchDaily(DATE);

            // Assert: KOREAEXIM active_source 무변경
            Gauge koreaeximActive =
                    registry.find(MarketIndicatorMetrics.ACTIVE_SOURCE)
                            .tag("indicator", "USDKRW")
                            .tag("source", "KOREAEXIM")
                            .gauge();
            assertThat(koreaeximActive).isNotNull();
            assertThat(koreaeximActive.value()).isEqualTo(0.0);

            // Assert: YAHOO_USDKRW는 실제 성공이므로 active_source 플립
            Gauge yahooActive =
                    registry.find(MarketIndicatorMetrics.ACTIVE_SOURCE)
                            .tag("indicator", "USDKRW")
                            .tag("source", "YAHOO_USDKRW")
                            .gauge();
            assertThat(yahooActive).isNotNull();
            assertThat(yahooActive.value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("휴장일(predicate=true) primary 빈 결과 — 폴백 계측 보존(empty_result) (AC-01, REQ-010)")
        void holiday_primaryEmpty_preservesFallbackInstrumentation() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> true,
                            emptySource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            c.fetchDaily(DATE);

            // Assert: 폴백 계측 보존(empty_result)
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "USDKRW")
                            .tag("from_source", "KOREAEXIM")
                            .tag("to_source", "YAHOO_USDKRW")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName(
                "개장일(predicate=false) primary 빈 결과 — recordExpectedNoData 미기록, 기존 WARN 동작 보존 (AC-02)")
        void openDay_primaryEmpty_doesNotRecordExpectedNoData() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> false,
                            emptySource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            c.fetchDaily(DATE);

            // Assert: KOREAEXIM 신선도 게이지는 미전진(0.0, PostConstruct 사전등록값 유지)
            Gauge koreaeximLastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "USDKRW")
                            .tag("source", "KOREAEXIM")
                            .gauge();
            assertThat(koreaeximLastSuccess).isNotNull();
            assertThat(koreaeximLastSuccess.value()).isEqualTo(0.0);

            // Assert: 폴백 계측은 기존대로 보존
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "USDKRW")
                            .tag("from_source", "KOREAEXIM")
                            .tag("to_source", "YAHOO_USDKRW")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("휴장일이어도 primary 예외 — 실패 집계, recordExpectedNoData 미기록 (AC-03, 핵심 안전장치)")
        void holiday_primaryException_stillRecordsFailure() {
            // Arrange
            MarketIndicatorRow row = usdkrwRow("YAHOO_USDKRW");
            MarketIndicatorSourceChain c =
                    usdkrwChain(
                            d -> true,
                            failingSource("KOREAEXIM"),
                            successSource("YAHOO_USDKRW", row));

            // Act
            List<MarketIndicatorRow> result = c.fetchDaily(DATE);

            // Assert: Yahoo 폴백 확정값 반환
            assertThat(result).containsExactly(row);

            // Assert: KOREAEXIM 신선도 게이지는 미전진(예외는 predicate 무관 실패로 집계)
            Gauge koreaeximLastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "USDKRW")
                            .tag("source", "KOREAEXIM")
                            .gauge();
            assertThat(koreaeximLastSuccess).isNotNull();
            assertThat(koreaeximLastSuccess.value()).isEqualTo(0.0);

            // Assert: 예외 사유(error)로 폴백 계측
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "USDKRW")
                            .tag("from_source", "KOREAEXIM")
                            .tag("to_source", "YAHOO_USDKRW")
                            .tag("reason", "error")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName(
                "VIX 체인(조건 미주입 3-인자 생성자) — 기본값 always-false로 recordExpectedNoData 미기록 (AC-04, 무회귀)")
        void vixChain_defaultPredicate_neverRecordsExpectedNoData() {
            // Arrange: 3-인자 생성자 — predicate 기본값(항상 거짓)
            MarketIndicatorRow row = vixRow("YAHOO_VIX");
            MarketIndicatorSourceChain c =
                    chain(emptySource("CBOE"), successSource("YAHOO_VIX", row));

            // Act
            c.fetchDaily(DATE);

            // Assert: CBOE 신선도 게이지 미전진(기존 실패 집계 그대로 유지)
            Gauge cboeLastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "VIX")
                            .tag("source", "CBOE")
                            .gauge();
            assertThat(cboeLastSuccess).isNotNull();
            assertThat(cboeLastSuccess.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("fetchRange — fallback 체인 (SPEC-COLLECTOR-MARKETIND-003, AC-3, AC-4)")
    class FetchRangeChain {

        private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
        private static final LocalDate TO = LocalDate.of(2026, 6, 14);

        /** 범위 조회를 지원하는 소스(고정 결과 반환). */
        private MarketIndicatorSource rangeSuccessSource(String name, MarketIndicatorRow row) {
            return new MarketIndicatorSource() {
                @Override
                public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                    return List.of();
                }

                @Override
                public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
                    return List.of(row);
                }

                @Override
                public String sourceName() {
                    return name;
                }
            };
        }

        private MarketIndicatorSource rangeEmptySource(String name) {
            return new MarketIndicatorSource() {
                @Override
                public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                    return List.of();
                }

                @Override
                public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
                    return List.of();
                }

                @Override
                public String sourceName() {
                    return name;
                }
            };
        }

        private MarketIndicatorSource rangeFailingSource(String name) {
            return new MarketIndicatorSource() {
                @Override
                public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
                    return List.of();
                }

                @Override
                public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
                    throw new IllegalStateException(name + " 범위 조회 실패");
                }

                @Override
                public String sourceName() {
                    return name;
                }
            };
        }

        @Test
        @DisplayName("CBOE 빈 결과 — YAHOO_VIX로 전환하여 결과 반환 + fallback(empty_result) 기록 (AC-3)")
        void primaryEmpty_fallsBackAndRecords() {
            MarketIndicatorRow row = vixRow("YAHOO_VIX");
            MarketIndicatorSourceChain c =
                    chain(rangeEmptySource("CBOE"), rangeSuccessSource("YAHOO_VIX", row));

            List<MarketIndicatorRow> result = c.fetchRange(FROM, TO);

            assertThat(result).containsExactly(row);
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "YAHOO_VIX")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("CBOE 예외 — YAHOO_VIX로 전환 + fallback(error) 기록")
        void primaryError_fallsBackAndRecordsError() {
            MarketIndicatorRow row = vixRow("YAHOO_VIX");
            MarketIndicatorSourceChain c =
                    chain(rangeFailingSource("CBOE"), rangeSuccessSource("YAHOO_VIX", row));

            List<MarketIndicatorRow> result = c.fetchRange(FROM, TO);

            assertThat(result).containsExactly(row);
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "YAHOO_VIX")
                            .tag("reason", "error")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("모든 소스 탈진 — 빈 리스트 반환 + recordExhausted(method=daily) (AC-4)")
        void allSourcesExhausted_returnsEmptyAndRecordsExhausted() {
            MarketIndicatorSourceChain c =
                    chain(rangeEmptySource("CBOE"), rangeEmptySource("YAHOO_VIX"));

            List<MarketIndicatorRow> result = c.fetchRange(FROM, TO);

            assertThat(result).isEmpty();
            Counter exhausted =
                    registry.find(MarketIndicatorMetrics.EXHAUSTED_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("method", "daily")
                            .counter();
            assertThat(exhausted).isNotNull();
            assertThat(exhausted.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Primary 성공 — success 기록, 예외 전파 없음")
        void primarySuccess_recordsSuccess() {
            MarketIndicatorRow row = vixRow("CBOE");
            MarketIndicatorSourceChain c = chain(rangeSuccessSource("CBOE", row));

            assertThatCode(() -> c.fetchRange(FROM, TO)).doesNotThrowAnyException();

            Gauge lastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "VIX")
                            .tag("source", "CBOE")
                            .gauge();
            assertThat(lastSuccess).isNotNull();
            assertThat(lastSuccess.value()).isEqualTo((double) FIXED_INSTANT.getEpochSecond());
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
        @DisplayName("Primary 빈 결과 → YAHOO_VIX 성공 — fallback(empty_result) + success(YAHOO_VIX) 기록")
        void primaryEmpty_recordsFallbackAndSuccess() {
            // Arrange
            MarketIndicatorRow row = vixRow("YAHOO_VIX");
            chain(emptySource("CBOE"), successSource("YAHOO_VIX", row)).fetchDaily(DATE);

            // Assert: fallback 기록
            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "YAHOO_VIX")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(fallback).isNotNull();
            assertThat(fallback.count()).isEqualTo(1.0);

            // Assert: YAHOO_VIX success 기록
            Gauge lastSuccess =
                    registry.find(MarketIndicatorMetrics.LAST_SUCCESS)
                            .tag("indicator", "VIX")
                            .tag("source", "YAHOO_VIX")
                            .gauge();
            assertThat(lastSuccess).isNotNull();
            assertThat(lastSuccess.value()).isEqualTo((double) FIXED_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("Primary 예외 → Fallback 성공 — fallback(error) 기록")
        void primaryError_recordsFallbackError() {
            MarketIndicatorRow row = vixRow("YAHOO_VIX");
            chain(failingSource("CBOE"), successSource("YAHOO_VIX", row)).fetchDaily(DATE);

            Counter fallback =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "YAHOO_VIX")
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
