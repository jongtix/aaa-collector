package com.aaa.collector.macro.ecos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicator;
import com.aaa.collector.macro.MacroIndicatorInserter;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

/**
 * T2 RED-GREEN-REFACTOR — EcosCollectionService 단위 테스트 (SPEC-COLLECTOR-MACRO-EXT-001).
 *
 * <p>8개 시리즈 매핑, 날짜 형식 정규화(D/M/Q), BigDecimal 변환, INFO-200 처리, 예외 격리 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EcosCollectionService — 단위 테스트")
class EcosCollectionServiceTest {

    @Mock private RestClient ecosRestClient;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private MacroIndicatorInserter macroIndicatorInserter;

    @Mock
    @SuppressWarnings("rawtypes")
    private RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock private ResponseSpec responseSpec;

    @Captor private ArgumentCaptor<List<MacroIndicator>> inserterCaptor;

    private final InserterProperties inserterProperties = new InserterProperties();

    private EcosCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new EcosCollectionService(
                        ecosRestClient,
                        macroIndicatorRepository,
                        macroIndicatorInserter,
                        inserterProperties);
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientChain() {
        when(ecosRestClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(String.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
    }

    private EcosStatisticSearchResponse responseWithRows(
            List<EcosStatisticSearchResponse.Row> rows) {
        return new EcosStatisticSearchResponse(
                new EcosStatisticSearchResponse.StatisticSearch(rows.size(), rows));
    }

    private EcosStatisticSearchResponse.Row row(String time, String value) {
        return new EcosStatisticSearchResponse.Row(time, value);
    }

    // ────────────────────────────────────────────────────────────────────
    // normalizeDate — 날짜 정규화 직접 검증
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalizeDate — D/M/Q 주기 변환")
    class NormalizeDate {

        @Test
        @DisplayName("D(YYYYMMDD) → LocalDate 정상 변환")
        void dailyFormat_parsedCorrectly() {
            assertThat(EcosCollectionService.normalizeDate("D", "20260620"))
                    .isEqualTo(LocalDate.of(2026, 6, 20));
        }

        @Test
        @DisplayName("M(YYYYMM) → 해당 월 1일")
        void monthlyFormat_convertedToFirstDay() {
            assertThat(EcosCollectionService.normalizeDate("M", "202605"))
                    .isEqualTo(LocalDate.of(2026, 5, 1));
        }

        @Test
        @DisplayName("Q1(YYYYQ1) → 01-01")
        void quarterQ1_convertedToJan1() {
            assertThat(EcosCollectionService.normalizeDate("Q", "2026Q1"))
                    .isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("Q2(YYYYQ2) → 04-01")
        void quarterQ2_convertedToApr1() {
            assertThat(EcosCollectionService.normalizeDate("Q", "2026Q2"))
                    .isEqualTo(LocalDate.of(2026, 4, 1));
        }

        @Test
        @DisplayName("Q3(YYYYQ3) → 07-01")
        void quarterQ3_convertedToJul1() {
            assertThat(EcosCollectionService.normalizeDate("Q", "2026Q3"))
                    .isEqualTo(LocalDate.of(2026, 7, 1));
        }

        @Test
        @DisplayName("Q4(YYYYQ4) → 10-01")
        void quarterQ4_convertedToOct1() {
            assertThat(EcosCollectionService.normalizeDate("Q", "2026Q4"))
                    .isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        @DisplayName("지원하지 않는 주기 코드 → IllegalArgumentException")
        void unsupportedPeriod_throwsException() {
            assertThatThrownBy(() -> EcosCollectionService.normalizeDate("W", "20260620"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 수집 서비스 동작 검증
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 수집 동작")
    class CollectBehavior {

        @Test
        @DisplayName("단일 정상 행(D주기) 수집 — source=ECOS, insertBatch 호출")
        void collectSingleRow_storesWithEcosSource() {
            // Arrange — YYYYMMDD 형식 행 (D 주기 시리즈에 적합)
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            verify(macroIndicatorInserter, atLeastOnce()).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .allMatch(m -> m.getSource() == MacroSource.ECOS);
            assertThat(result.succeeded()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("각 시리즈마다 RestClient.get() 8회 호출")
        void collect_calls8SeriesRequests() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act
            service.collect();

            // Assert — 8개 시리즈이므로 8회 호출
            verify(ecosRestClient, times(8)).get();
        }

        @Test
        @DisplayName("BigDecimal — 천단위 콤마 포함 값 '1,234.56' 정상 변환")
        void commaValue_parsedCorrectly() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "1,234.56"))));

            // Act
            service.collect();

            // Assert
            verify(macroIndicatorInserter, atLeastOnce()).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .anyMatch(
                            m ->
                                    m.getValue() != null
                                            && new BigDecimal("1234.56").compareTo(m.getValue())
                                                    == 0);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // INFO-200 (0건) 응답
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("INFO-200 응답 — 0건 처리 후 계속")
    class Info200Response {

        @Test
        @DisplayName("statisticSearch=null (INFO-200) → 해당 시리즈 0건, insertIgnoreDuplicate 미호출")
        void info200Response_skipAndContinue() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(new EcosStatisticSearchResponse(null));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(macroIndicatorInserter, never()).insertBatch(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 격리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 격리 — 단일 시리즈 실패 시 다음 시리즈 계속")
    class ExceptionIsolation {

        @Test
        @DisplayName("RestClient retrieve 예외 — 해당 시리즈만 skip, 나머지 7개 시리즈 계속")
        @SuppressWarnings("unchecked")
        void restClientException_isolatedPerSeries() {
            // Arrange — 첫 번째 retrieve만 예외, 이후는 정상
            when(ecosRestClient.get()).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.uri(any(String.class))).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.retrieve())
                    .thenThrow(new RuntimeException("network error"))
                    .thenReturn(responseSpec);
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act — 예외가 collect()로 전파되지 않아야 함
            MacroCollectionResult result = service.collect();

            // Assert — 1개 시리즈 예외 격리, 나머지 7개 시리즈 성공적으로 attempted
            assertThat(result.attempted()).isGreaterThanOrEqualTo(7);
        }
    }
}
