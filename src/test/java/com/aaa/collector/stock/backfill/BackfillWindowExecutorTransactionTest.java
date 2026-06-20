package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("BackfillWindowExecutor 트랜잭션 통합 테스트 (AC-4.1/4.2)")
class BackfillWindowExecutorTransactionTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    // 수집 서비스 전체 모킹 — KIS 미호출
    @MockitoBean private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @MockitoBean private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @MockitoBean private ShortSaleCollectionService shortSaleService;
    @MockitoBean private InvestorTrendCollectionService investorTrendService;
    @MockitoBean private CreditBalanceCollectionService creditBalanceService;

    @Autowired private BackfillWindowExecutor windowExecutor;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    private LeaseSession session;
    private Stock domesticStock;

    @BeforeEach
    void setUp() {
        session = org.mockito.Mockito.mock(LeaseSession.class);
        domesticStock =
                Stock.builder()
                        .symbol("005930")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(true)
                        .build();

        backfillStatusRepository.deleteAllInBatch();
    }

    private BackfillStatus seedPending(String symbol, String dataTable) {
        backfillStatusRepository.insertIgnoreSeed("STOCK", symbol, dataTable);
        return backfillStatusRepository
                .findByStatusInAndTargetTypeOrderById(java.util.List.of("PENDING"), "STOCK")
                .stream()
                .filter(s -> s.getTargetCode().equals(symbol) && s.getDataTable().equals(dataTable))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("AC-4.1 수집+상태 동일 트랜잭션 커밋")
    class TransactionCommit {

        @Test
        @DisplayName(
                "executeWindow() — 성공 시 status가 IN_PROGRESS로 갱신된다 (최초 윈도우, lastCollectedDate=null)")
        void executeWindow_firstWindow_setsInProgress() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            LocalDate oldest = LocalDate.of(2025, 1, 1);
            when(domesticOhlcvService.collectWindow(any(), any(), any(), any()))
                    .thenReturn(
                            new BackfillWindowResult(oldest, 100)); // 100건 = GROUP_A IN_PROGRESS 유지

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(updated.getLastCollectedDate()).isEqualTo(oldest);
            assertThat(updated.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("executeWindow() — GROUP_A 100건 미만 반환 시 COMPLETED로 전이")
        void executeWindow_groupATermination_setsCompleted() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            LocalDate oldest = LocalDate.of(2020, 1, 2);
            when(domesticOhlcvService.collectWindow(any(), any(), any(), any()))
                    .thenReturn(new BackfillWindowResult(oldest, 50)); // 50 < 100 → COMPLETED

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("GROUP_B stale 종료")
    class GroupBStaleTermination {

        @Test
        @DisplayName("executeWindow() — GROUP_B staleCount가 임계(3) 도달 시 COMPLETED로 전이")
        void executeWindow_groupBNoProgress_setsCompleted_afterNRuns() throws InterruptedException {
            // Arrange — staleCount=2(=threshold-1)인 기존 항목 직접 생성
            backfillStatusRepository.insertIgnoreSeed("STOCK", "005930", "investor_trend");
            BackfillStatus raw =
                    backfillStatusRepository
                            .findByStatusInAndTargetTypeOrderById(
                                    java.util.List.of("PENDING"), "STOCK")
                            .stream()
                            .filter(
                                    s ->
                                            "005930".equals(s.getTargetCode())
                                                    && "investor_trend".equals(s.getDataTable()))
                            .findFirst()
                            .orElseThrow();

            // staleCount=2 으로 설정 (직전 2회 무전진)
            backfillStatusRepository.updateProgress(
                    raw.getId(), "IN_PROGRESS", LocalDate.of(2024, 6, 1), 2, 5);

            BackfillStatus status = backfillStatusRepository.findById(raw.getId()).orElseThrow();

            // 이번 윈도우도 무전진(0건)
            when(investorTrendService.collectWindow(any(), any(), any()))
                    .thenReturn(BackfillWindowResult.EMPTY);

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        }
    }
}
