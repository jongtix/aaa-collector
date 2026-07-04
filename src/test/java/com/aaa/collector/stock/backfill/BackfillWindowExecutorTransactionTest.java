package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M2-T1 격리 분류 — 싱글턴 공유 제외(전용 컨테이너). M2-T3(REQ-DBGRANT3-013)에서 {@code @BeforeEach} 정리를 {@link
 * RootFixtureCleaner#deleteAllBackfillStatus(String)} root 커넥션으로 재배선했다 — 테스트 대상 코드 경로(리포지토리 호출,
 * {@code advance()}/{@code fail()} dirty-check 포함)는 앱 datasource를 그대로 사용.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("BackfillWindowExecutor 트랜잭션 통합 테스트 (AC-1/AC-2/AC-4.1/4.2)")
@Tag("integration")
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
    @MockitoBean private RevSplitCollectionService revSplitService;

    @Autowired private BackfillWindowExecutor windowExecutor;
    // SpyBean: 실제 구현 유지, advance() spy 기반 예외 주입 가능 (AC-4.2 롤백 테스트)
    @MockitoSpyBean private BackfillStatusRepository backfillStatusRepository;

    private LeaseSession session;
    private Stock domesticStock;

    @BeforeEach
    void setUp() throws SQLException {
        session = Mockito.mock(LeaseSession.class);
        domesticStock =
                Stock.builder()
                        .symbol("005930")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(true)
                        .build();

        // root 커넥션으로 정리(M2-T3)
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
    }

    private BackfillStatus seedPending(String symbol, String dataTable) {
        backfillStatusRepository.insertIgnoreSeed("STOCK", symbol, dataTable);
        return backfillStatusRepository
                .findByStatusInAndTargetTypeOrderById(List.of(BackfillStatusType.PENDING), "STOCK")
                .stream()
                .filter(s -> s.getTargetCode().equals(symbol) && s.getDataTable().equals(dataTable))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------------------
    // AC-1: persistWindow 트랜잭션 원자성
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AC-1 persistWindow 트랜잭션 원자성 (REQ-TXB-030)")
    class PersistWindowTransaction {

        @Test
        @DisplayName("persistWindow() — 성공 시 INSERT + advance() dirty-check 커밋 (AC-1 커밋)")
        void persistWindow_commit_updatesStatus() {
            // Arrange
            BackfillStatus status = seedPending("005930", "investor_trend");
            LocalDate oldest = LocalDate.of(2025, 1, 1);
            InvestorTrendFetch fetch = new InvestorTrendFetch(List.of(), oldest, 30);
            when(investorTrendService.persistWindow(any(), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 30));

            // Act
            windowExecutor.persistWindow(status, domesticStock, fetch);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(updated.getLastCollectedDate()).isEqualTo(oldest);
        }

        @Test
        @DisplayName(
                "persistWindow() — advance() 예외 시 INSERT + status 변경 모두 롤백 (AC-1 롤백,"
                        + " REQ-BACKFILL-031)")
        void persistWindow_rollback_onUpdateProgressFailure() {
            // Arrange
            BackfillStatus status = seedPending("005930", "investor_trend");
            LocalDate oldest = LocalDate.of(2025, 1, 1);
            InvestorTrendFetch fetch = new InvestorTrendFetch(List.of(), oldest, 30);
            when(investorTrendService.persistWindow(any(), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 30));

            // advance() 호출 시 RuntimeException → 트랜잭션 롤백
            BackfillStatus spyStatus = Mockito.spy(status);
            doThrow(new RuntimeException("DB write failure — rollback trigger"))
                    .when(spyStatus)
                    .advance(any(), any(), anyInt(), any());
            when(backfillStatusRepository.findById(status.getId()))
                    .thenReturn(Optional.of(spyStatus));

            // Act & Assert — 예외 전파
            assertThatThrownBy(() -> windowExecutor.persistWindow(status, domesticStock, fetch))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB write failure");

            // Assert — 롤백: spyStatus는 advance() 실패로 상태가 변경되지 않음 (PENDING 유지)
            BackfillStatus afterRollback =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(afterRollback.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(afterRollback.getLastCollectedDate()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // AC-2: fetchWindow 비트랜잭션 증명
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AC-2 fetchWindow 비트랜잭션 증명 (REQ-TXB-020)")
    class FetchWindowNoTransaction {

        @Test
        @DisplayName("fetchWindow() — @Transactional 없음: 서비스 호출 중 활성 트랜잭션 미점유 (AC-2)")
        void fetchWindow_noActiveTransaction_duringServiceCall() throws InterruptedException {
            // Arrange: investor_trend status (lastCollectedDate를 non-null로 설정)
            BackfillStatus status = seedPending("005930", "investor_trend");
            BackfillStatus managedSetup =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            managedSetup.advance(BackfillStatusType.IN_PROGRESS, LocalDate.of(2025, 1, 1), 0, 0);
            backfillStatusRepository.saveAndFlush(managedSetup);
            BackfillStatus populated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();

            // investorTrendService.fetchWindow 호출 시 현재 tx 활성 상태를 캡처
            AtomicBoolean txActiveDuringFetch = new AtomicBoolean(true);
            when(investorTrendService.fetchWindow(any(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                txActiveDuringFetch.set(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return new InvestorTrendFetch(List.of(), null, 0);
                            });

            // Act — fetchWindow 직접 호출 (executeWindow 경유 아님)
            windowExecutor.fetchWindow(populated, domesticStock, session);

            // Assert — fetchWindow 내부에서 활성 tx 없음
            assertThat(txActiveDuringFetch.get())
                    .as("fetchWindow 실행 중 활성 트랜잭션이 없어야 한다 (REQ-TXB-020)")
                    .isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // AC-4.1 수집+상태 동일 트랜잭션 커밋 (executeWindow 경유)
    // -------------------------------------------------------------------------

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
            DomesticDailyOhlcvFetch fetch =
                    new DomesticDailyOhlcvFetch(List.of(), oldest, 100, 100);
            when(domesticOhlcvService.fetchWindow(any(), any(), any(), any())).thenReturn(fetch);
            // 100건 = GROUP_A IN_PROGRESS 유지
            when(domesticOhlcvService.persistWindow(any(), any()))
                    .thenReturn(new BackfillWindowResult(oldest, 100));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(updated.getLastCollectedDate()).isEqualTo(oldest);
            assertThat(updated.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("executeWindow() — GROUP_A 100건 미만 반환 시 COMPLETED로 전이")
        void executeWindow_groupATermination_setsCompleted() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            LocalDate oldest = LocalDate.of(2020, 1, 2);
            DomesticDailyOhlcvFetch fetch = new DomesticDailyOhlcvFetch(List.of(), oldest, 50, 50);
            when(domesticOhlcvService.fetchWindow(any(), any(), any(), any())).thenReturn(fetch);
            // 50 < 100 → COMPLETED
            when(domesticOhlcvService.persistWindow(any(), any()))
                    .thenReturn(new BackfillWindowResult(oldest, 50));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
        }
    }

    // -------------------------------------------------------------------------
    // AC-4.2 트랜잭션 롤백 (executeWindow 경유)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AC-4.2 트랜잭션 롤백")
    class TransactionRollback {

        @Test
        @DisplayName(
                "executeWindow() — advance() 예외 시 수집 데이터·status 변경 모두 롤백된다" + " (REQ-BACKFILL-031)")
        void executeWindow_rollbackOnUpdateProgressFailure_neitherDataNorStatusPersists()
                throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");

            LocalDate oldest = LocalDate.of(2025, 1, 1);
            DomesticDailyOhlcvFetch fetch = new DomesticDailyOhlcvFetch(List.of(), oldest, 50, 50);
            when(domesticOhlcvService.fetchWindow(any(), any(), any(), any())).thenReturn(fetch);
            when(domesticOhlcvService.persistWindow(any(), any()))
                    .thenReturn(new BackfillWindowResult(oldest, 50));

            // advance() 호출 시 RuntimeException 발생 → 트랜잭션 롤백
            BackfillStatus spyStatus = Mockito.spy(status);
            doThrow(new RuntimeException("DB write failure — rollback trigger"))
                    .when(spyStatus)
                    .advance(any(), any(), anyInt(), any());
            when(backfillStatusRepository.findById(status.getId()))
                    .thenReturn(Optional.of(spyStatus));

            // Act & Assert — 예외가 전파됨
            assertThatThrownBy(() -> windowExecutor.executeWindow(status, domesticStock, session))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB write failure");

            // Assert — 롤백: spyStatus는 advance() 실패로 상태가 변경되지 않음
            BackfillStatusType originalStatus = status.getStatus(); // PENDING
            LocalDate originalDate = status.getLastCollectedDate(); // null
            BackfillStatus afterRollback =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(afterRollback.getStatus()).isEqualTo(originalStatus);
            assertThat(afterRollback.getLastCollectedDate()).isEqualTo(originalDate);
        }
    }

    // -------------------------------------------------------------------------
    // AC-6.3 오류 분류
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AC-6.3 오류 분류 — retryable/non-retryable (REQ-BACKFILL-030)")
    class ErrorClassification {

        @Test
        @DisplayName(
                "executeWindowOnError() — retryable=true → status=IN_PROGRESS, last_error 기록"
                        + " (AC-6.3a)")
        void executeWindowOnError_retryableError_keepsInProgress() {
            // Arrange
            BackfillStatus status = seedPending("005930", "daily_ohlcv");
            String errorMsg = "HTTP 500 Internal Server Error";

            // Act
            windowExecutor.executeWindowOnError(status, errorMsg, true);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
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
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.FAILED);
        }
    }

    // -------------------------------------------------------------------------
    // REQ-UPDATEDAT-009: advance() 예외 시 롤백 보장
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("REQ-UPDATEDAT-009 진행 갱신 경로 트랜잭션 롤백 보장")
    class AdvanceRollback {

        @Test
        @DisplayName("advance()가 RuntimeException을 던지면 INSERT 행과 attempt_count 변경 모두 롤백")
        void persistWindow_advanceThrows_rollsBackInsertAndStatus() {
            // Arrange
            BackfillStatus status = seedPending("005930", "investor_trend");
            LocalDate oldest = LocalDate.of(2025, 1, 1);
            InvestorTrendFetch fetch = new InvestorTrendFetch(List.of(), oldest, 30);
            when(investorTrendService.persistWindow(any(), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 30));

            BackfillStatus spyStatus = Mockito.spy(status);
            doThrow(new RuntimeException("advance rollback trigger"))
                    .when(spyStatus)
                    .advance(any(), any(), anyInt(), any());
            when(backfillStatusRepository.findById(status.getId()))
                    .thenReturn(Optional.of(spyStatus));

            // Act & Assert
            assertThatThrownBy(() -> windowExecutor.persistWindow(status, domesticStock, fetch))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("advance rollback trigger");

            // Assert — spyStatus는 advance() 실패로 상태 미변경
            BackfillStatus afterRollback =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(afterRollback.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(afterRollback.getAttemptCount()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // AC-1 resolveAnchor — 초기 anchor 날짜 (REQ-BACKFILL-060)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AC-1 resolveAnchor — 초기 anchor 날짜 (REQ-BACKFILL-060)")
    class ResolveAnchor {

        @Test
        @DisplayName("AC-1.1 last_collected_date=null → anchor = 어제(LocalDate.now().minusDays(1))")
        void resolveAnchor_nullLastCollectedDate_usesYesterday() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "investor_trend");
            LocalDate yesterday = LocalDate.now().minusDays(1);
            when(investorTrendService.fetchWindow(any(), any(), any()))
                    .thenReturn(new InvestorTrendFetch(List.of(), yesterday.minusDays(30), 30));
            when(investorTrendService.persistWindow(any(), any()))
                    .thenReturn(new BackfillWindowResult(yesterday.minusDays(30), 30));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — investorTrendService.fetchWindow이 어제 날짜 anchor로 호출됐는지 확인
            verify(investorTrendService).fetchWindow(eq(yesterday), any(), any());
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
                                    List.of(BackfillStatusType.PENDING), "STOCK")
                            .stream()
                            .filter(
                                    s ->
                                            "005930".equals(s.getTargetCode())
                                                    && "investor_trend".equals(s.getDataTable()))
                            .findFirst()
                            .orElseThrow();

            LocalDate lastCollected = LocalDate.of(2025, 6, 1);
            BackfillStatus managedRaw =
                    backfillStatusRepository.findById(raw.getId()).orElseThrow();
            managedRaw.advance(BackfillStatusType.IN_PROGRESS, lastCollected, 0, 30);
            backfillStatusRepository.saveAndFlush(managedRaw);
            BackfillStatus status = backfillStatusRepository.findById(raw.getId()).orElseThrow();

            when(investorTrendService.fetchWindow(any(), any(), any()))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));
            when(investorTrendService.persistWindow(any(), any()))
                    .thenReturn(BackfillWindowResult.EMPTY);

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — 오늘도, 어제도 아닌 nextAnchor(lastCollected)=lastCollected.minusDays(1) 로 호출
            // (AC-1.3)
            verify(investorTrendService).fetchWindow(eq(lastCollected.minusDays(1)), any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // GROUP_B stale 종료
    // -------------------------------------------------------------------------

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
                                    List.of(BackfillStatusType.PENDING), "STOCK")
                            .stream()
                            .filter(
                                    s ->
                                            "005930".equals(s.getTargetCode())
                                                    && "investor_trend".equals(s.getDataTable()))
                            .findFirst()
                            .orElseThrow();

            // staleCount=2 으로 설정 (직전 2회 무전진)
            BackfillStatus managedRaw =
                    backfillStatusRepository.findById(raw.getId()).orElseThrow();
            managedRaw.advance(BackfillStatusType.IN_PROGRESS, LocalDate.of(2024, 6, 1), 2, 5);
            backfillStatusRepository.saveAndFlush(managedRaw);

            BackfillStatus status = backfillStatusRepository.findById(raw.getId()).orElseThrow();

            // 이번 윈도우도 무전진(0건)
            when(investorTrendService.fetchWindow(any(), any(), any()))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));
            when(investorTrendService.persistWindow(any(), any()))
                    .thenReturn(BackfillWindowResult.EMPTY);

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
        }
    }

    // -------------------------------------------------------------------------
    // SPEC-COLLECTOR-BACKFILL-007 W3+W4 — corporate_events GROUP_A 라우팅
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("BACKFILL-007: corporate_events GROUP_A 라우팅·종료 (REQ-BACKFILL-093~099a)")
    class CorporateEventsRouting {

        @Test
        @DisplayName("AC-4/AC-5: corporate_events fetch는 고정 플로어(1950)로 호출되고 1회 윈도우 후 COMPLETED")
        void corporateEvents_fetchUsesFloor_andCompletesInOneWindow() throws InterruptedException {
            // Arrange — corporate_events status PENDING, 유효 분할 1건 반환
            BackfillStatus status = seedPending("005930", "corporate_events");
            LocalDate oldest = LocalDate.of(2018, 5, 2);
            RevSplitBackfillFetch fetch = new RevSplitBackfillFetch(List.of(), oldest, 1);
            when(revSplitService.fetchWindowForBackfill(any(), any(), any(), any()))
                    .thenReturn(fetch);
            when(revSplitService.persistWindowForBackfill(eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 1));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — from-date=고정 플로어(1950-01-01), GROUP_A rowCount<100 → COMPLETED
            verify(revSplitService)
                    .fetchWindowForBackfill(
                            eq(domesticStock), eq(session), eq(LocalDate.of(1950, 1, 1)), any());
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(updated.getLastCollectedDate()).isEqualTo(oldest);
        }

        @Test
        @DisplayName("AC-5a: rawRowCount=2(degenerate+valid 혼합)여도 100건 미만이라 결정적으로 COMPLETED")
        void corporateEvents_rawRowCountTwo_stillCompletes() throws InterruptedException {
            // Arrange — 원본 행수 2(1 valid + 1 degenerate skip), 종료 입력 rawRowCount=2·저장 rowCount=1
            BackfillStatus status = seedPending("005930", "corporate_events");
            LocalDate oldest = LocalDate.of(2018, 5, 2);
            RevSplitBackfillFetch fetch = new RevSplitBackfillFetch(List.of(), oldest, 2);
            when(revSplitService.fetchWindowForBackfill(any(), any(), any(), any()))
                    .thenReturn(fetch);
            // 3-arg: rowCount(저장)=1, rawRowCount(종료 입력)=2 — 발산해도 종료는 rawRowCount로 결정
            when(revSplitService.persistWindowForBackfill(eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 1, 2));

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert — rawRowCount=2 < 100 → COMPLETED (저장 1과 발산해도 무관, 006 적용 여부 무관)
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
        }

        @Test
        @DisplayName("EC-3: 액면교체 이력 0건 → rowCount=0 → GROUP_A 0건 종료 COMPLETED")
        void corporateEvents_emptyHistory_completes() throws InterruptedException {
            // Arrange
            BackfillStatus status = seedPending("005930", "corporate_events");
            RevSplitBackfillFetch fetch = new RevSplitBackfillFetch(List.of(), null, 0);
            when(revSplitService.fetchWindowForBackfill(any(), any(), any(), any()))
                    .thenReturn(fetch);
            when(revSplitService.persistWindowForBackfill(eq(fetch)))
                    .thenReturn(BackfillWindowResult.EMPTY);

            // Act
            windowExecutor.executeWindow(status, domesticStock, session);

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(status.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
        }
    }
}
