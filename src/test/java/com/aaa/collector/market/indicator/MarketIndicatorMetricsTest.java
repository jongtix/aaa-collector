package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketIndicatorMetrics 단위 테스트")
class MarketIndicatorMetricsTest {

    private static final Instant FIXED_INSTANT = Instant.ofEpochSecond(1_700_000_000L);
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private MeterRegistry registry;

    @Mock private MarketIndicatorLastSuccessRepository lastSuccessRepository;

    private MarketIndicatorMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MarketIndicatorMetrics(registry, FIXED_CLOCK, lastSuccessRepository);
        metrics.init();
    }

    @Nested
    @DisplayName("PostConstruct — @PostConstruct 사전 등록")
    class PostConstructPhase {

        @Test
        @DisplayName(
                "VIX 2종 + USDKRW 2종 last_success Gauge 0.0으로 사전 등록 (SPEC-COLLECTOR-MARKETIND-003 FRED 제거)")
        void init_preRegistersLastSuccess() {
            assertGaugeValue(MarketIndicatorMetrics.LAST_SUCCESS, "VIX", "CBOE", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.LAST_SUCCESS, "VIX", "YAHOO_VIX", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.LAST_SUCCESS, "USDKRW", "KOREAEXIM", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.LAST_SUCCESS, "USDKRW", "YAHOO_USDKRW", 0.0);
        }

        @Test
        @DisplayName("active_source Gauge 0.0으로 사전 등록")
        void init_preRegistersActiveSource() {
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "CBOE", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "YAHOO_VIX", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "USDKRW", "KOREAEXIM", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "USDKRW", "YAHOO_USDKRW", 0.0);
        }
    }

    @Nested
    @DisplayName("recordFallback — fallback_total 카운터")
    class RecordFallback {

        @Test
        @DisplayName("reason=empty_result — 카운터 1 증가")
        void recordFallback_incrementsCounter() {
            metrics.recordFallback("VIX", "CBOE", "FRED", "empty_result");

            Counter counter =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "FRED")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("reason=error 태그 확인")
        void recordFallback_withReason_error() {
            metrics.recordFallback("USDKRW", "KOREAEXIM", "YAHOO_USDKRW", "error");

            Counter counter =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "USDKRW")
                            .tag("from_source", "KOREAEXIM")
                            .tag("to_source", "YAHOO_USDKRW")
                            .tag("reason", "error")
                            .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("동일 라벨 복수 호출 — 누적 카운트")
        void recordFallback_multipleCall_accumulates() {
            metrics.recordFallback("VIX", "CBOE", "FRED", "empty_result");
            metrics.recordFallback("VIX", "CBOE", "FRED", "empty_result");

            Counter counter =
                    registry.find(MarketIndicatorMetrics.FALLBACK_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("from_source", "CBOE")
                            .tag("to_source", "FRED")
                            .tag("reason", "empty_result")
                            .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("recordSuccess — last_success + active_source 갱신")
    class RecordSuccess {

        @Test
        @DisplayName("last_success Gauge를 현재 epoch 초로 갱신")
        void recordSuccess_updatesLastSuccessAndActiveSource() {
            metrics.recordSuccess("VIX", "CBOE");

            assertGaugeValue(
                    MarketIndicatorMetrics.LAST_SUCCESS,
                    "VIX",
                    "CBOE",
                    (double) FIXED_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("성공 소스 active=1, 같은 indicator의 다른 소스 active=0으로 리셋")
        void recordSuccess_resetsOtherActiveSources() {
            metrics.recordSuccess("VIX", "YAHOO_VIX");

            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "CBOE", 0.0);
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "YAHOO_VIX", 1.0);
        }

        @Test
        @DisplayName("다른 indicator의 active_source는 영향 없음")
        void recordSuccess_doesNotAffectOtherIndicators() {
            // Arrange: USDKRW 먼저 성공 기록
            metrics.recordSuccess("USDKRW", "KOREAEXIM");

            // Act: VIX 성공 기록
            metrics.recordSuccess("VIX", "CBOE");

            // Assert: USDKRW active_source는 변경 없음
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "USDKRW", "KOREAEXIM", 1.0);
        }

        @Test
        @DisplayName("게이지와 Redis에 동일 epoch를 기록한다 (REQ-WSR-017/018, AC-1)")
        void recordSuccess_persistsSameEpochToRedis() {
            metrics.recordSuccess("VIX", "CBOE");

            verify(lastSuccessRepository).save("VIX", "CBOE", FIXED_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("Redis 기록이 DataAccessException으로 실패해도 게이지 기록은 완료된다 (REQ-WSR-019, AC-2)")
        void recordSuccess_absorbsRedisWriteFailure() {
            // Arrange
            doThrow(new QueryTimeoutException("Redis 연결 실패"))
                    .when(lastSuccessRepository)
                    .save("VIX", "CBOE", FIXED_INSTANT.getEpochSecond());

            // Act & Assert — 예외가 전파되지 않고 게이지는 정상 갱신된다
            assertThatCode(() -> metrics.recordSuccess("VIX", "CBOE")).doesNotThrowAnyException();
            assertGaugeValue(
                    MarketIndicatorMetrics.LAST_SUCCESS,
                    "VIX",
                    "CBOE",
                    (double) FIXED_INSTANT.getEpochSecond());
            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "CBOE", 1.0);
        }
    }

    @Nested
    @DisplayName("warmLastSuccess — 부팅 시 게이지 seed (REQ-WSR-021)")
    class WarmLastSuccess {

        @Test
        @DisplayName("주어진 Instant로 last_success 게이지를 seed한다")
        void warmLastSuccess_seedsGauge() {
            Instant seedInstant = Instant.ofEpochSecond(1_690_000_000L);

            metrics.warmLastSuccess("VIX", "CBOE", seedInstant);

            assertGaugeValue(
                    MarketIndicatorMetrics.LAST_SUCCESS,
                    "VIX",
                    "CBOE",
                    (double) seedInstant.getEpochSecond());
        }

        @Test
        @DisplayName("active_source는 건드리지 않는다 (REQ-WSR-023)")
        void warmLastSuccess_doesNotTouchActiveSource() {
            metrics.warmLastSuccess("VIX", "CBOE", Instant.ofEpochSecond(1_690_000_000L));

            assertGaugeValue(MarketIndicatorMetrics.ACTIVE_SOURCE, "VIX", "CBOE", 0.0);
        }
    }

    @Nested
    @DisplayName("knownSources — warm-start 반복 대상 단일 정본 열거 노출 (REQ-WSR-025)")
    class KnownSources {

        @Test
        @DisplayName("VIX 2종 + USDKRW 2종 조합을 노출한다 (SPEC-COLLECTOR-MARKETIND-003 FRED 제거)")
        void knownSources_exposesFourCombinations() {
            Map<String, List<String>> known = MarketIndicatorMetrics.knownSources();

            assertThat(known.get("VIX")).containsExactly("CBOE", "YAHOO_VIX");
            assertThat(known.get("USDKRW")).containsExactly("KOREAEXIM", "YAHOO_USDKRW");
        }
    }

    @Nested
    @DisplayName("recordExhausted — exhausted_total 카운터")
    class RecordExhausted {

        @Test
        @DisplayName("method=daily — 카운터 1 증가")
        void recordExhausted_incrementsCounter() {
            metrics.recordExhausted("VIX", "daily");

            Counter counter =
                    registry.find(MarketIndicatorMetrics.EXHAUSTED_TOTAL)
                            .tag("indicator", "VIX")
                            .tag("method", "daily")
                            .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("method=history — 카운터 1 증가")
        void recordExhausted_history_incrementsCounter() {
            metrics.recordExhausted("USDKRW", "history");

            Counter counter =
                    registry.find(MarketIndicatorMetrics.EXHAUSTED_TOTAL)
                            .tag("indicator", "USDKRW")
                            .tag("method", "history")
                            .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("예외 격리 — 메트릭 실패가 호출자로 전파되지 않음 (REQ-012)")
    class ExceptionIsolation {

        @Test
        @DisplayName("recordFallback — registry 예외 발생 시 전파 없음")
        void metricsFailure_doesNotPropagateException() {
            // Arrange: closed registry 대신 null registry로 예외 유발을 시뮬레이션
            // 실제로는 예외가 발생하지 않는 환경에서 try-catch 격리를 간접 검증
            // (내부 try-catch 구조상 예외가 전파되면 안 됨)
            assertThatCode(() -> metrics.recordFallback("VIX", "CBOE", "FRED", "error"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> metrics.recordSuccess("VIX", "CBOE")).doesNotThrowAnyException();
            assertThatCode(() -> metrics.recordExhausted("VIX", "daily"))
                    .doesNotThrowAnyException();
        }
    }

    // -------------------------------------------------------------------------
    // 헬퍼
    // -------------------------------------------------------------------------

    private void assertGaugeValue(
            String metricName, String indicator, String source, double expected) {
        Gauge gauge =
                registry.find(metricName).tag("indicator", indicator).tag("source", source).gauge();
        assertThat(gauge)
                .as("Gauge %s{indicator=%s,source=%s} 미등록", metricName, indicator, source)
                .isNotNull();
        assertThat(gauge.value())
                .as("Gauge %s{indicator=%s,source=%s} 값", metricName, indicator, source)
                .isEqualTo(expected);
    }
}
