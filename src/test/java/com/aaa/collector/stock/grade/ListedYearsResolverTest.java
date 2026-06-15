package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
@ExtendWith(MockitoExtension.class)
@DisplayName("ListedYearsResolver 단위 테스트 (REQ-STOCKMETA-013, M3 1안)")
class ListedYearsResolverTest {

    @Mock private DailyOhlcvRepository dailyOhlcvRepository;

    @InjectMocks private ListedYearsResolver resolver;

    private static Stock stockWith(AssetType assetType, LocalDate listedDate) {
        Stock stock =
                Stock.builder()
                        .symbol("TEST")
                        .nameKo("테스트")
                        .market(Market.KOSPI)
                        .assetType(assetType)
                        .listedDate(listedDate)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    @Nested
    @DisplayName("listedDate non-null — 날짜 기반 계산")
    class WithListedDate {

        @Test
        @DisplayName("listedDate 10년 전 — 10년 이상 반환 (OHLCV 미조회)")
        void resolve_tenYearsAgo_returnsAboutTen() {
            Stock stock = stockWith(AssetType.STOCK, LocalDate.now().minusYears(10));

            double years = resolver.resolve(stock);

            assertThat(years).isGreaterThan(9.9);
            verify(dailyOhlcvRepository, never()).findMinTradeDateByStockId(any());
        }

        @Test
        @DisplayName("listedDate 3년 전 — 3년 이상 반환 (OHLCV 미조회)")
        void resolve_threeYearsAgo_returnsAboutThree() {
            Stock stock = stockWith(AssetType.STOCK, LocalDate.now().minusYears(3));

            double years = resolver.resolve(stock);

            assertThat(years).isGreaterThan(2.9);
            verify(dailyOhlcvRepository, never()).findMinTradeDateByStockId(any());
        }
    }

    @Nested
    @DisplayName("INDEX/COMMODITY — OHLCV 미조회 (T-008, AC-8)")
    class IndexCommodityEarlyReturn {

        @Test
        @DisplayName("INDEX + listedDate=null — OHLCV 미조회, ESTABLISHED_FALLBACK 반환")
        void resolve_indexNullListedDate_noOhlcvQuery() {
            Stock stock = stockWith(AssetType.INDEX, null);

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
            verify(dailyOhlcvRepository, never()).findMinTradeDateByStockId(any());
        }

        @Test
        @DisplayName("COMMODITY + listedDate=null — OHLCV 미조회, ESTABLISHED_FALLBACK 반환 (AC-8)")
        void commodityStockWithNullListedDate_doesNotQueryOhlcv() {
            Stock stock = stockWith(AssetType.COMMODITY, null);

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
            verify(dailyOhlcvRepository, never()).findMinTradeDateByStockId(any());
        }
    }

    @Nested
    @DisplayName("listedDate=null + STOCK/ETF — OHLCV MIN 근사 (M3 1안)")
    class NullListedDateOhlcvApproximation {

        @Test
        @DisplayName("OHLCV 없음 — NEW_STOCK_FALLBACK(0.0) 반환 (A 미수여)")
        void resolve_noOhlcv_returnsNewFallback() {
            Stock stock = stockWith(AssetType.STOCK, null);
            when(dailyOhlcvRepository.findMinTradeDateByStockId(1L)).thenReturn(Optional.empty());

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.NEW_STOCK_FALLBACK_YEARS);
        }

        @Test
        @DisplayName("OHLCV MIN >= 7년 전 — ESTABLISHED_FALLBACK(100.0) 반환 (A 등급 후보)")
        void resolve_ohlcvMinSevenOrMoreYearsAgo_returnsEstablishedFallback() {
            Stock stock = stockWith(AssetType.STOCK, null);
            LocalDate eightYearsAgo = LocalDate.now().minusYears(8);
            when(dailyOhlcvRepository.findMinTradeDateByStockId(1L))
                    .thenReturn(Optional.of(eightYearsAgo));

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
        }

        @Test
        @DisplayName("OHLCV MIN < 7년 전 — elapsed 반환 (A 미수여)")
        void resolve_ohlcvMinLessThanSevenYears_returnsElapsed() {
            Stock stock = stockWith(AssetType.STOCK, null);
            LocalDate threeYearsAgo = LocalDate.now().minusYears(3);
            when(dailyOhlcvRepository.findMinTradeDateByStockId(1L))
                    .thenReturn(Optional.of(threeYearsAgo));

            double years = resolver.resolve(stock);

            assertThat(years)
                    .isGreaterThan(2.9)
                    .isLessThan(GradeConstants.ESTABLISHED_YEARS_THRESHOLD);
        }

        @Test
        @DisplayName("OHLCV MIN 정확히 7년+1일 전 — ESTABLISHED_FALLBACK 반환 (경계: >=7 조건 만족)")
        void resolve_ohlcvMinExactlySevenYearsPlusOneDay_returnsEstablishedFallback() {
            Stock stock = stockWith(AssetType.STOCK, null);
            // 7년 전에서 1일 더 이전: 경과일 기준 >= 7.0 확실히 만족
            LocalDate sevenYearsPlusOneDay = LocalDate.now().minusYears(7).minusDays(1);
            when(dailyOhlcvRepository.findMinTradeDateByStockId(1L))
                    .thenReturn(Optional.of(sevenYearsPlusOneDay));

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.ESTABLISHED_FALLBACK_YEARS);
        }

        @Test
        @DisplayName(
                "ETF + listedDate=null + OHLCV 없음 — NEW_STOCK_FALLBACK 반환 (ETF도 INDEX/COMMODITY 아님)")
        void resolve_etfNullListedDateNoOhlcv_returnsNewFallback() {
            Stock stock = stockWith(AssetType.ETF, null);
            when(dailyOhlcvRepository.findMinTradeDateByStockId(1L)).thenReturn(Optional.empty());

            double years = resolver.resolve(stock);

            assertThat(years).isEqualTo(ListedYearsResolver.NEW_STOCK_FALLBACK_YEARS);
        }
    }
}
