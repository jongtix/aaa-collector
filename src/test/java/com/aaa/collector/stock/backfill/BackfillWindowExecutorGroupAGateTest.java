package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GROUP_A {@code daily_ohlcv} 종료 확인 게이트 단위 테스트 (SPEC-COLLECTOR-BACKFILL-010).
 *
 * <p>AC-1~AC-4(정상 경로: 빈 프로브/1행 이상/API 오류/신뢰 하한 도달), AC-13~AC-15(oldest=null 3분할: 진짜 빈 응답·교차검증
 * 강등·zdiv/전량거부 이상)를 순수 Mockito 단위 테스트로 커버한다. Testcontainers 없이 검증 가능한 로직(프로브 발행 여부·probeOutcome
 * 산정·status 전이·staleCount)만 다루고, verified_at·게이지 영속은 {@link
 * com.aaa.collector.backfill.BackfillStatusRepositoryTest}(Testcontainers)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillWindowExecutor GROUP_A daily_ohlcv 종료 확인 게이트 (SPEC-COLLECTOR-BACKFILL-010)")
class BackfillWindowExecutorGroupAGateTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @Mock private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @Mock private ShortSaleCollectionService shortSaleService;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private CreditBalanceCollectionService creditBalanceService;
    @Mock private RevSplitCollectionService revSplitService;
    @Mock private DividendScheduleCollectionService dividendService;
    @Mock private OverseasSplitCollectionService overseasSplitService;
    @Mock private BackfillTerminationPolicy terminationPolicy;
    @Mock private BackfillWindowAdvancer windowAdvancer;
    @Mock private BackfillMetrics backfillMetrics;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private LeaseSession session;

    private BackfillWindowExecutor executor;
    private BackfillProperties properties;

    private static final LocalDate FLOOR_FROM = LocalDate.of(1950, 1, 1);

    @BeforeEach
    void setUp() {
        properties = new BackfillProperties(); // staleWindowThreshold=3 기본
        executor =
                new BackfillWindowExecutor(
                        backfillStatusRepository,
                        domesticOhlcvService,
                        overseasOhlcvService,
                        shortSaleService,
                        investorTrendService,
                        creditBalanceService,
                        revSplitService,
                        dividendService,
                        overseasSplitService,
                        terminationPolicy,
                        windowAdvancer,
                        backfillMetrics,
                        transactionTemplate,
                        properties);
        // resolveAnchor(status)가 non-null lastCollectedDate에 windowAdvancer.nextAnchor를 위임한다
        // (REQ-BACKFILL-015) — 이 테스트는 anchor 산정 로직 자체(BackfillWindowAdvancerTest 담당)가 아니라
        // 게이트 판정을 검증하므로 identity(무변경) stub으로 고정한다. lenient — 모든 테스트가 이 경로를 타지 않음.
        org.mockito.Mockito.lenient()
                .when(windowAdvancer.nextAnchor(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Stock usStock(String symbol, LocalDate listedDate) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo(symbol)
                .market(Market.NASDAQ)
                .assetType(AssetType.STOCK)
                .active(true)
                .listedDate(listedDate)
                .build();
    }

    private static BackfillStatus dailyOhlcvStatus(String symbol, LocalDate lastCollectedDate) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable("daily_ohlcv")
                .status(
                        lastCollectedDate == null
                                ? BackfillStatusType.PENDING
                                : BackfillStatusType.IN_PROGRESS)
                .lastCollectedDate(lastCollectedDate)
                .build();
    }

    /** persistWindow용 관리 엔티티 findById stub — 대상 status 자기 자신을 반환. */
    private void stubManaged(BackfillStatus status) {
        // BackfillStatus.id는 @GeneratedValue라 테스트에서 직접 세팅 불가 — findById(null) stub 대신
        // 동일 인스턴스를 그대로 반환하도록 lenient 매칭한다.
        when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));
    }

    @Nested
    @DisplayName("AC-1/AC-2/AC-3 — 정상 경로(oldest 비-null) 확인 프로브")
    class NormalPathProbe {

        @Test
        @DisplayName("AC-1: rawRowCount<100·oldest>floor·프로브 빈 응답 → COMPLETED+verified, 프로브 1회")
        void emptyProbe_completesAndVerifies() throws InterruptedException {
            // Arrange — 미국 종목, listed_date=벽 이전(신뢰 가능 벽), oldest가 벽보다 위
            Stock aapl = usStock("AAPL", null);
            LocalDate oldest = LocalDate.of(2007, 9, 1); // > 2007-08-20 벽
            BackfillStatus status = dailyOhlcvStatus("AAPL", oldest.plusDays(30));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), oldest, 40, 40);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.confirmExhaustionProbe(eq(oldest), eq(aapl), eq(session)))
                    .thenReturn(false); // 빈 정상 응답
            when(overseasOhlcvService.persistWindow(eq(aapl), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 40, 40));

            // Act
            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.CONFIRMED_EXHAUSTED);
            executor.persistWindow(status, aapl, envelope);

            // Assert
            verify(overseasOhlcvService, times(1))
                    .confirmExhaustionProbe(eq(oldest), eq(aapl), eq(session));
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(status.getVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName(
                "AC-2: rawRowCount<100·oldest>floor·프로브 1행 이상 → IN_PROGRESS 유지·진행점=비-프로브 oldest·카운터++")
        void nonEmptyProbe_blocksCompletion() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            LocalDate oldest = LocalDate.of(2007, 9, 1);
            BackfillStatus status = dailyOhlcvStatus("AAPL", oldest.plusDays(30));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), oldest, 40, 40);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.confirmExhaustionProbe(eq(oldest), eq(aapl), eq(session)))
                    .thenReturn(true); // 데이터 잔존
            when(overseasOhlcvService.persistWindow(eq(aapl), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 40, 40));

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.MORE_DATA_EXISTS);
            executor.persistWindow(status, aapl, envelope);

            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(status.getLastCollectedDate()).isEqualTo(oldest);
            assertThat(status.getVerifiedAt()).isNull();
            verify(backfillMetrics, times(1)).recordEarlyCompletionSuspect();
        }

        @Test
        @DisplayName("AC-3a: 프로브 API 오류(재시도 가능) → IN_PROGRESS 유지·무전진, verified_at 미설정")
        void probeError_retryable_staysInProgress() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            LocalDate oldest = LocalDate.of(2007, 9, 1);
            LocalDate priorDate = oldest.plusDays(30);
            BackfillStatus status = dailyOhlcvStatus("AAPL", priorDate);
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), oldest, 40, 40);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.confirmExhaustionProbe(eq(oldest), eq(aapl), eq(session)))
                    .thenThrow(new RuntimeException("HTTP 500"));

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.DEFERRED);
            executor.persistWindow(status, aapl, envelope);

            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(status.getLastCollectedDate()).isEqualTo(priorDate); // 무전진
            assertThat(status.getVerifiedAt()).isNull();
        }

        @Test
        @DisplayName("AC-4a: 신뢰 하한(벽 자체) 도달 → 프로브 0회, 즉시 COMPLETED+verified")
        void floorAlreadyMet_trustedWall_skipsProbe() throws InterruptedException {
            // listed_date NULL → floor = 2007-08-20 벽 자체(신뢰 가능)
            Stock aapl = usStock("AAPL", null);
            LocalDate oldest = LocalDate.of(2007, 8, 20); // == 벽
            BackfillStatus status = dailyOhlcvStatus("AAPL", oldest.plusDays(10));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), oldest, 10, 10);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.persistWindow(eq(aapl), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 10, 10));

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.FLOOR_ALREADY_MET);
            executor.persistWindow(status, aapl, envelope);

            verify(overseasOhlcvService, never()).confirmExhaustionProbe(any(), any(), any());
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(status.getVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("AC-4b: 신뢰 불가 floor(listed_date>벽)에서 oldest<=floor → 프로브 1회 발행(생략 안 함)")
        void floorAlreadyMet_untrustedListedDate_stillProbes() throws InterruptedException {
            // listed_date > 벽(거래소 이전 종목류) — floor=listed_date, 신뢰 불가
            LocalDate listedDate = LocalDate.of(2024, 11, 26);
            Stock plt = usStock("PLTR-STUB", listedDate);
            LocalDate oldest = listedDate.minusDays(5); // oldest <= floor(listedDate)
            BackfillStatus status = dailyOhlcvStatus("PLTR-STUB", oldest.plusDays(10));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), oldest, 10, 10);
            when(overseasOhlcvService.fetchWindow(any(), eq(plt), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.confirmExhaustionProbe(eq(oldest), eq(plt), eq(session)))
                    .thenReturn(true); // floor 아래 진짜 데이터 잔존
            when(overseasOhlcvService.persistWindow(eq(plt), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 10, 10));

            FetchEnvelope envelope = executor.fetchWindow(status, plt, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.MORE_DATA_EXISTS);
            executor.persistWindow(status, plt, envelope);

            verify(overseasOhlcvService, times(1))
                    .confirmExhaustionProbe(eq(oldest), eq(plt), eq(session));
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(status.getVerifiedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("EC-2/EC-8: 잠정 종료 미성립·게이트 비대상")
    class GateNotApplicable {

        @Test
        @DisplayName("rawRowCount>=100 → NOT_APPLICABLE, 프로브 미발행")
        void hundredRows_notApplicable() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            BackfillStatus status = dailyOhlcvStatus("AAPL", null);
            OverseasDailyOhlcvFetch fetch =
                    new OverseasDailyOhlcvFetch(List.of(), LocalDate.of(2020, 1, 1), 100, 100);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);

            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.NOT_APPLICABLE);
            verifyNoInteractions(backfillMetrics);
        }
    }

    @Nested
    @DisplayName("AC-13/AC-15: oldest=null·rawRowCount==0 — 케이스① 진짜 빈 응답 3분할")
    class EmptyOldestNull {

        @Test
        @DisplayName(
                "AC-13(a): anchor<=floor(신뢰 가능) → EMPTY_EXHAUSTED, 프로브 0회, 즉시 COMPLETED+verified")
        void trueEmpty_crossCheckPasses_completesImmediately() throws InterruptedException {
            Stock aapl = usStock("AAPL", null); // floor=벽(2007-08-20)
            LocalDate anchor = LocalDate.of(2007, 8, 19); // anchor <= floor
            BackfillStatus status = dailyOhlcvStatus("AAPL", anchor);
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);
            when(overseasOhlcvService.persistWindow(eq(aapl), eq(fetch)))
                    .thenReturn(BackfillWindowResult.EMPTY);

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.EMPTY_EXHAUSTED);
            executor.persistWindow(status, aapl, envelope);

            verify(overseasOhlcvService, never()).confirmExhaustionProbe(any(), any(), any());
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(status.getVerifiedAt()).isNotNull();
            assertThat(status.getStaleCount()).isZero();
        }

        @Test
        @DisplayName(
                "AC-15: anchor>floor(미국 상시) → EMPTY_ANOMALY 강등, COMPLETED 전이 금지, staleCount 누적")
        void trueEmpty_crossCheckFails_demotesToAnomaly() throws InterruptedException {
            Stock aapl = usStock("AAPL", null); // floor=벽
            LocalDate anchor = LocalDate.of(2010, 1, 1); // anchor > floor(벽 위)
            BackfillStatus status = dailyOhlcvStatus("AAPL", anchor);
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);

            FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.EMPTY_ANOMALY);
            executor.persistWindow(status, aapl, envelope);

            verify(overseasOhlcvService, never()).confirmExhaustionProbe(any(), any(), any());
            assertThat(status.getStatus()).isNotEqualTo(BackfillStatusType.COMPLETED);
            assertThat(status.getVerifiedAt()).isNull();
            assertThat(status.getStaleCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-14: oldest=null·rawRowCount>0 — 케이스②③ 이상, bounded FAILED 정확히 N 사이클")
    class AnomalyBoundedFailed {

        @Test
        @DisplayName("사이클 1~2(N-1): IN_PROGRESS 유지, staleCount 누적, anomaly-FAILED 카운터 미증가")
        void beforeThreshold_staysInProgress() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            BackfillStatus status = dailyOhlcvStatus("AAPL", LocalDate.of(2010, 1, 1));
            stubManaged(status);
            // 케이스② zdiv 가드 — rawRowCount>0, oldest=null
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 5);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);

            // 사이클 1
            FetchEnvelope e1 = executor.fetchWindow(status, aapl, session);
            assertThat(e1.probeOutcome()).isEqualTo(ProbeOutcome.EMPTY_ANOMALY);
            executor.persistWindow(status, aapl, e1);
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(status.getStaleCount()).isEqualTo(1);

            // 사이클 2
            FetchEnvelope e2 = executor.fetchWindow(status, aapl, session);
            executor.persistWindow(status, aapl, e2);
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(status.getStaleCount()).isEqualTo(2);

            verify(backfillMetrics, never()).recordAnomalyFailed();
        }

        @Test
        @DisplayName("사이클 N(3): terminal FAILED + last_error + anomaly-FAILED 카운터 정확히 1회")
        void atThreshold_terminalFailed() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            BackfillStatus status = dailyOhlcvStatus("AAPL", LocalDate.of(2010, 1, 1));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 5);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);

            for (int i = 0; i < 3; i++) {
                FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
                executor.persistWindow(status, aapl, envelope);
            }

            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.FAILED);
            assertThat(status.getLastError()).isNotBlank();
            assertThat(status.getVerifiedAt()).isNull();
            verify(backfillMetrics, times(1)).recordAnomalyFailed();
        }

        @Test
        @DisplayName("[R5-CR-01] FAILED 전이 후 추가 사이클은 멱등 no-op — 카운터 재발화 없음, fetch도 KIS 미호출")
        void afterFailed_additionalCyclesAreNoOp() throws InterruptedException {
            Stock aapl = usStock("AAPL", null);
            BackfillStatus status = dailyOhlcvStatus("AAPL", LocalDate.of(2010, 1, 1));
            stubManaged(status);
            OverseasDailyOhlcvFetch fetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 5);
            when(overseasOhlcvService.fetchWindow(any(), eq(aapl), eq(session))).thenReturn(fetch);

            for (int i = 0; i < 3; i++) {
                FetchEnvelope envelope = executor.fetchWindow(status, aapl, session);
                executor.persistWindow(status, aapl, envelope);
            }
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.FAILED);

            // 4번째(같은 run 잔여 윈도우 모사) — fetchWindow 조기 단락(FAILED 종목 KIS 미호출), persistWindow no-op
            FetchEnvelope envelope4 = executor.fetchWindow(status, aapl, session);
            assertThat(envelope4.probeOutcome()).isEqualTo(ProbeOutcome.NOT_APPLICABLE);
            executor.persistWindow(status, aapl, envelope4);

            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.FAILED);
            verify(backfillMetrics, times(1)).recordAnomalyFailed(); // 재발화 없음(status당 정확히 1회)
            verify(overseasOhlcvService, times(3)) // 3사이클만 실제 호출, 4번째는 조기 단락
                    .fetchWindow(any(), eq(aapl), eq(session));
        }
    }

    @Nested
    @DisplayName("EC-8: 국내 대칭 — confirmExhaustionProbe(from, below, stock, session) 시그니처")
    class DomesticSymmetry {

        @Test
        @DisplayName("국내 GROUP_A 빈 프로브 → COMPLETED+verified, from=고정 플로어로 프로브 발행")
        void domesticEmptyProbe_completes() throws InterruptedException {
            Stock samsung =
                    Stock.builder()
                            .symbol("005930")
                            .nameKo("삼성전자")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .active(true)
                            .build();
            LocalDate oldest = LocalDate.of(1990, 3, 5);
            BackfillStatus status = dailyOhlcvStatus("005930", oldest.plusDays(20));
            stubManaged(status);
            when(windowAdvancer.groupAFromDate()).thenReturn(FLOOR_FROM);
            DomesticDailyOhlcvFetch fetch = new DomesticDailyOhlcvFetch(List.of(), oldest, 47, 47);
            when(domesticOhlcvService.fetchWindow(eq(FLOOR_FROM), any(), eq(samsung), eq(session)))
                    .thenReturn(fetch);
            when(domesticOhlcvService.confirmExhaustionProbe(
                            eq(FLOOR_FROM), eq(oldest), eq(samsung), eq(session)))
                    .thenReturn(false);
            when(domesticOhlcvService.persistWindow(eq(samsung), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(oldest, 47, 47));

            FetchEnvelope envelope = executor.fetchWindow(status, samsung, session);
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.CONFIRMED_EXHAUSTED);
            executor.persistWindow(status, samsung, envelope);

            verify(domesticOhlcvService, times(1))
                    .confirmExhaustionProbe(eq(FLOOR_FROM), eq(oldest), eq(samsung), eq(session));
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(status.getVerifiedAt()).isNotNull();
        }
    }
}
