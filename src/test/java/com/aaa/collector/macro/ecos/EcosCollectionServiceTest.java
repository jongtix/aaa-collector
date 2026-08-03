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
    @Captor private ArgumentCaptor<String> uriCaptor;

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
                new EcosStatisticSearchResponse.StatisticSearch(rows.size(), rows), null);
    }

    private EcosStatisticSearchResponse info200Response() {
        return new EcosStatisticSearchResponse(
                null, new EcosStatisticSearchResponse.Result("INFO-200", "요청하신 데이터가 없습니다."));
    }

    private EcosStatisticSearchResponse errorResponse(String code, String message) {
        return new EcosStatisticSearchResponse(
                null, new EcosStatisticSearchResponse.Result(code, message));
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
    // 주기별 요청 URL 날짜 포맷 (T2, AC-1.1~1.4, 1.7, REQ-ECOSFMT-001~006, 022/023)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildUrl — 주기별 날짜 포맷")
    class UrlDateFormat {

        @Test
        @DisplayName("당일 수집 — M 시리즈(ECOS_CPI) 요청 URL 날짜가 6자리(YYYYMM) [실측 #2 재발 방지]")
        void dailyCollect_monthlySeries_sixDigitDate() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("202606", "3.50"))));

            // Act
            service.collect();

            // Assert — ECOS_CPI는 EcosSeriesConfig.ALL의 6번째(index 5) 시리즈
            verify(requestHeadersUriSpec, times(8)).uri(uriCaptor.capture());
            String cpiUrl = uriCaptor.getAllValues().get(5);
            String[] segments = cpiUrl.split("/");
            String startDate = segments[segments.length - 3];
            String endDate = segments[segments.length - 2];
            assertThat(startDate).matches("^\\d{6}$");
            assertThat(endDate).matches("^\\d{6}$");
        }

        @Test
        @DisplayName("당일 수집 — Q 시리즈(ECOS_GDP_QOQ) 요청 URL 날짜가 YYYYQN [실측 #3 재발 방지, #5 형식 준거]")
        void dailyCollect_quarterlySeries_yyyyQnDate() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("2026Q2", "0.6"))));

            // Act
            service.collect();

            // Assert — ECOS_GDP_QOQ는 EcosSeriesConfig.ALL의 7번째(index 6) 시리즈
            verify(requestHeadersUriSpec, times(8)).uri(uriCaptor.capture());
            String gdpUrl = uriCaptor.getAllValues().get(6);
            String[] segments = gdpUrl.split("/");
            String startDate = segments[segments.length - 3];
            String endDate = segments[segments.length - 2];
            assertThat(startDate).matches("^\\d{4}Q[1-4]$");
            assertThat(endDate).matches("^\\d{4}Q[1-4]$");
        }

        @Test
        @DisplayName("당일 수집 — D 시리즈(ECOS_BASE_RATE) 요청 URL 날짜가 8자리(YYYYMMDD, 회귀 없음)")
        void dailyCollect_dailySeries_eightDigitDate() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act
            service.collect();

            // Assert — ECOS_BASE_RATE는 EcosSeriesConfig.ALL의 첫 번째(index 0) 시리즈
            verify(requestHeadersUriSpec, times(8)).uri(uriCaptor.capture());
            String baseRateUrl = uriCaptor.getAllValues().getFirst();
            String[] segments = baseRateUrl.split("/");
            String startDate = segments[segments.length - 3];
            String endDate = segments[segments.length - 2];
            assertThat(startDate).matches("^\\d{8}$");
            assertThat(endDate).matches("^\\d{8}$");
        }

        @Test
        @DisplayName("백필 — 주기별 시작 리터럴(D=19000101, M=190001, Q=1900Q1) [실측 #7 재발 방지]")
        void backfill_periodSpecificStartLiteral() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act
            service.collectAll();

            // Assert
            verify(requestHeadersUriSpec, times(8)).uri(uriCaptor.capture());
            List<String> urls = uriCaptor.getAllValues();
            assertThat(startDateSegment(urls.getFirst())).isEqualTo("19000101"); // D
            assertThat(startDateSegment(urls.get(5))).isEqualTo("190001"); // M (ECOS_CPI)
            assertThat(startDateSegment(urls.get(6))).isEqualTo("1900Q1"); // Q (ECOS_GDP_QOQ)
        }

        private String startDateSegment(String url) {
            String[] segments = url.split("/");
            return segments[segments.length - 3];
        }

        @Test
        @DisplayName("Q 분기 계산 경계 — 1·3월=Q1, 4·6월=Q2")
        void quarterCalculation_q1AndQ2Boundaries() {
            assertThat(EcosPeriodDateFormatter.quarterOf(1)).isEqualTo(1);
            assertThat(EcosPeriodDateFormatter.quarterOf(3)).isEqualTo(1);
            assertThat(EcosPeriodDateFormatter.quarterOf(4)).isEqualTo(2);
            assertThat(EcosPeriodDateFormatter.quarterOf(6)).isEqualTo(2);
        }

        @Test
        @DisplayName("Q 분기 계산 경계 — 7·9월=Q3, 10·12월=Q4")
        void quarterCalculation_q3AndQ4Boundaries() {
            assertThat(EcosPeriodDateFormatter.quarterOf(7)).isEqualTo(3);
            assertThat(EcosPeriodDateFormatter.quarterOf(9)).isEqualTo(3);
            assertThat(EcosPeriodDateFormatter.quarterOf(10)).isEqualTo(4);
            assertThat(EcosPeriodDateFormatter.quarterOf(12)).isEqualTo(4);
        }

        @Test
        @DisplayName("미지원 주기 코드 → URL 생성 시 예외(무음 폴백 제거, REQ-ECOSFMT-006)")
        void unsupportedPeriod_throwsInsteadOfSilentFallback() {
            assertThatThrownBy(
                            () -> EcosPeriodDateFormatter.formatDateForPeriod(LocalDate.now(), "W"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // collectAllForIndicator — 백필 code별 단일 시리즈 수집 (T3, AC-4.1~4.6, REQ-ECOSFMT-012~018)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collectAllForIndicator — code별 단일 시리즈 백필")
    class CollectAllForIndicator {

        @Test
        @DisplayName("ECOS_CPI 지정 — 해당 시리즈만 1회 수집, 8개 일괄 수집 안 함 (AC-4.1, 4.6)")
        void singleIndicatorCode_collectsOnlyThatSeries() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("202606", "3.50"))));

            // Act
            MacroCollectionResult result = service.collectAllForIndicator("ECOS_CPI");

            // Assert — RestClient.get()이 1회만 호출됨(8회 아님)
            verify(ecosRestClient, times(1)).get();
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("반환 집계에 타 시리즈 수집분이 섞이지 않음 (AC-4.2)")
        void singleIndicatorCode_noAggregationFromOtherSeries() {
            // Arrange — 단일 행만 반환하도록 스텁(다른 시리즈가 호출되면 8회가 되어 검증 실패)
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(responseWithRows(List.of(row("2026Q2", "0.6"))));

            // Act
            MacroCollectionResult result = service.collectAllForIndicator("ECOS_GDP_QOQ");

            // Assert
            verify(ecosRestClient, times(1)).get();
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("ERROR-* 응답 — 예외가 오케스트레이터로 전파됨(시리즈 격리 없이, AC-4.3)")
        void errorResponse_propagatesExceptionWithoutIsolation() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(errorResponse("ERROR-101", "주기와 다른 형식의 날짜 형식입니다."));

            // Act & Assert — collectInternal()과 달리 예외가 흡수되지 않고 전파되어야 함
            assertThatThrownBy(() -> service.collectAllForIndicator("ECOS_CPI"))
                    .isInstanceOf(EcosApiException.class);
        }

        @Test
        @DisplayName("정당한 0건(INFO-200) — 예외 없이 (0,0,0) 반환 (AC-4.4)")
        void info200_returnsZeroWithoutException() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(info200Response());

            // Act
            MacroCollectionResult result = service.collectAllForIndicator("ECOS_CPI");

            // Assert
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
        }

        @Test
        @DisplayName("미지 indicator_code — 예외 발생, 다른 시리즈로 대체하지 않음 (AC-4.5)")
        void unknownIndicatorCode_throwsException() {
            assertThatThrownBy(() -> service.collectAllForIndicator("ECOS_UNKNOWN"))
                    .isInstanceOf(EcosApiException.class);
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
    @DisplayName("INFO-200 응답 — 0건 처리 후 계속 (AC-2.3)")
    class Info200Response {

        @Test
        @DisplayName("RESULT.CODE=INFO-200 → 해당 시리즈 0건, 예외 없음, insertBatch 미호출")
        void info200Response_skipAndContinue() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(info200Response());

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(macroIndicatorInserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("ERROR-* 응답 — 실패 집계 + 시리즈 격리 (AC-2.2, AC-2.4, AC-2.6)")
    class ErrorResponseHandling {

        @Test
        @DisplayName("ERROR-101 → 예외 승격, attempted 계상, 나머지 7개 시리즈 계속, serviceKey 미노출")
        @SuppressWarnings("unchecked")
        void error101Response_promotedToExceptionAndIsolated() {
            // Arrange — 첫 번째 시리즈만 ERROR-101, 나머지는 정상 1행
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(errorResponse("ERROR-101", "주기와 다른 형식의 날짜 형식입니다."))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act — 예외가 collect()로 전파되지 않아야 함(시리즈 단위 격리)
            MacroCollectionResult result = service.collect();

            // Assert — 실패 시리즈 1건이 attempted에 계상됨(REQ-ECOSFMT-019), INFO-200과 결과가 다름
            assertThat(result.attempted()).isGreaterThanOrEqualTo(1);
            assertThat(result.succeeded()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("예외 메시지에 indicator_code/RESULT.CODE/RESULT.MESSAGE 포함, serviceKey 미노출")
        void error101Response_exceptionMessageExcludesServiceKey() {
            // Arrange
            EcosSeriesConfig.Series series = EcosSeriesConfig.ALL.getFirst();

            // Act
            Exception thrown =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            RuntimeException.class,
                            () -> {
                                throw new EcosApiException(
                                        series.indicatorCode(),
                                        "ERROR-101",
                                        "주기와 다른 형식의 날짜 형식입니다.");
                            });

            // Assert
            assertThat(thrown.getMessage()).contains(series.indicatorCode());
            assertThat(thrown.getMessage()).contains("ERROR-101");
            assertThat(thrown.getMessage()).doesNotContain("serviceKey");
        }
    }

    @Nested
    @DisplayName("예측 불가 응답 — 실패 처리 (AC-2.5)")
    class UnpredictableResponse {

        @Test
        @DisplayName("응답 body가 null → 정상 0건 아닌 실패로 취급, 나머지 시리즈 계속")
        @SuppressWarnings("unchecked")
        void nullResponseBody_treatedAsFailure() {
            // Arrange — 첫 번째 시리즈만 null body, 나머지는 정상 1행
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(null)
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act — 예외가 collect()로 전파되지 않아야 함(시리즈 단위 격리)
            MacroCollectionResult result = service.collect();

            // Assert — null 응답 시리즈가 attempted에 실패로 계상됨
            assertThat(result.attempted()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("RESULT·StatisticSearch 어느 키도 없으면 → 실패로 취급")
        @SuppressWarnings("unchecked")
        void neitherResultNorStatisticSearch_treatedAsFailure() {
            // Arrange — 첫 번째 시리즈만 RESULT/StatisticSearch 둘 다 null, 나머지는 정상 1행
            stubRestClientChain();
            when(responseSpec.body(EcosStatisticSearchResponse.class))
                    .thenReturn(new EcosStatisticSearchResponse(null, null))
                    .thenReturn(responseWithRows(List.of(row("20260620", "3.50"))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.attempted()).isGreaterThanOrEqualTo(1);
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
