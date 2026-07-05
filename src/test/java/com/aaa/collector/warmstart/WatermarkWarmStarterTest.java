package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.exthours.ExtendedHoursRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("WatermarkWarmStarter — 부팅 시 워터마크 게이지 초기화 (REQ-WM-003)")
class WatermarkWarmStarterTest {

    @Mock private WatermarkMetrics watermarkMetrics;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private InvestorTrendRepository investorTrendRepository;
    @Mock private CreditBalanceRepository creditBalanceRepository;
    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private AnalystEstimateRepository analystEstimateRepository;
    @Mock private DisclosureRepository disclosureRepository;
    @Mock private DomesticNewsHeadlineRepository domesticNewsHeadlineRepository;
    @Mock private OverseasNewsHeadlineRepository overseasNewsHeadlineRepository;
    @Mock private ExtendedHoursRepository extendedHoursRepository;

    @InjectMocks private WatermarkWarmStarter warmStarter;

    @Nested
    @DisplayName("정상 resync — 조회 결과 있으면 resync(series, date) 호출됨")
    class NormalResync {

        @Test
        @DisplayName("daily-ohlcv-krx 조회 결과가 있으면 resync(DAILY_OHLCV_KRX, date) 호출")
        void resyncsDailyOhlcvKrxWhenResultPresent() {
            // Arrange
            LocalDate tradeDate = LocalDate.of(2026, 7, 3);
            stubAllEmpty();
            when(dailyOhlcvRepository.findMaxTradeDateByMarketsIn(any()))
                    .thenReturn(Optional.of(tradeDate), Optional.empty());

            // Act
            warmStarter.run(null);

            // Assert
            verify(watermarkMetrics).resync(WatermarkSeries.DAILY_OHLCV_KRX, tradeDate);
        }

        @Test
        @DisplayName("news-domestic은 LocalDateTime을 LocalDate로 변환해 resync한다")
        void resyncsNewsDomesticAsLocalDate() {
            // Arrange
            LocalDateTime publishedAt = LocalDateTime.of(2026, 7, 3, 9, 0);
            stubAllEmpty();
            when(domesticNewsHeadlineRepository.findMaxPublishedAt())
                    .thenReturn(Optional.of(publishedAt));

            // Act
            warmStarter.run(null);

            // Assert
            verify(watermarkMetrics)
                    .resync(WatermarkSeries.NEWS_DOMESTIC, publishedAt.toLocalDate());
        }
    }

    @Nested
    @DisplayName("빈 테이블 — Optional.empty() 반환 시 resync(series, null) 호출")
    class EmptyTable {

        @Test
        @DisplayName("모든 리포지토리가 empty이면 모든 시리즈에 resync(series, null)이 호출된다")
        void resyncsNullWhenAllEmpty() {
            // Arrange
            stubAllEmpty();

            // Act
            warmStarter.run(null);

            // Assert
            for (WatermarkSeries series : WatermarkSeries.values()) {
                verify(watermarkMetrics).resync(series, null);
            }
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 시리즈 조회 실패 시 나머지 계속 처리")
    class FailureIsolation {

        @Test
        @DisplayName("daily-ohlcv-krx 조회에서 예외가 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneSeriesThrows() {
            // Arrange
            when(dailyOhlcvRepository.findMaxTradeDateByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 연결 실패"))
                    .thenReturn(Optional.empty());
            stubNonDailyEmpty();

            // Act & Assert
            assertThatNoException().isThrownBy(() -> warmStarter.run(null));
        }

        @Test
        @DisplayName("한 시리즈 조회가 실패해도 나머지 시리즈는 resync가 계속 호출된다")
        void resyncsRemainingSeriesAfterOneFailure() {
            // Arrange
            LocalDate tradeDate = LocalDate.of(2026, 7, 3);
            when(dailyOhlcvRepository.findMaxTradeDateByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 오류"))
                    .thenReturn(Optional.of(tradeDate));
            stubNonDailyEmpty();

            // Act
            warmStarter.run(null);

            // Assert
            verify(watermarkMetrics).resync(WatermarkSeries.DAILY_OHLCV_US, tradeDate);
        }
    }

    private void stubAllEmpty() {
        when(dailyOhlcvRepository.findMaxTradeDateByMarketsIn(any())).thenReturn(Optional.empty());
        stubNonDailyEmpty();
    }

    private void stubNonDailyEmpty() {
        when(investorTrendRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(creditBalanceRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(shortSaleDomesticRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(shortSaleOverseasRepository.findMaxDailyTradeDate()).thenReturn(Optional.empty());
        when(shortSaleOverseasRepository.findMaxShortInterestDate()).thenReturn(Optional.empty());
        when(marketIndicatorRepository.findMaxTradeDateByIndicatorCode(any()))
                .thenReturn(Optional.empty());
        when(macroIndicatorRepository.findMaxTradeDateBySource(any())).thenReturn(Optional.empty());
        when(analystEstimateRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(disclosureRepository.findMaxRceptDt()).thenReturn(Optional.empty());
        when(domesticNewsHeadlineRepository.findMaxPublishedAt()).thenReturn(Optional.empty());
        when(overseasNewsHeadlineRepository.findMaxPublishedAt()).thenReturn(Optional.empty());
        when(extendedHoursRepository.findMaxTradeDateBySession(any())).thenReturn(Optional.empty());
    }
}
