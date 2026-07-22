package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.TerminationDecision;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.DividendBackfillFetch;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitBackfillCapSaturatedException;
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitBackfillFetch;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.stock.supply.ShortSaleFetch;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BackfillWindowExecutor} corporate_events 시장별 소스 분기 단위 테스트
 * (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063, AC-13).
 *
 * <p>미국 종목은 {@link OverseasSplitCollectionService}(CTRGT011R), 국내 종목은 {@link
 * RevSplitCollectionService} (HHKDB669105C0)로 각각 라우팅되고 서로 침범하지 않음을 검증한다(fetch·persist 양쪽).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillWindowExecutor corporate_events 시장별 소스 분기 단위 테스트")
class BackfillWindowExecutorTest {

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

    private final LocalDate floor = LocalDate.of(1950, 1, 1);

    @BeforeEach
    void setUp() {
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
                        new BackfillProperties());
    }

    private Stock stock(String symbol, Market market) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo(symbol)
                .market(market)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private BackfillStatus corporateEventsStatus(String symbol) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable("corporate_events")
                .status(BackfillStatusType.PENDING)
                .build();
    }

    @Nested
    @DisplayName("fetchWindow — 시장별 소스 분기 (AC-13)")
    class FetchDispatch {

        @Test
        @DisplayName("미국 종목 → OverseasSplitCollectionService(CTRGT011R), RevSplit 미호출")
        void usStock_routesToOverseasSplit() throws InterruptedException {
            when(windowAdvancer.groupAFromDate()).thenReturn(floor);
            Stock aapl = stock("AAPL", Market.NASDAQ);
            when(overseasSplitService.fetchWindowForBackfill(
                            eq(aapl), eq(session), eq(floor), any()))
                    .thenReturn(new OverseasSplitBackfillFetch(List.of(), null, 0));

            executor.fetchWindow(corporateEventsStatus("AAPL"), aapl, session);

            verify(overseasSplitService)
                    .fetchWindowForBackfill(eq(aapl), eq(session), eq(floor), any());
            verify(revSplitService, never()).fetchWindowForBackfill(any(), any(), any(), any());
        }

        @Test
        @DisplayName("국내 종목 → RevSplitCollectionService(HHKDB669105C0), OverseasSplit 미호출 (비회귀)")
        void domesticStock_routesToRevSplit() throws InterruptedException {
            when(windowAdvancer.groupAFromDate()).thenReturn(floor);
            Stock samsung = stock("005930", Market.KOSPI);
            when(revSplitService.fetchWindowForBackfill(eq(samsung), eq(session), eq(floor), any()))
                    .thenReturn(new RevSplitBackfillFetch(List.of(), null, 0));

            executor.fetchWindow(corporateEventsStatus("005930"), samsung, session);

            verify(revSplitService)
                    .fetchWindowForBackfill(eq(samsung), eq(session), eq(floor), any());
            verify(overseasSplitService, never())
                    .fetchWindowForBackfill(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName(
            "fetchWindow — 배당 T_DT=anchor (SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-011, AC-5)")
    class DividendAnchorFetch {

        private BackfillStatus dividendStatus(String symbol, LocalDate lastCollectedDate) {
            return BackfillStatus.builder()
                    .targetType("STOCK")
                    .targetCode(symbol)
                    .dataTable("corporate_events_dividend")
                    .status(BackfillStatusType.IN_PROGRESS)
                    .lastCollectedDate(lastCollectedDate)
                    .build();
        }

        @Test
        @DisplayName("AC-5: T_DT=anchor(윈도우 진행점)로 조회 — today 고정 아님")
        void dividendFetch_usesAnchorAsUpperBound() throws InterruptedException {
            when(windowAdvancer.groupAFromDate()).thenReturn(floor);
            LocalDate anchor = LocalDate.of(2025, 1, 1);
            when(windowAdvancer.nextAnchor(any())).thenReturn(anchor);
            Stock samsung = stock("005930", Market.KOSPI);
            when(dividendService.fetchWindowForBackfill(
                            eq(samsung), eq(session), eq(floor), eq(anchor)))
                    .thenReturn(new DividendBackfillFetch(List.of(), null, 0));

            executor.fetchWindow(
                    dividendStatus("005930", LocalDate.of(2025, 3, 1)), samsung, session);

            verify(dividendService)
                    .fetchWindowForBackfill(eq(samsung), eq(session), eq(floor), eq(anchor));
        }
    }

    @Nested
    @DisplayName("persistWindow — fetchDto 타입별 소스 분기 (AC-13)")
    class PersistDispatch {

        private BackfillStatus mockedStatus(String symbol) {
            BackfillStatus status = Mockito.mock(BackfillStatus.class);
            when(status.getId()).thenReturn(1L);
            when(status.getDataTable()).thenReturn("corporate_events");
            Mockito.lenient().when(status.getTargetCode()).thenReturn(symbol);
            Mockito.lenient().when(status.getLastCollectedDate()).thenReturn(null);
            Mockito.lenient().when(status.getLastRowCount()).thenReturn(null);
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(status));
            return status;
        }

        @Test
        @DisplayName("OverseasSplitBackfillFetch → overseasSplitService.persistWindowForBackfill")
        void overseasFetchDto_routesToOverseasPersist() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            BackfillStatus status = mockedStatus("AAPL");
            LocalDate oldest = LocalDate.of(2020, 8, 31);
            OverseasSplitBackfillFetch fetch = new OverseasSplitBackfillFetch(List.of(), oldest, 1);
            when(overseasSplitService.persistWindowForBackfill(fetch))
                    .thenReturn(new BackfillWindowResult(oldest, 1, 1));
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, aapl, FetchEnvelope.notApplicable(fetch));

            verify(overseasSplitService).persistWindowForBackfill(fetch);
            verify(revSplitService, never()).persistWindowForBackfill(any());
        }

        @Test
        @DisplayName("RevSplitBackfillFetch → revSplitService.persistWindowForBackfill (비회귀)")
        void domesticFetchDto_routesToRevSplitPersist() {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = mockedStatus("005930");
            LocalDate oldest = LocalDate.of(2018, 5, 2);
            RevSplitBackfillFetch fetch = new RevSplitBackfillFetch(List.of(), oldest, 1);
            when(revSplitService.persistWindowForBackfill(fetch))
                    .thenReturn(new BackfillWindowResult(oldest, 1, 1));
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, samsung, FetchEnvelope.notApplicable(fetch));

            verify(revSplitService).persistWindowForBackfill(fetch);
            verify(overseasSplitService, never()).persistWindowForBackfill(any());
        }
    }

    @Nested
    @DisplayName("isRetryable — 캡 포화 예외 비재시도 분류 (REQ-GC-014, AC-7)")
    class IsRetryableCapSaturated {

        @Test
        @DisplayName("REQ-GC-014: RevSplitBackfillCapSaturatedException → 비재시도(false)")
        void capSaturatedException_notRetryable() {
            boolean retryable =
                    executor.isRetryable(
                            new RevSplitBackfillCapSaturatedException("cap saturated"));

            assertThat(retryable).isFalse();
        }

        @Test
        @DisplayName("회귀: 일반 RuntimeException은 여전히 재시도 가능(true)")
        void otherRuntimeException_stillRetryable() {
            boolean retryable = executor.isRetryable(new RuntimeException("transient"));

            assertThat(retryable).isTrue();
        }
    }

    @Nested
    @DisplayName(
            "persistLegacy markVerified 게이트 (SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-007/010/021,"
                    + " AC-4/AC-8)")
    class MarkVerifiedGate {

        private BackfillStatus mockedStatus(String symbol, String dataTable) {
            BackfillStatus status = Mockito.mock(BackfillStatus.class);
            when(status.getId()).thenReturn(1L);
            when(status.getDataTable()).thenReturn(dataTable);
            Mockito.lenient().when(status.getTargetCode()).thenReturn(symbol);
            Mockito.lenient().when(status.getLastCollectedDate()).thenReturn(null);
            Mockito.lenient().when(status.getLastRowCount()).thenReturn(null);
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(status));
            return status;
        }

        @Test
        @DisplayName("AC-4: GROUP_C(corporate_events) 완료 시 markVerified 호출")
        void groupC_completed_marksVerified() {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = mockedStatus("005930", "corporate_events");
            LocalDate oldest = LocalDate.of(2018, 5, 2);
            RevSplitBackfillFetch fetch = new RevSplitBackfillFetch(List.of(), oldest, 1);
            when(revSplitService.persistWindowForBackfill(fetch))
                    .thenReturn(new BackfillWindowResult(oldest, 1, 1));
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, samsung, FetchEnvelope.notApplicable(fetch));

            verify(status).markVerified(any());
        }

        @Test
        @DisplayName("AC-8: GROUP_A corporate_events_dividend 절대 플로어 완료 시 markVerified 호출")
        void dividendGroupA_completed_marksVerified() {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = mockedStatus("005930", "corporate_events_dividend");
            LocalDate oldest = LocalDate.of(2015, 3, 31);
            DividendBackfillFetch fetch = new DividendBackfillFetch(List.of(), oldest, 1);
            when(dividendService.persistWindowForBackfill(fetch))
                    .thenReturn(new BackfillWindowResult(oldest, 1, 1));
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, samsung, FetchEnvelope.notApplicable(fetch));

            verify(status).markVerified(any());
        }

        @Test
        @DisplayName(
                "AC-8 음성: GROUP_A corporate_events_dividend IN_PROGRESS(100행 캡)면 markVerified 미호출")
        void dividendGroupA_inProgress_doesNotMarkVerified() {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = mockedStatus("005930", "corporate_events_dividend");
            LocalDate oldest = LocalDate.of(2015, 3, 31);
            DividendBackfillFetch fetch = new DividendBackfillFetch(List.of(), oldest, 100);
            when(dividendService.persistWindowForBackfill(fetch))
                    .thenReturn(new BackfillWindowResult(oldest, 100, 100));
            when(terminationPolicy.decide(any())).thenReturn(TerminationDecision.inProgress(0));

            executor.persistWindow(status, samsung, FetchEnvelope.notApplicable(fetch));

            verify(status, never()).markVerified(any());
        }

        @Test
        @DisplayName("회귀: GROUP_B(short_sale_domestic) 완료여도 markVerified 미호출(대상 아님)")
        void groupB_completed_doesNotMarkVerified() {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = mockedStatus("005930", "short_sale_domestic");
            Mockito.lenient().when(status.getStaleCount()).thenReturn(0);
            ShortSaleFetch fetch = Mockito.mock(ShortSaleFetch.class);
            when(shortSaleService.persistWindow(eq(status), eq(samsung), eq(fetch)))
                    .thenReturn(BackfillWindowResult.EMPTY);
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, samsung, FetchEnvelope.notApplicable(fetch));

            verify(status, never()).markVerified(any());
        }
    }

    @Nested
    @DisplayName(
            "resolveAnchor — GROUP_B 초기 anchor delisted_at floor (SPEC-COLLECTOR-BACKFILL-013"
                    + " AC-1/AC-2/AC-3)")
    class GroupBInitialAnchor {

        private static final ZoneId KST = ZoneId.of("Asia/Seoul");

        private BackfillStatus groupBStatus(String symbol, String dataTable) {
            return BackfillStatus.builder()
                    .targetType("STOCK")
                    .targetCode(symbol)
                    .dataTable(dataTable)
                    .status(BackfillStatusType.PENDING)
                    .build();
        }

        @Test
        @DisplayName("AC-1: 상폐 확정 종목(delistedAt!=null) → anchor=delistedAt")
        void delistedStock_anchorIsDelistedAt() throws InterruptedException {
            LocalDate delistedAt = LocalDate.of(2025, 12, 1);
            Stock delisted =
                    Stock.builder()
                            .symbol("010620")
                            .nameKo("HD현대미포")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .active(false)
                            .delistedAt(delistedAt)
                            .build();
            ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
            when(investorTrendService.fetchWindow(
                            anchorCaptor.capture(), eq(delisted), eq(session)))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));

            executor.fetchWindow(groupBStatus("010620", "investor_trend"), delisted, session);

            assertThat(anchorCaptor.getValue()).isEqualTo(delistedAt);
        }

        @Test
        @DisplayName("AC-2: 활성 종목(delistedAt==null) → anchor=어제(KST) — 비회귀")
        void activeStock_anchorIsYesterdayKst() throws InterruptedException {
            Stock active = stock("005930", Market.KOSPI);
            ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
            when(investorTrendService.fetchWindow(anchorCaptor.capture(), eq(active), eq(session)))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));

            executor.fetchWindow(groupBStatus("005930", "investor_trend"), active, session);

            assertThat(anchorCaptor.getValue()).isEqualTo(LocalDate.now(KST).minusDays(1));
        }

        @Test
        @DisplayName("AC-3: GROUP_A(daily_ohlcv)는 delistedAt 분기 미적용 — 무변경(어제)")
        void groupA_delistedAt_notApplied() throws InterruptedException {
            LocalDate delistedAt = LocalDate.of(2025, 12, 1);
            Stock delisted =
                    Stock.builder()
                            .symbol("010620")
                            .nameKo("HD현대미포")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .active(false)
                            .delistedAt(delistedAt)
                            .build();
            when(windowAdvancer.groupAFromDate()).thenReturn(floor);
            ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
            when(domesticOhlcvService.fetchWindow(
                            eq(floor), anchorCaptor.capture(), eq(delisted), eq(session)))
                    .thenReturn(new DomesticDailyOhlcvFetch(List.of(), null, 0, 0));

            executor.fetchWindow(groupBStatus("010620", "daily_ohlcv"), delisted, session);

            // GROUP_A 분기는 delistedAt을 반영하지 않고 "어제"를 그대로 사용한다(REQ-BACKFILL-173).
            assertThat(anchorCaptor.getValue()).isEqualTo(LocalDate.now().minusDays(1));
        }
    }

    @Nested
    @DisplayName(
            "resolveAnchor — GROUP_B probe 재개 (SPEC-COLLECTOR-BACKFILL-013 REQ-BACKFILL-164, v0.4.0"
                    + " 정정)")
    class GroupBProbeResumeAnchor {

        @Test
        @DisplayName(
                "last_collected_date!=null && last_row_count==null → probe 커서 그대로 재개(nextAnchor 재적용"
                        + " 안 함)")
        void probeCursorResumed_withoutReapplyingNextAnchor() throws InterruptedException {
            LocalDate probeCursor = LocalDate.of(1995, 6, 1);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("STOCK")
                            .targetCode("010620")
                            .dataTable("investor_trend")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .lastCollectedDate(probeCursor)
                            .lastRowCount(null)
                            .build();
            Stock stock = stock("010620", Market.KOSPI);
            ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
            when(investorTrendService.fetchWindow(anchorCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));

            executor.fetchWindow(status, stock, session);

            assertThat(anchorCaptor.getValue()).isEqualTo(probeCursor);
            verify(windowAdvancer, never()).nextAnchor(any());
        }

        @Test
        @DisplayName("last_row_count!=null(실데이터 확보) → 기존 nextAnchor(oldest−1일) walk-back으로 분기(무회귀)")
        void realDataFound_usesExistingNextAnchor() throws InterruptedException {
            LocalDate oldest = LocalDate.of(2020, 3, 1);
            LocalDate nextAnchor = LocalDate.of(2020, 2, 29);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("STOCK")
                            .targetCode("010620")
                            .dataTable("investor_trend")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .lastCollectedDate(oldest)
                            .lastRowCount(30)
                            .build();
            Stock stock = stock("010620", Market.KOSPI);
            when(windowAdvancer.nextAnchor(oldest)).thenReturn(nextAnchor);
            ArgumentCaptor<LocalDate> anchorCaptor = ArgumentCaptor.forClass(LocalDate.class);
            when(investorTrendService.fetchWindow(anchorCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(new InvestorTrendFetch(List.of(), null, 0));

            executor.fetchWindow(status, stock, session);

            assertThat(anchorCaptor.getValue()).isEqualTo(nextAnchor);
        }
    }

    @Nested
    @DisplayName(
            "persistLegacy — GROUP_B probe-continue anchor 전진 (SPEC-COLLECTOR-BACKFILL-013 REQ-BACKFILL-164)")
    class GroupBProbeContinuePersist {

        private BackfillStatus mockedGroupBStatus(
                String symbol, String dataTable, LocalDate lastCollectedDate) {
            BackfillStatus status = Mockito.mock(BackfillStatus.class);
            when(status.getId()).thenReturn(1L);
            when(status.getDataTable()).thenReturn(dataTable);
            Mockito.lenient().when(status.getTargetCode()).thenReturn(symbol);
            Mockito.lenient().when(status.getLastCollectedDate()).thenReturn(lastCollectedDate);
            Mockito.lenient().when(status.getLastRowCount()).thenReturn(null);
            Mockito.lenient().when(status.getStaleCount()).thenReturn(0);
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(status));
            return status;
        }

        @Test
        @DisplayName(
                "AC-4: probe-continue 결정 시 anchor를 stride 전진하고 last_collected_date에 겸용 persist,"
                        + " IN_PROGRESS 유지")
        void probeContinue_advancesAnchorAndPersistsToLastCollectedDate() {
            Stock stock = stock("010620", Market.KOSPI);
            LocalDate probedAnchor = LocalDate.of(2020, 1, 1);
            BackfillStatus status =
                    mockedGroupBStatus("010620", "short_sale_domestic", probedAnchor);
            ShortSaleFetch fetch = Mockito.mock(ShortSaleFetch.class);
            when(shortSaleService.persistWindow(eq(status), eq(stock), eq(fetch)))
                    .thenReturn(BackfillWindowResult.EMPTY);
            when(terminationPolicy.decide(any())).thenReturn(TerminationDecision.continueProbing());
            LocalDate advanced = LocalDate.of(2019, 10, 3);
            when(windowAdvancer.nextGroupBProbeAnchor(
                            eq("short_sale_domestic"), eq(probedAnchor), any()))
                    .thenReturn(advanced);

            executor.persistWindow(status, stock, FetchEnvelope.notApplicable(fetch, probedAnchor));

            verify(status).advance(BackfillStatusType.IN_PROGRESS, advanced, 0, null);
        }

        @Test
        @DisplayName("음성: floor 도달로 COMPLETED 결정이면 probe 전진 로직 미호출(무한 걸음 방지)")
        void floorReached_completed_doesNotAdvanceProbeAnchor() {
            Stock stock = stock("010620", Market.KOSPI);
            LocalDate probedAnchor = LocalDate.of(1985, 1, 4);
            BackfillStatus status =
                    mockedGroupBStatus("010620", "short_sale_domestic", probedAnchor);
            ShortSaleFetch fetch = Mockito.mock(ShortSaleFetch.class);
            when(shortSaleService.persistWindow(eq(status), eq(stock), eq(fetch)))
                    .thenReturn(BackfillWindowResult.EMPTY);
            when(terminationPolicy.decide(any()))
                    .thenReturn(TerminationDecision.completed(0, false));

            executor.persistWindow(status, stock, FetchEnvelope.notApplicable(fetch, probedAnchor));

            verify(windowAdvancer, never()).nextGroupBProbeAnchor(any(), any(), any());
            verify(status).advance(eq(BackfillStatusType.COMPLETED), any(), eq(0), any());
        }
    }
}
