package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-BACKFILL-001 T4 — InvestorTrendCollectionService.collectWindow 단위 테스트.
 *
 * <p>rt_cd=2(비영업일 anchor 거부) anchor-skip 보정: 최대 10회 재시도, anchor −1 calendar day/회. 소진 시 EMPTY 반환
 * (REQ-BACKFILL-016).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestorTrendCollectionService.collectWindow 단위 테스트 (T4)")
class InvestorTrendCollectionServiceWindowTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private InvestorTrendInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private InvestorTrendCollectionService service;
    private BackfillWindowAdvancer windowAdvancer;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        session = keyLeaseRegistry.openSession();
        // anchorSkipMax=10 (BackfillProperties 기본값과 동일, REQ-BACKFILL-016)
        windowAdvancer = new BackfillWindowAdvancer(LocalDate.of(1950, 1, 1), 10);
        service =
                new InvestorTrendCollectionService(
                        stockRepository,
                        inserter,
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        windowAdvancer);
    }

    private Stock stockOf(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    /**
     * 검증 통과 행 3개짜리 응답 — acmlVol/acmlTrPbmn은 양수, 순매수 계열은 0으로 단순화.
     *
     * <p>rt_cd="0" 정상 응답.
     */
    private KisInvestorTrendResponse successResponse(List<String> dates) {
        List<KisInvestorTrendResponse.InvestorTrendRow> rows =
                dates.stream().map(this::makeRow).toList();
        return new KisInvestorTrendResponse("0", "MCA00000", "정상", rows);
    }

    private KisInvestorTrendResponse rtCd2Response() {
        return new KisInvestorTrendResponse("2", "MCA00001", "비영업일", List.of());
    }

    private KisInvestorTrendResponse.InvestorTrendRow makeRow(String date) {
        return new KisInvestorTrendResponse.InvestorTrendRow(
                date,
                "1000", // frgnNtbyQty
                "500", // orgnNtbyQty
                "-200", // prsnNtbyQty (음수 정상)
                "100", // frgnNtbyTrPbmn
                "50", // orgnNtbyTrPbmn
                "-20", // prsnNtbyTrPbmn (음수 정상)
                "10000", // acmlVol (양수)
                "5000" // acmlTrPbmn (양수)
                );
    }

    @Nested
    @DisplayName("collectWindow — 정상 응답 (rt_cd=0)")
    class NormalResponse {

        @Test
        @DisplayName("rt_cd=0, 3건 응답 — 최소 거래일과 rowCount=3 반환")
        void collectWindow_rt_cd0_returnsOldest() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260611", "20260612", "20260613")));

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
            assertThat(result.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 output2 (rt_cd=0) — EMPTY 반환")
        void collectWindow_emptyResponse_returnsEmpty() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(new KisInvestorTrendResponse("0", "MCA00000", "정상", List.of()));

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collectWindow — rt_cd=2 anchor-skip 보정 (REQ-BACKFILL-016)")
    class AnchorSkipCorrection {

        @Test
        @DisplayName("첫 호출 rt_cd=2, 두 번째 rt_cd=0 — anchor -1일 후 성공, rowCount=2")
        void collectWindow_rt_cd2_skipsAnchorAndRetries() throws Exception {
            // Arrange
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response())
                    .thenReturn(successResponse(List.of("20260609", "20260610")));

            // Act
            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            // Assert
            assertThat(result.rowCount()).isEqualTo(2);
            verify(guardedKisExecutor, times(2))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class));
        }

        @Test
        @DisplayName(
                "rt_cd=2 연속 — 소진 후 EMPTY 반환"
                        + " (최초 1회 + anchorSkipMax-1회 재시도 = 10회 총 호출, attempts=10에서 fetch 전 종료)")
        void collectWindow_rt_cd2_exhaustedRetries_returnsEmpty() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response());

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            // correctRejectedAnchor(anchor, 9) → attempts=10 = anchorSkipMax → exhausted=true.
            // exhausted 시 fetch를 하지 않고 즉시 EMPTY 반환 → 총 10회 (최초 1 + 재시도 9)
            verify(guardedKisExecutor, times(10))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class));
        }

        @Test
        @DisplayName(
                "rt_cd=2 시 windowAdvancer.correctRejectedAnchor()가 호출됨을 검증 (W-5, REQ-BACKFILL-016)")
        void collectWindow_rt_cd2_invokesWindowAdvancer() throws Exception {
            // Arrange — spy로 advancer 호출 횟수 검증
            BackfillWindowAdvancer spyAdvancer = spy(windowAdvancer);
            InvestorTrendCollectionService spyService =
                    new InvestorTrendCollectionService(
                            stockRepository,
                            inserter,
                            guardedKisExecutor,
                            new KeyLeaseRegistry(healthyKeySelector),
                            spyAdvancer);

            // ANCHOR=2026-06-13, rt_cd=2 → effectiveAnchor → 2026-06-12.
            // 두 번째 호출 시 윈도우: 2026-06-12 이전 14일 이내 날짜만 유효.
            // 2026-06-11·2026-06-12 모두 windowStart(2026-05-29)~effectiveAnchor(2026-06-12) 범위.
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response())
                    .thenReturn(successResponse(List.of("20260611", "20260612")));

            // Act
            BackfillWindowResult result =
                    spyService.collectWindow(stockOf("005930"), session, ANCHOR);

            // Assert — correctRejectedAnchor가 1회 호출됨
            verify(spyAdvancer, times(1)).correctRejectedAnchor(any(), anyInt());
            assertThat(result.rowCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("collectWindow — StockRepository 미호출 보장")
    class NoStockRepoCall {

        @Test
        @DisplayName("collectWindow — StockRepository를 호출하지 않음 (호출자가 stock을 전달)")
        void collectWindow_doesNotCallStockRepository() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260613")));

            service.collectWindow(stockOf("005930"), session, ANCHOR);

            verify(stockRepository, never()).findAllActiveTradable();
        }
    }

    @Nested
    @DisplayName("fetchWindow / persistWindow — fetch·persist 분리 (T5, REQ-TXBOUNDARY-002)")
    class FetchPersistWindow {

        @Test
        @DisplayName("fetchWindow — inserter(DB) 미호출 (fetch 단계에서 INSERT 없음)")
        void fetchWindow_doesNotCallInserter() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260611", "20260612", "20260613")));

            InvestorTrendFetch fetch = service.fetchWindow(ANCHOR, stockOf("005930"), session);

            verify(inserter, never()).insertBatch(any());
            assertThat(fetch.rowCount()).isEqualTo(3);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        }

        @Test
        @DisplayName("fetchWindow — 빈 응답 시 rows=빈목록, oldestTradeDate=null, rowCount=0")
        void fetchWindow_emptyResponse_emptyFetch() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(new KisInvestorTrendResponse("0", "MCA00000", "정상", List.of()));

            InvestorTrendFetch fetch = service.fetchWindow(ANCHOR, stockOf("005930"), session);

            assertThat(fetch.rows()).isEmpty();
            assertThat(fetch.oldestTradeDate()).isNull();
            assertThat(fetch.rowCount()).isZero();
            verify(inserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("fetchWindow — rt_cd=2 anchor-skip 보정 루프가 fetchWindow에 잔류 (HARD)")
        void fetchWindow_rt_cd2_retryLoopInFetch() throws Exception {
            // rt_cd=2 첫 호출 → anchor-skip 보정 후 rt_cd=0 두 번째 호출
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response())
                    .thenReturn(successResponse(List.of("20260611", "20260612")));

            InvestorTrendFetch fetch = service.fetchWindow(ANCHOR, stockOf("005930"), session);

            // fetch 단계에서 retry 발생 (execute 2회), inserter는 여전히 미호출
            verify(guardedKisExecutor, times(2))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class));
            verify(inserter, never()).insertBatch(any());
            assertThat(fetch.rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("persistWindow — inserter.insertBatch 1회 호출 (persist에서 INSERT 발생)")
        void persistWindow_callsInserter() {
            Stock stock = stockOf("005930");
            InvestorTrend entity =
                    InvestorTrend.builder()
                            .stock(stock)
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .foreignNetQty(1000L)
                            .institutionNetQty(500L)
                            .individualNetQty(-200L)
                            .foreignNetValue(100_000_000L)
                            .institutionNetValue(50_000_000L)
                            .individualNetValue(-20_000_000L)
                            .totalVolume(10_000L)
                            .totalTradingValue(5_000_000_000L)
                            .build();
            InvestorTrendFetch fetch =
                    new InvestorTrendFetch(List.of(entity), LocalDate.of(2026, 6, 11), 1);

            BackfillWindowResult result = service.persistWindow(stock, fetch);

            verify(inserter, times(1)).insertBatch(any());
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
            assertThat(result.rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("persistWindow — 빈 fetch → EMPTY 반환, inserter 미호출")
        void persistWindow_emptyFetch_returnsEmpty() {
            InvestorTrendFetch emptyFetch = new InvestorTrendFetch(List.of(), null, 0);

            BackfillWindowResult result = service.persistWindow(stockOf("005930"), emptyFetch);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("회귀 가드 — 당일 경로(collect) green 유지: saveValidRows가 collect에서 정상 동작")
        void dailyPathRegression_collectStillWorks() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260613")));

            SupplyDemandResult result = service.collect(ANCHOR);

            assertThat(result.succeeded()).isEqualTo(1);
            verify(inserter, times(1)).insertBatch(any());
        }
    }
}
