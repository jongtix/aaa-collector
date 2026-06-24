package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchMetricsWarmStarter — 부팅 시 last-load gauge 초기화 (SPEC-OBSV-WARMSTART-001)")
class BatchMetricsWarmStarterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private BatchMetrics batchMetrics;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private InvestorTrendRepository investorTrendRepository;
    @Mock private CreditBalanceRepository creditBalanceRepository;
    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private AnalystEstimateRepository analystEstimateRepository;
    @Mock private FinancialRepository financialRepository;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;

    @InjectMocks private BatchMetricsWarmStarter warmStarter;

    @Nested
    @DisplayName("정상 set — 조회 결과 있으면 warmLastLoad 호출됨")
    class NormalSet {

        @Test
        @DisplayName("domestic-daily 조회 결과가 있으면 warmLastLoad('domestic-daily', instant) 호출")
        void warmsDomesticDailyWhenResultPresent() throws Exception {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 16, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();

            stubAllEmpty();
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(
                            Optional.of(kstTime), // domestic-daily (KOSPI/KOSDAQ/KRX)
                            Optional.empty() // overseas-daily (NYSE/NASDAQ/AMEX/US)
                            );

            // Act
            warmStarter.run(null);

            // Assert
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(batchMetrics).warmLastLoad(eq("domestic-daily"), captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("빈 테이블 skip — Optional.empty() 반환 시 warmLastLoad 미호출")
    class EmptyTableSkip {

        @Test
        @DisplayName("모든 리포지토리가 empty를 반환하면 warmLastLoad가 한 번도 호출되지 않는다")
        void skipsAllWhenAllRepositoriesEmpty() throws Exception {
            // Arrange
            stubAllEmpty();

            // Act
            warmStarter.run(null);

            // Assert
            verify(batchMetrics, never()).warmLastLoad(any(), any());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 배치 조회 실패 시 나머지 계속 처리")
    class FailureIsolation {

        @Test
        @DisplayName("domestic-daily 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneBatchThrows() {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 10, 0, 0);
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 연결 실패"))
                    .thenReturn(Optional.of(kstTime)); // overseas-daily는 정상
            stubNonDailyEmpty();

            // Act & Assert
            assertThatNoException().isThrownBy(() -> warmStarter.run(null));
        }

        @Test
        @DisplayName("한 배치 조회가 실패해도 나머지 배치는 warmLastLoad 호출이 계속된다")
        void warmsContinuingBatchesAfterOneFailure() throws Exception {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 10, 0, 0);
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 오류")) // domestic-daily 실패
                    .thenReturn(Optional.of(kstTime)); // overseas-daily 성공
            stubNonDailyEmpty();

            // Act
            warmStarter.run(null);

            // Assert: overseas-daily는 정상 호출
            verify(batchMetrics).warmLastLoad(eq("overseas-daily"), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("제외 3종 미포함 — warm-start 후 domestic-news 등은 미호출")
    class ExcludedBatches {

        @Test
        @DisplayName("domestic-news로 warmLastLoad가 호출된 적 없다")
        void doesNotWarmDomesticNews() throws Exception {
            stubAllEmpty();
            warmStarter.run(null);
            verify(batchMetrics, never()).warmLastLoad(eq("domestic-news"), any());
        }

        @Test
        @DisplayName("watchlist-sync-krx로 warmLastLoad가 호출된 적 없다")
        void doesNotWarmWatchlistSyncKrx() throws Exception {
            stubAllEmpty();
            warmStarter.run(null);
            verify(batchMetrics, never()).warmLastLoad(eq("watchlist-sync-krx"), any());
        }

        @Test
        @DisplayName("watchlist-sync-us로 warmLastLoad가 호출된 적 없다")
        void doesNotWarmWatchlistSyncUs() throws Exception {
            stubAllEmpty();
            warmStarter.run(null);
            verify(batchMetrics, never()).warmLastLoad(eq("watchlist-sync-us"), any());
        }
    }

    /** 모든 리포지토리를 Optional.empty() 반환으로 stub. */
    private void stubAllEmpty() {
        when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any())).thenReturn(Optional.empty());
        stubNonDailyEmpty();
    }

    /** DailyOhlcvRepository 제외 나머지 리포지토리를 Optional.empty()로 stub. */
    private void stubNonDailyEmpty() {
        when(investorTrendRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(creditBalanceRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(shortSaleDomesticRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(shortSaleOverseasRepository.findMaxDailyCollectedAt()).thenReturn(Optional.empty());
        when(shortSaleOverseasRepository.findMaxInterestCollectedAt()).thenReturn(Optional.empty());
        when(analystEstimateRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(financialRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(macroIndicatorRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(marketIndicatorRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(etfRepresentativeHistoryRepository.findMaxEffectiveFrom())
                .thenReturn(Optional.empty());
    }
}
