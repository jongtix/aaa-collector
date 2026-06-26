package com.aaa.collector.macro.fred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicator;
import com.aaa.collector.macro.MacroIndicatorInserter;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.enums.MacroSource;
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
 * T4 RED — FredCollectionService 단위 테스트 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-031~040).
 *
 * <p>5개 시리즈 매핑, value="." skip, DFF 주말 저장, BigDecimal 변환, 예외 격리 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FredCollectionService — 단위 테스트")
class FredCollectionServiceTest {

    @Mock private RestClient macroFredRestClient;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private MacroIndicatorInserter macroIndicatorInserter;

    @Mock
    @SuppressWarnings("rawtypes")
    private RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock private ResponseSpec responseSpec;

    @Captor private ArgumentCaptor<List<MacroIndicator>> inserterCaptor;

    private FredCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new FredCollectionService(
                        macroFredRestClient, macroIndicatorRepository, macroIndicatorInserter);
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientChain() {
        when(macroFredRestClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(String.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
    }

    private FredObservationsResponse responseWithObs(
            List<FredObservationsResponse.Observation> obs) {
        return new FredObservationsResponse(obs.size(), obs);
    }

    private FredObservationsResponse.Observation obs(String date, String value) {
        return new FredObservationsResponse.Observation(date, value);
    }

    // ────────────────────────────────────────────────────────────────────
    // 5개 시리즈
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 5개 시리즈 수집")
    class SeriesCollection {

        @Test
        @DisplayName("5개 시리즈마다 RestClient.get() 5회 호출")
        void collect_calls5SeriesRequests() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(responseWithObs(List.of(obs("2026-06-17", "3.63"))));

            // Act
            service.collect();

            // Assert
            verify(macroFredRestClient, times(5)).get();
        }

        @Test
        @DisplayName("정상 행 수집 — source=FRED, date→LocalDate 변환")
        void collectNormalRow_storesWithFredSourceAndLocalDate() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(responseWithObs(List.of(obs("2026-06-17", "3.63"))));

            // Act
            service.collect();

            // Assert
            verify(macroIndicatorInserter, atLeastOnce()).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .allMatch(m -> m.getSource() == MacroSource.FRED);
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .anyMatch(m -> LocalDate.of(2026, 6, 17).equals(m.getTradeDate()));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // value="." skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("value='.' — skip (REQ-MACRO-EXT-032)")
    class DotValueSkip {

        @Test
        @DisplayName("value='.' 행 — insertIgnoreDuplicate 미호출")
        void dotValue_skipped() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(responseWithObs(List.of(obs("2026-06-16", "."))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            verify(macroIndicatorInserter, never()).insertBatch(any());
            assertThat(result.skipped()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("정상 행 + '.' 행 혼합 — 정상 행만 저장")
        void mixedRows_onlyValidStored() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(
                            responseWithObs(
                                    List.of(obs("2026-06-17", "3.63"), obs("2026-06-16", "."))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert — 5시리즈 × 2행 = 10 attempted, '.' 행은 skip
            assertThat(result.attempted()).isEqualTo(10);
            assertThat(result.succeeded()).isEqualTo(5);
            assertThat(result.skipped()).isEqualTo(5);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // DFF 주말 행 저장 (REQ-MACRO-EXT-033)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DFF 주말 행 저장 (REQ-MACRO-EXT-033)")
    class WeekendRows {

        @Test
        @DisplayName("주말(토·일) 날짜 행 — skip 없이 저장")
        void weekendDate_storedWithoutSkip() {
            // Arrange — 2026-06-13(토), 2026-06-14(일)
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(
                            responseWithObs(
                                    List.of(obs("2026-06-13", "5.33"), obs("2026-06-14", "5.33"))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert — 주말 행도 저장됨 (skip 아님)
            assertThat(result.succeeded()).isGreaterThanOrEqualTo(2);
            verify(macroIndicatorInserter, atLeastOnce()).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .anyMatch(m -> LocalDate.of(2026, 6, 13).equals(m.getTradeDate()));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // BigDecimal 변환
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BigDecimal 변환")
    class BigDecimalConversion {

        @Test
        @DisplayName("소수점 포함 값 'NaN' → skip")
        void nanValue_skipped() {
            // Arrange
            stubRestClientChain();
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(responseWithObs(List.of(obs("2026-06-17", "NaN"))));

            // Act
            MacroCollectionResult result = service.collect();

            // Assert
            assertThat(result.skipped()).isGreaterThanOrEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 격리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 격리")
    class ExceptionIsolation {

        @Test
        @DisplayName("첫 번째 시리즈 retrieve 예외 — 나머지 4개 시리즈 계속")
        @SuppressWarnings("unchecked")
        void firstSeriesException_remaining4Continue() {
            // Arrange
            when(macroFredRestClient.get()).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.uri(any(String.class))).thenReturn(requestHeadersUriSpec);
            when(requestHeadersUriSpec.retrieve())
                    .thenThrow(new RuntimeException("connection refused"))
                    .thenReturn(responseSpec);
            when(responseSpec.body(FredObservationsResponse.class))
                    .thenReturn(responseWithObs(List.of(obs("2026-06-17", "3.63"))));

            // Act — 예외가 collect()로 전파되지 않아야 함
            MacroCollectionResult result = service.collect();

            // Assert — 나머지 4개 시리즈 처리됨
            assertThat(result.attempted()).isGreaterThanOrEqualTo(4);
        }
    }
}
