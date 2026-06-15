package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SectorIndexCollectionService 단위 테스트")
class SectorIndexCollectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private KisApiExecutor kisApiExecutor;

    private SectorIndexCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new SectorIndexCollectionService(
                        stockRepository, dailyOhlcvRepository, kisApiExecutor);
    }

    private Stock indexStock(String symbol, long id) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("지수_" + symbol)
                        .market(Market.KRX)
                        .assetType(AssetType.INDEX)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private KisSectorIndexResponse successResponse(
            List<KisSectorIndexResponse.SectorIndexRow> rows) {
        return new KisSectorIndexResponse("0", "MCA00000", "정상처리", rows);
    }

    private KisSectorIndexResponse.SectorIndexRow row(String date, String close) {
        return new KisSectorIndexResponse.SectorIndexRow(
                date, "2700.00", "2720.00", "2690.00", close, "1000000", "50000000000");
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 매핑
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — 매핑 검증")
    class HappyPath {

        @Test
        @DisplayName("KOSPI·KOSDAQ 2종 조회 시 U-API로만 호출하고 daily_ohlcv에 저장")
        void collectsBothSymbols_uApiOnly() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();

            // U-API 2회 호출 (KOSPI + KOSDAQ)
            verify(kisApiExecutor, times(2))
                    .executeGet(
                            argThat(
                                    fn -> {
                                        java.net.URI uri =
                                                fn.apply(
                                                        org.springframework.web.util
                                                                .UriComponentsBuilder
                                                                .newInstance());
                                        return uri.toString().contains("FID_COND_MRKT_DIV_CODE=U");
                                    }),
                            eq("FHKUP03500100"),
                            eq(KisSectorIndexResponse.class));

            // insertIgnoreDuplicate 2회 호출 (각 종목 1행)
            verify(dailyOhlcvRepository, times(2))
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(long.class),
                            any(long.class));
        }

        @Test
        @DisplayName("J-API는 절대 호출하지 않는다 — REQ-BATCH3-021b")
        void neverCallsJApi() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            when(kisApiExecutor.executeGet(any(), anyString(), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            service.collect(TODAY);

            // Assert — J API path가 포함된 URI는 호출되지 않아야 함
            verify(kisApiExecutor, never())
                    .executeGet(
                            argThat(
                                    fn -> {
                                        java.net.URI uri =
                                                fn.apply(
                                                        org.springframework.web.util
                                                                .UriComponentsBuilder
                                                                .newInstance());
                                        return uri.toString().contains("FHKST03010100")
                                                || uri.toString()
                                                        .contains("FID_COND_MRKT_DIV_CODE=J");
                                    }),
                            anyString(),
                            any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 검증 실패 건별 skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증 실패 — 건별 skip")
    class ValidationSkip {

        @Test
        @DisplayName("종가 ≤ 0인 행은 저장하지 않는다")
        void skipRowWithNonPositiveClose() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            KisSectorIndexResponse.SectorIndexRow badRow =
                    new KisSectorIndexResponse.SectorIndexRow(
                            "20260613", "2700.00", "2720.00", "2690.00", "0", "100", "500");
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(badRow)));

            // Act
            service.collect(TODAY);

            // Assert — 저장 없음
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(long.class),
                            any(long.class));
        }

        @Test
        @DisplayName("null 날짜 필드 행은 저장하지 않는다")
        void skipRowWithNullDate() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            KisSectorIndexResponse.SectorIndexRow badRow =
                    new KisSectorIndexResponse.SectorIndexRow(
                            null, "2700.00", "2720.00", "2690.00", "2715.35", "100", "500");
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(badRow)));

            // Act
            service.collect(TODAY);

            // Assert
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(long.class),
                            any(long.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 빈 output2 → 0건 성공
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("빈 응답 — 0건 성공 (REQ-BATCH3-073)")
    class EmptyResponse {

        @Test
        @DisplayName("빈 output2 → succeeded=2 (양쪽 API 호출 성공), 저장 0건")
        void emptyOutput2_countedAsSuccess() {
            // Arrange — KOSPI·KOSDAQ 양쪽 등록, 빈 응답
            Stock kospi = indexStock("0001", 1L);
            Stock kosdaq = indexStock("1001", 2L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi, kosdaq));
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of()));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert — API 호출은 성공(예외 없음) → succeeded 집계, 저장은 0건
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(long.class),
                            any(long.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // INDEX 행 부재 → skip
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("INDEX 행 부재 — skip (REQ-BATCH3-021)")
    class IndexRowAbsent {

        @Test
        @DisplayName("대상 INDEX 행 부재 시 skip하고 API 호출 없음")
        void absentIndexRow_skipWithoutApiCall() {
            // Arrange — DB에 INDEX 행이 없음
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of());

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
            assertThat(result.succeeded()).isZero();
            verify(kisApiExecutor, never()).executeGet(any(), anyString(), any());
        }

        @Test
        @DisplayName("KOSDAQ 행만 부재 시 KOSPI만 수집")
        void onlyKosdaqAbsent_kosPiStillCollected() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi)); // KOSDAQ 없음
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act
            SectorIndexCollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등 저장 — uk_daily_ohlcv")
    class Idempotency {

        @Test
        @DisplayName("동일 행 재수집 시 insertIgnoreDuplicate 재호출 (DB가 중복 무시)")
        void idempotentSave_insertIgnoreCalledAgain() {
            // Arrange
            Stock kospi = indexStock("0001", 1L);
            when(stockRepository.findActiveIndexByMarketAndSymbolIn(eq(Market.KRX), any()))
                    .thenReturn(List.of(kospi));
            when(kisApiExecutor.executeGet(
                            any(), eq("FHKUP03500100"), eq(KisSectorIndexResponse.class)))
                    .thenReturn(successResponse(List.of(row("20260613", "2715.35"))));

            // Act — 2회 실행
            service.collect(TODAY);
            service.collect(TODAY);

            // Assert — insertIgnoreDuplicate 총 2회 (각 실행 1회씩, DB가 중복 무시함)
            verify(dailyOhlcvRepository, times(2))
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(long.class),
                            any(long.class));
        }
    }
}
