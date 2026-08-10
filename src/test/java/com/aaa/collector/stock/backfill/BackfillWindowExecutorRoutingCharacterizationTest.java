package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasDividendBackfillFetch;
import com.aaa.collector.stock.rights.OverseasDividendBackfillService;
import com.aaa.collector.stock.rights.OverseasSplitBackfillFetch;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceFetch;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BackfillWindowExecutor}의 리팩토링 전(pre-router) 라우팅 행동 특성화 테스트
 * (SPEC-COLLECTOR-BACKFILL-ROUTER-001 M1, REQ-ROUTER-050).
 *
 * <p>기존 7종 {@code data_table}(daily_ohlcv 국내/해외·short_sale_domestic·investor_trend·credit_balance·
 * corporate_events 국내/해외·corporate_events_dividend·corporate_events_dividend_overseas)에 대해 {@code
 * routeFetch}/{@code routePersist}가 위임하는 협업 서비스와 정확한 인자를 고정한다. M2~M5에서 {@code BackfillRouteHandler}
 * Strategy/Registry로 이관한 뒤에도 이 테스트가 그대로 통과해야 한다(REQ-ROUTER-051) — 회귀 감지의 1차 방어선이다.
 */
@SuppressWarnings(
        "PMD.TooManyFields") // 테스트 클래스 — 9개 협업 서비스 mock 필드 불가피(routeFetch/routePersist 전체 라우팅 표면)
@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillWindowExecutor 라우팅 행동 특성화 테스트 (M1, REQ-ROUTER-050)")
class BackfillWindowExecutorRoutingCharacterizationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate LAST_COLLECTED = LocalDate.of(2020, 1, 1);
    private static final Integer LAST_ROW_COUNT = 50;
    private static final LocalDate ANCHOR = LocalDate.of(2020, 6, 1);
    private static final LocalDate FLOOR = LocalDate.of(1950, 1, 1);

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @Mock private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @Mock private ShortSaleCollectionService shortSaleService;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private CreditBalanceCollectionService creditBalanceService;
    @Mock private RevSplitCollectionService revSplitService;
    @Mock private DividendScheduleCollectionService dividendService;
    @Mock private OverseasSplitCollectionService overseasSplitService;
    @Mock private OverseasDividendBackfillService overseasDividendBackfillService;
    @Mock private BackfillTerminationPolicy terminationPolicy;
    @Mock private BackfillWindowAdvancer windowAdvancer;
    @Mock private BackfillMetrics backfillMetrics;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private LeaseSession session;

    private BackfillWindowExecutor executor;

    @BeforeEach
    void setUp() {
        // SPEC-COLLECTOR-BACKFILL-ROUTER-001 M5: 생성자가 List<BackfillRouteHandler>를 받도록 변경됐다.
        // 이 테스트가 원래 검증하던 대상(각 협업 서비스가 올바른 인자로 호출되는가)을 그대로 유지하기 위해,
        // 핸들러 mock이 아닌 "실제 핸들러 구현체"를 이 서비스 mock들로 직접 생성해 주입한다 — 핸들러 본체는
        // 이관 전 switch case 본체를 그대로 옮긴 것이므로(REQ-ROUTER-021/-022), 실제 구현체를 쓰면 아래
        // when(...)/verify(...) 대상(서비스 mock)과 인자 검증 로직이 리팩토링 전후로 완전히 동일하게 유지된다.
        List<BackfillRouteHandler> handlers =
                List.of(
                        new DailyOhlcvRouteHandler(
                                domesticOhlcvService, overseasOhlcvService, windowAdvancer),
                        new ShortSaleDomesticRouteHandler(shortSaleService),
                        new InvestorTrendRouteHandler(investorTrendService),
                        new CreditBalanceRouteHandler(creditBalanceService),
                        new CorporateEventsRouteHandler(
                                revSplitService, overseasSplitService, windowAdvancer),
                        new CorporateEventsDividendRouteHandler(dividendService, windowAdvancer),
                        new CorporateEventsDividendOverseasRouteHandler(
                                overseasDividendBackfillService, windowAdvancer));
        executor =
                new BackfillWindowExecutor(
                        backfillStatusRepository,
                        domesticOhlcvService,
                        overseasOhlcvService,
                        terminationPolicy,
                        windowAdvancer,
                        backfillMetrics,
                        transactionTemplate,
                        new BackfillProperties(),
                        handlers);

        // resolveAnchor 공통 경로 고정 — lastCollectedDate·lastRowCount 둘 다 non-null이라
        // BackfillWindowExecutor.resolveAnchor는 전 data_table 공통으로 windowAdvancer.nextAnchor()로
        // 귀결된다. data_table별로 실제 소비 여부가 갈리므로(groupAFromDate는 GROUP_A/C만, decide는
        // persistWindow 호출 테스트만) strict-stub 오탐을 피하기 위해 lenient로 등록한다.
        lenient().when(windowAdvancer.nextAnchor(LAST_COLLECTED)).thenReturn(ANCHOR);
        lenient().when(windowAdvancer.groupAFromDate()).thenReturn(FLOOR);
        // persistLegacy/persistGated 공통 경로 — 종료 판정 자체는 이 SPEC의 검증 대상이 아니다(§B 보존 대상).
        lenient()
                .when(terminationPolicy.decide(any()))
                .thenReturn(TerminationDecision.inProgress(0));
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

    private BackfillStatus status(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status(BackfillStatusType.PENDING)
                .lastCollectedDate(LAST_COLLECTED)
                .lastRowCount(LAST_ROW_COUNT)
                .build();
    }

    /**
     * {@code backfillStatusRepository.findById}가 반환할 관리 엔티티 — FAILED가 아니면 충분(§ persistWindow 가드).
     */
    private BackfillStatus managed(String symbol, String dataTable) {
        return status(symbol, dataTable);
    }

    private void stubManagedLookup(BackfillStatus managed) {
        when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(managed));
    }

    @Nested
    @DisplayName("daily_ohlcv — 국내/해외 시장 분기 (EC-1)")
    class DailyOhlcv {

        @Test
        @DisplayName("국내 종목 → DomesticDailyOhlcvCollectionService, OverseasDailyOhlcv 미호출")
        void domestic_routesToDomesticService() throws InterruptedException {
            Stock samsung = stock("005930", Market.KOSPI);
            BackfillStatus status = status("005930", "daily_ohlcv");
            stubManagedLookup(managed("005930", "daily_ohlcv"));

            DomesticDailyOhlcvFetch fetchDto =
                    new DomesticDailyOhlcvFetch(List.of(), null, 0, 150, null);
            when(domesticOhlcvService.fetchWindow(FLOOR, ANCHOR, samsung, session))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0, 0);
            when(domesticOhlcvService.persistWindow(samsung, fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, samsung, session);
            BackfillWindowResult actual = executor.persistWindow(status, samsung, envelope);

            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.NOT_APPLICABLE);
            verify(domesticOhlcvService).fetchWindow(FLOOR, ANCHOR, samsung, session);
            verify(overseasOhlcvService, never()).fetchWindow(any(), any(), any());
            verify(domesticOhlcvService).persistWindow(samsung, fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }

        @Test
        @DisplayName("해외 종목 → OverseasDailyOhlcvCollectionService, DomesticDailyOhlcv 미호출")
        void overseas_routesToOverseasService() throws InterruptedException {
            Stock apple = stock("AAPL", Market.NASDAQ);
            BackfillStatus status = status("AAPL", "daily_ohlcv");
            stubManagedLookup(managed("AAPL", "daily_ohlcv"));

            OverseasDailyOhlcvFetch fetchDto =
                    new OverseasDailyOhlcvFetch(List.of(), null, 0, 150, null);
            when(overseasOhlcvService.fetchWindow(ANCHOR, apple, session)).thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0, 0);
            when(overseasOhlcvService.persistWindow(apple, fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, apple, session);
            BackfillWindowResult actual = executor.persistWindow(status, apple, envelope);

            verify(overseasOhlcvService).fetchWindow(ANCHOR, apple, session);
            verify(domesticOhlcvService, never()).fetchWindow(any(), any(), any(), any());
            verify(overseasOhlcvService).persistWindow(apple, fetchDto);
            verify(domesticOhlcvService, never()).persistWindow(any(), any());
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("short_sale_domestic")
    class ShortSaleDomestic {

        @Test
        @DisplayName("resolved status + stock + session으로 위임, 원본 status로 persist")
        void routesToShortSaleService() throws InterruptedException {
            Stock stock = stock("003550", Market.KOSPI);
            BackfillStatus status = status("003550", "short_sale_domestic");
            stubManagedLookup(managed("003550", "short_sale_domestic"));

            ShortSaleFetch fetchDto = new ShortSaleFetch(List.of(), null, 0);
            ArgumentCaptor<BackfillStatus> resolvedCaptor =
                    ArgumentCaptor.forClass(BackfillStatus.class);
            when(shortSaleService.fetchWindow(resolvedCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(shortSaleService.persistWindow(status, stock, fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            BackfillStatus resolved = resolvedCaptor.getValue();
            assertThat(resolved.getDataTable()).isEqualTo("short_sale_domestic");
            assertThat(resolved.getTargetCode()).isEqualTo("003550");
            assertThat(resolved.getLastCollectedDate()).isEqualTo(ANCHOR);
            verify(shortSaleService).persistWindow(status, stock, fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("investor_trend")
    class InvestorTrend {

        @Test
        @DisplayName("anchor(LocalDate) + stock + session으로 위임, 2-인자 persist")
        void routesToInvestorTrendService() throws InterruptedException {
            Stock stock = stock("005380", Market.KOSPI);
            BackfillStatus status = status("005380", "investor_trend");
            stubManagedLookup(managed("005380", "investor_trend"));

            InvestorTrendFetch fetchDto = new InvestorTrendFetch(List.of(), null, 0);
            when(investorTrendService.fetchWindow(ANCHOR, stock, session)).thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(investorTrendService.persistWindow(stock, fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            verify(investorTrendService).fetchWindow(ANCHOR, stock, session);
            verify(investorTrendService).persistWindow(stock, fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("credit_balance")
    class CreditBalance {

        @Test
        @DisplayName("resolved status + stock + session으로 위임, 3-인자(status·stock·fetch) persist")
        void routesToCreditBalanceService() throws InterruptedException {
            Stock stock = stock("000660", Market.KOSPI);
            BackfillStatus status = status("000660", "credit_balance");
            stubManagedLookup(managed("000660", "credit_balance"));

            CreditBalanceFetch fetchDto = new CreditBalanceFetch(List.of(), null, 0);
            ArgumentCaptor<BackfillStatus> resolvedCaptor =
                    ArgumentCaptor.forClass(BackfillStatus.class);
            when(creditBalanceService.fetchWindow(resolvedCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(creditBalanceService.persistWindow(status, stock, fetchDto))
                    .thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            assertThat(resolvedCaptor.getValue().getLastCollectedDate()).isEqualTo(ANCHOR);
            verify(creditBalanceService).persistWindow(status, stock, fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("corporate_events — 시장별 소스 분기 (EC-2)")
    class CorporateEvents {

        @Test
        @DisplayName("국내 종목 → RevSplitCollectionService, OverseasSplit 미호출")
        void domestic_routesToRevSplit() throws InterruptedException {
            Stock stock = stock("005930", Market.KOSPI);
            BackfillStatus status = status("005930", "corporate_events");
            stubManagedLookup(managed("005930", "corporate_events"));
            LocalDate today = LocalDate.now(KST);

            RevSplitBackfillFetch fetchDto = new RevSplitBackfillFetch(List.of(), null, 0);
            when(revSplitService.fetchWindowForBackfill(stock, session, FLOOR, today))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(revSplitService.persistWindowForBackfill(fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            verify(revSplitService).fetchWindowForBackfill(stock, session, FLOOR, today);
            verify(overseasSplitService, never())
                    .fetchWindowForBackfill(any(), any(), any(), any());
            verify(revSplitService).persistWindowForBackfill(fetchDto);
            verify(overseasSplitService, never()).persistWindowForBackfill(any());
            assertThat(actual).isEqualTo(persistResult);
        }

        @Test
        @DisplayName("해외 종목 → OverseasSplitCollectionService, RevSplit 미호출")
        void overseas_routesToOverseasSplit() throws InterruptedException {
            Stock stock = stock("AAPL", Market.NASDAQ);
            BackfillStatus status = status("AAPL", "corporate_events");
            stubManagedLookup(managed("AAPL", "corporate_events"));
            LocalDate today = LocalDate.now(KST);

            OverseasSplitBackfillFetch fetchDto =
                    new OverseasSplitBackfillFetch(List.of(), null, 0);
            when(overseasSplitService.fetchWindowForBackfill(stock, session, FLOOR, today))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(overseasSplitService.persistWindowForBackfill(fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            verify(overseasSplitService).fetchWindowForBackfill(stock, session, FLOOR, today);
            verify(revSplitService, never()).fetchWindowForBackfill(any(), any(), any(), any());
            verify(overseasSplitService).persistWindowForBackfill(fetchDto);
            verify(revSplitService, never()).persistWindowForBackfill(any());
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("corporate_events_dividend")
    class CorporateEventsDividend {

        @Test
        @DisplayName("floor(groupAFromDate)~anchor로 위임, 1-인자 persist")
        void routesToDividendService() throws InterruptedException {
            Stock stock = stock("005930", Market.KOSPI);
            BackfillStatus status = status("005930", "corporate_events_dividend");
            stubManagedLookup(managed("005930", "corporate_events_dividend"));

            DividendBackfillFetch fetchDto = new DividendBackfillFetch(List.of(), null, 0);
            when(dividendService.fetchWindowForBackfill(stock, session, FLOOR, ANCHOR))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(dividendService.persistWindowForBackfill(fetchDto)).thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            verify(dividendService).fetchWindowForBackfill(stock, session, FLOOR, ANCHOR);
            verify(dividendService).persistWindowForBackfill(fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("corporate_events_dividend_overseas")
    class CorporateEventsDividendOverseas {

        @Test
        @DisplayName("listedDate 미상 종목 → fixedFloor(groupAFromDate)~today로 위임, 1-인자 persist")
        void nullListedDate_usesFixedFloor() throws InterruptedException {
            Stock stock = stock("AAPL", Market.NASDAQ);
            BackfillStatus status = status("AAPL", "corporate_events_dividend_overseas");
            stubManagedLookup(managed("AAPL", "corporate_events_dividend_overseas"));
            LocalDate today = LocalDate.now(KST);

            OverseasDividendBackfillFetch fetchDto =
                    new OverseasDividendBackfillFetch(List.of(), null, 0);
            when(overseasDividendBackfillService.fetchWindowForBackfill(
                            stock, session, FLOOR, today))
                    .thenReturn(fetchDto);
            BackfillWindowResult persistResult = new BackfillWindowResult(null, 0);
            when(overseasDividendBackfillService.persistWindowForBackfill(fetchDto))
                    .thenReturn(persistResult);

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            verify(overseasDividendBackfillService)
                    .fetchWindowForBackfill(stock, session, FLOOR, today);
            verify(overseasDividendBackfillService).persistWindowForBackfill(fetchDto);
            assertThat(actual).isEqualTo(persistResult);
        }
    }

    @Nested
    @DisplayName("미등록 data_table 폴백 (REQ-ROUTER-004, Scenario 6)")
    class UnregisteredDataTable {

        @Test
        @DisplayName("routeFetch 미등록 → warn + null 반환, 어떤 협업 서비스도 호출하지 않음")
        void unknownDataTable_fetchReturnsNull() throws InterruptedException {
            Stock stock = stock("XXXX", Market.KOSPI);
            BackfillStatus status = status("XXXX", "unknown_table");

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);

            assertThat(envelope.serviceFetch()).isNull();
            assertThat(envelope.probeOutcome()).isEqualTo(ProbeOutcome.NOT_APPLICABLE);
            verify(domesticOhlcvService, never()).fetchWindow(any(), any(), any(), any());
            verify(shortSaleService, never()).fetchWindow(any(), any(), any());
        }

        @Test
        @DisplayName("routePersist null fetchDto → 즉시 EMPTY 반환, 어떤 협업 서비스도 호출하지 않음")
        void nullFetchDto_persistReturnsEmpty() throws InterruptedException {
            Stock stock = stock("XXXX", Market.KOSPI);
            BackfillStatus status = status("XXXX", "unknown_table");
            stubManagedLookup(managed("XXXX", "unknown_table"));

            FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
            BackfillWindowResult actual = executor.persistWindow(status, stock, envelope);

            assertThat(actual).isEqualTo(BackfillWindowResult.EMPTY);
            verify(domesticOhlcvService, never()).persistWindow(any(), any());
        }
    }
}
