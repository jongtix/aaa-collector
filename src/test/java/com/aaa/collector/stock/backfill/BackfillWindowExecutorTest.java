package com.aaa.collector.stock.backfill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.TerminationDecision;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitBackfillFetch;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitBackfillFetch;
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
                        transactionTemplate);
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

            executor.persistWindow(status, aapl, fetch);

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

            executor.persistWindow(status, samsung, fetch);

            verify(revSplitService).persistWindowForBackfill(fetch);
            verify(overseasSplitService, never()).persistWindowForBackfill(any());
        }
    }
}
