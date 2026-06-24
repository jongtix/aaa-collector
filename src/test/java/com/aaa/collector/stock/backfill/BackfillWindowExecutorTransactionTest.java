package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisTokenIssueException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    // SpyBean: 실제 구현 유지, updateProgress 선택적 stubbing 가능 (AC-4.2 롤백 테스트)
    @MockitoSpyBean private BackfillStatusRepository backfillStatusRepository;

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
    @DisplayName("AC-4.2 트랜잭션 롤백")
    class TransactionRollback {

        @Test
        @DisplayName(
                "executeWindow() — updateProgress 예외 시 수집 데이터·status 변경 모두 롤백된다 (REQ-BACKFILL-031)")
        void executeWindow_rollbackOnUpdateProgressFailure_neitherDataNorStatusPersists()
                throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            String originalStatus = status.getStatus(); // PENDING
            LocalDate originalDate = status.getLastCollectedDate(); // null

            LocalDate oldest = LocalDate.of(2025, 1, 1);
            when(domesticOhlcvService.collectWindow(any(), any(), any(), any()))
                    .thenReturn(new BackfillWindowResult(oldest, 50));

            // updateProgress 호출 시 RuntimeException 발생 → 트랜잭션 롤백
            doThrow(new RuntimeException("DB write failure — rollback trigger"))
                    .when(backfillStatusRepository)
                    .updateProgress(anyLong(), anyString(), any(), anyInt(), any());

            // Act & Assert — 예외가 전파됨
            assertThatThrownBy(() -> windowExecutor.executeWindow(status, domesticStock, session))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB write failure");

            // Assert — 롤백: status 행은 원래 값 유지
            BackfillStatus afterRollback =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(afterRollback.getStatus()).isEqualTo(originalStatus);
            assertThat(afterRollback.getLastCollectedDate()).isEqualTo(originalDate);
        }
    }

    @Nested
    @DisplayName("AC-6.3 오류 분류 — retryable/non-retryable (REQ-BACKFILL-030)")
    class ErrorClassification {

        @Test
        @DisplayName(
                "executeWindowOnError() — retryable=true → status=IN_PROGRESS, last_error 기록 (AC-6.3a)")
        void executeWindowOnError_retryableError_keepsInProgress() {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            String errorMsg = "HTTP 500 Internal Server Error";

            // Act
            windowExecutor.executeWindowOnError(status, errorMsg, true);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(updated.getLastError()).contains("HTTP 500");
            // last_collected_date는 변경되지 않음
            assertThat(updated.getLastCollectedDate()).isNull();
        }

        @Test
        @DisplayName(
                "executeWindowOnError() — KisTokenIssueException(비재시도) → status=FAILED (AC-6.3b)")
        void executeWindowOnError_nonRetryableKisTokenError_setsFailed() {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            KisTokenIssueException tokenEx =
                    new KisTokenIssueException("test-alias", new RuntimeException("auth error"));
            String errorMsg = tokenEx.getMessage();
            boolean retryable = windowExecutor.isRetryable(tokenEx);

            // Act
            windowExecutor.executeWindowOnError(status, errorMsg, retryable);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("AC-1 resolveAnchor — 초기 anchor 날짜 (REQ-BACKFILL-060)")
    class ResolveAnchor {

        @Test
        @DisplayName("AC-1.1 last_collected_date=null → anchor = 어제(LocalDate.now().minusDays(1))")
        void resolveAnchor_nullLastCollectedDate_usesYesterday() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "investor_trend");
            LocalDate yesterday = LocalDate.now().minusDays(1);
            when(investorTrendService.collectWindow(any(), any(), any()))
                    .thenReturn(new BackfillWindowResult(yesterday.minusDays(30), 30));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — investorTrendService가 어제 날짜로 호출됐는지 확인 (AC-1.1)
            verify(investorTrendService).collectWindow(any(), any(), eq(yesterday));
        }

        @Test
        @DisplayName("AC-1.3 last_collected_date 설정된 경우 → nextAnchor 위임 (기존 동작 불변)")
        void resolveAnchor_existingLastCollectedDate_delegatesToNextAnchor()
                throws InterruptedException {
            // Arrange — 이미 수집된 기록이 있는 status 생성
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

            LocalDate lastCollected = LocalDate.of(2025, 6, 1);
            backfillStatusRepository.updateProgress(
                    raw.getId(), "IN_PROGRESS", lastCollected, 0, 30);
            BackfillStatus status = backfillStatusRepository.findById(raw.getId()).orElseThrow();

            when(investorTrendService.collectWindow(any(), any(), any()))
                    .thenReturn(BackfillWindowResult.EMPTY);

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — 오늘도, 어제도 아닌 nextAnchor(lastCollected) 값으로 호출 (AC-1.3)
            verify(investorTrendService)
                    .collectWindow(any(), any(), eq(lastCollected.minusDays(1)));
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
