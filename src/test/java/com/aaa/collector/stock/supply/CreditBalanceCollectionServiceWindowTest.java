package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.CreditBalance;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
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
 * SPEC-COLLECTOR-BACKFILL-001 T4 — CreditBalanceCollectionService.collectWindow 단위 테스트.
 *
 * <p>MA-05 실측(2026-06-20): FHPST04760000은 주말 anchor에 rt_cd=0을 반환 → anchor-skip 보정 없음. 종료 판정은
 * stale-count 기반(T5/T6 책임).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditBalanceCollectionService.collectWindow 단위 테스트 (T4)")
class CreditBalanceCollectionServiceWindowTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private CreditBalanceRowMapper mapper;
    @Mock private CreditBalanceInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private CreditBalanceCollectionService service;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        session = keyLeaseRegistry.openSession();
        service =
                new CreditBalanceCollectionService(
                        stockRepository, mapper, inserter, guardedKisExecutor, keyLeaseRegistry);
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

    private KisCreditBalanceResponse anyResponse() {
        return new KisCreditBalanceResponse("0", "MCA00000", "정상", List.of());
    }

    @Nested
    @DisplayName("collectWindow — 정상 응답")
    class NormalResponse {

        @Test
        @DisplayName("3건 응답 — 최소 deal_date와 rowCount=3 반환")
        void collectWindow_returnsOldestDealDate() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);

            CreditBalance cb1 = makeCreditBalance(stock, LocalDate.of(2026, 6, 11));
            CreditBalance cb2 = makeCreditBalance(stock, LocalDate.of(2026, 6, 12));
            CreditBalance cb3 = makeCreditBalance(stock, LocalDate.of(2026, 6, 13));
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(List.of(cb1, cb2, cb3));

            // Act
            BackfillWindowResult result = service.collectWindow(stock, session, ANCHOR);

            // Assert
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
            assertThat(result.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 응답 — EMPTY 반환")
        void collectWindow_emptyResponse_returnsEmpty() throws Exception {
            Stock stock = stockOf("005930");
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(List.of());

            BackfillWindowResult result = service.collectWindow(stock, session, ANCHOR);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collectWindow — anchor-skip 보정 없음 (MA-05 실측, REQ-BACKFILL-016 적용 제외)")
    class NoAnchorCorrection {

        @Test
        @DisplayName("rt_cd=2(가상)에도 fetch 1회만 호출 — retry 루프 없음")
        void collectWindow_neverCorrectsAnchorForRtCd2() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            // rt_cd=2 응답 (가상 — FHPST04760000은 실제로 rt_cd=0 반환하지만 구현이 보정 없음을 검증)
            KisCreditBalanceResponse rtCd2Response =
                    new KisCreditBalanceResponse("2", "MCA00001", "비영업일", List.of());
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(rtCd2Response);
            when(mapper.collectValid(eq(stock), anyString(), eq(rtCd2Response), any(), any()))
                    .thenReturn(List.of());

            // Act
            service.collectWindow(stock, session, ANCHOR);

            // Assert — fetch 정확히 1회 (retry 루프 없음)
            verify(guardedKisExecutor, times(1))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class));
        }
    }

    @Nested
    @DisplayName("collectWindow — StockRepository 미호출 보장")
    class NoStockRepoCall {

        @Test
        @DisplayName("collectWindow — StockRepository를 호출하지 않음 (호출자가 stock을 전달)")
        void collectWindow_doesNotCallStockRepository() throws Exception {
            Stock stock = stockOf("005930");
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(List.of());

            service.collectWindow(stock, session, ANCHOR);

            verify(stockRepository, never()).findAllActiveTradable();
        }
    }

    @Nested
    @DisplayName("fetchWindow / persistWindow — fetch·persist 분리 (T6, REQ-TXBOUNDARY-002)")
    class FetchPersistWindow {

        private BackfillStatus statusOf(LocalDate lastCollectedDate) {
            return BackfillStatus.builder()
                    .targetType("STOCK")
                    .targetCode("005930")
                    .dataTable("credit_balance")
                    .status(BackfillStatusType.IN_PROGRESS)
                    .lastCollectedDate(lastCollectedDate)
                    .build();
        }

        @Test
        @DisplayName("fetchWindow — inserter(DB) 미호출 (fetch 단계에서 INSERT 없음)")
        void fetchWindow_doesNotCallInserter() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(
                            List.of(
                                    makeCreditBalance(stock, LocalDate.of(2026, 6, 11)),
                                    makeCreditBalance(stock, LocalDate.of(2026, 6, 12))));

            // Act
            CreditBalanceFetch fetch = service.fetchWindow(statusOf(ANCHOR), stock, session);

            // Assert — fetch 단계에서 DB INSERT 없음
            verify(inserter, never()).insertBatch(any());
            assertThat(fetch.rowCount()).isEqualTo(2);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        }

        @Test
        @DisplayName("fetchWindow — 빈 응답 시 rows=빈목록, oldestTradeDate=null, rowCount=0")
        void fetchWindow_emptyResponse_emptyFetch() throws Exception {
            Stock stock = stockOf("005930");
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(List.of());

            CreditBalanceFetch fetch = service.fetchWindow(statusOf(ANCHOR), stock, session);

            assertThat(fetch.rows()).isEmpty();
            assertThat(fetch.oldestTradeDate()).isNull();
            assertThat(fetch.rowCount()).isZero();
            verify(inserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("persistWindow — inserter.insertBatch 1회 호출 (persist에서 INSERT 발생)")
        void persistWindow_callsInserter() {
            Stock stock = stockOf("005930");
            CreditBalance entity = makeCreditBalance(stock, LocalDate.of(2026, 6, 11));
            CreditBalanceFetch fetch =
                    new CreditBalanceFetch(List.of(entity), LocalDate.of(2026, 6, 11), 1);

            BackfillWindowResult result = service.persistWindow(statusOf(ANCHOR), stock, fetch);

            verify(inserter, times(1)).insertBatch(any());
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
            assertThat(result.rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("persistWindow — 빈 fetch → EMPTY 반환, inserter 미호출")
        void persistWindow_emptyFetch_returnsEmpty() {
            CreditBalanceFetch emptyFetch = new CreditBalanceFetch(List.of(), null, 0);

            BackfillWindowResult result =
                    service.persistWindow(statusOf(ANCHOR), stockOf("005930"), emptyFetch);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("회귀 가드 — 당일 경로(collect) green 유지: saveValidRows가 collect에서 정상 동작")
        void dailyPathRegression_collectStillWorks() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            KisCreditBalanceResponse response = anyResponse();
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenReturn(response);
            when(mapper.collectValid(eq(stock), anyString(), eq(response), any(), any()))
                    .thenReturn(List.of(makeCreditBalance(stock, LocalDate.of(2026, 6, 12))));

            // Act
            SupplyDemandResult result = service.collect(ANCHOR);

            // Assert
            assertThat(result.succeeded()).isEqualTo(1);
            verify(inserter, times(1)).insertBatch(any());
        }
    }

    /** 테스트용 CreditBalance 엔티티 생성 헬퍼. */
    private CreditBalance makeCreditBalance(Stock stock, LocalDate tradeDate) {
        return CreditBalance.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .loanNewQty(100L)
                .loanRepayQty(50L)
                .loanBalanceQty(200L)
                .loanNewAmt(1000L)
                .loanRepayAmt(500L)
                .loanBalanceAmt(2000L)
                .loanBalanceRate(BigDecimal.valueOf(1.5))
                .loanSupplyRate(BigDecimal.valueOf(0.5))
                .lendNewQty(10L)
                .lendRepayQty(5L)
                .lendBalanceQty(20L)
                .lendNewAmt(100L)
                .lendRepayAmt(50L)
                .lendBalanceAmt(200L)
                .lendBalanceRate(BigDecimal.valueOf(0.1))
                .lendSupplyRate(BigDecimal.valueOf(0.05))
                .build();
    }
}
