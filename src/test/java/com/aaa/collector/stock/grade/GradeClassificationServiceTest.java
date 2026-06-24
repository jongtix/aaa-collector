package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GradeClassificationService 단위 테스트 (SPEC-COLLECTOR-GRADE-004).
 *
 * <p>데이터 게이트(holdingDays) + ADTV 배치 조회 → GradeClassifier 위임 → persistSingle 흐름 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GradeClassificationService — 데이터 게이트 + ADTV 기반 분류")
class GradeClassificationServiceTest {

    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private GradeClassifier gradeClassifier;
    @Mock private StockGradePersistService stockGradePersistService;

    @InjectMocks private GradeClassificationService service;

    private Stock buildKrxStock(String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    private Stock buildUsStock(String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("Test_" + symbol)
                        .market(Market.NYSE)
                        .assetType(AssetType.STOCK)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 2L);
        return stock;
    }

    // -----------------------------------------------------------------------
    // AC-9: cold-start no-op
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-9 — cold-start(빈 활성 종목) → no-op")
    class ColdStart {

        @Test
        @DisplayName("classifyDomestic(): 활성 종목 빈 결과 → persistSingle 미호출")
        void classifyDomestic_noActiveStocks_noop() {
            when(stockRepository.findAllActive()).thenReturn(Collections.emptyList());

            service.classifyDomestic();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("classifyOverseas(): 활성 종목 빈 결과 → persistSingle 미호출")
        void classifyOverseas_noActiveStocks_noop() {
            when(stockRepository.findAllActive()).thenReturn(Collections.emptyList());

            service.classifyOverseas();

            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // AC-10: 개별 종목 예외 격리
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-10 — 개별 종목 예외 격리 (종목별 try-catch)")
    class ExceptionIsolation {

        @Test
        @DisplayName("한 종목 classify 예외 → warn log + 나머지 계속 처리 (crash 없음)")
        void classifyDomestic_oneStockThrows_continuesOthers() {
            // Arrange
            Stock kospi1 = buildKrxStock("005930");
            Stock kospi2 = buildKrxStock("000660");
            ReflectionTestUtils.setField(kospi2, "id", 2L);

            when(stockRepository.findAllActive()).thenReturn(List.of(kospi1, kospi2));
            when(dailyOhlcvRepository.countByStockId(1L)).thenReturn(1000L);
            when(dailyOhlcvRepository.countByStockId(2L)).thenReturn(1000L);
            when(dailyOhlcvRepository.findRecent20DayAdtvByStockIds(anyList()))
                    .thenReturn(buildAdtvRows(new Object[] {1L, 6e10}, new Object[] {2L, 6e10}));
            when(gradeClassifier.classify(any()))
                    .thenThrow(new RuntimeException("분류 오류"))
                    .thenReturn(Grade.A);

            // Act & Assert: crash 없이 종료
            assertThatCode(service::classifyDomestic).doesNotThrowAnyException();
        }
    }

    // -----------------------------------------------------------------------
    // AC-11: 일봉 결손 → C 분류
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-11 — 일봉 결손(0행) → C 분류 (보류 아님)")
    class ZeroOhlcvToC {

        @Test
        @DisplayName("countByStockId=0 + ADTV 미포함 → GradeClassifier에 holdingDays=0/adtv=0.0 전달 → C")
        void classifyDomestic_zeroOhlcv_classifiesAsC() {
            // Arrange
            Stock kospi = buildKrxStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));
            when(dailyOhlcvRepository.countByStockId(1L)).thenReturn(0L);
            when(dailyOhlcvRepository.findRecent20DayAdtvByStockIds(anyList()))
                    .thenReturn(Collections.emptyList()); // 0행이면 ADTV 결과도 빈 리스트
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            // Act
            service.classifyDomestic();

            // Assert: persistSingle 호출됨 (C로 분류, 보류 아님)
            verify(stockGradePersistService)
                    .persistSingle(eq(kospi), eq(Grade.C), any(ZonedDateTime.class));
        }
    }

    // -----------------------------------------------------------------------
    // 시장 분리 — KRX/US 진입점
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시장 분리 — classifyDomestic()은 KRX만, classifyOverseas()는 US만")
    class MarketSeparation {

        @Test
        @DisplayName("classifyDomestic(): KOSPI 종목 → KRX 시장으로 분류")
        void classifyDomestic_kospiStock_classifiedWithKrxMarket() {
            // Arrange
            Stock kospi = buildKrxStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));
            when(dailyOhlcvRepository.countByStockId(1L)).thenReturn(800L);
            when(dailyOhlcvRepository.findRecent20DayAdtvByStockIds(anyList()))
                    .thenReturn(buildAdtvRows(new Object[] {1L, 6e10}));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classifyDomestic();

            // Assert: GradeInput.market() == "KRX"
            org.mockito.ArgumentCaptor<GradeInput> captor =
                    org.mockito.ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().market()).isEqualTo("KRX");
        }

        @Test
        @DisplayName("classifyDomestic(): NYSE 종목 → 처리 스킵 (KRX 시장 아님)")
        void classifyDomestic_nyseStock_skipped() {
            // Arrange — NYSE 종목만 존재 (KRX 필터 후 빈 리스트 → early return)
            Stock nyse = buildUsStock("AAPL");
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));

            // Act
            service.classifyDomestic();

            // Assert: NYSE 종목은 persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("classifyOverseas(): NYSE 종목 → US 시장으로 분류")
        void classifyOverseas_nyseStock_classifiedWithUsMarket() {
            // Arrange
            Stock nyse = buildUsStock("AAPL");
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));
            when(dailyOhlcvRepository.countByStockId(2L)).thenReturn(800L);
            when(dailyOhlcvRepository.findRecent20DayAdtvByStockIds(anyList()))
                    .thenReturn(buildAdtvRows(new Object[] {2L, 3e9}));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classifyOverseas();

            // Assert: GradeInput.market() == "US"
            org.mockito.ArgumentCaptor<GradeInput> captor =
                    org.mockito.ArgumentCaptor.forClass(GradeInput.class);
            verify(gradeClassifier).classify(captor.capture());
            assertThat(captor.getValue().market()).isEqualTo("US");
        }

        @Test
        @DisplayName("classifyOverseas(): KOSPI 종목 → 처리 스킵")
        void classifyOverseas_kospiStock_skipped() {
            // Arrange — KOSPI 종목만 존재 (US 필터 후 빈 리스트 → early return)
            Stock kospi = buildKrxStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));

            // Act
            service.classifyOverseas();

            // Assert
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    /** Object[][] → List<Object[]> 변환 헬퍼. List.of는 varargs 타입추론 실패로 사용 불가. */
    private static List<Object[]> buildAdtvRows(Object[]... rows) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] row : rows) {
            list.add(row);
        }
        return list;
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertThat(String actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
