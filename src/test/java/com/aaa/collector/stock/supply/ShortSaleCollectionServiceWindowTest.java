package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SPEC-COLLECTOR-TXBOUNDARY-001 T4 — ShortSaleCollectionService.fetchWindow/persistWindow 단위 테스트.
 *
 * <p>fetch 단계: HTTP + 매핑/검증, DB 접촉 없음. persist 단계: inserter 호출. 당일 경로 회귀 가드 포함.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleCollectionService.fetchWindow/persistWindow 단위 테스트 (T4)")
class ShortSaleCollectionServiceWindowTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private ShortSaleInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private ShortSaleCollectionService service;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        session = keyLeaseRegistry.openSession();
        service =
                new ShortSaleCollectionService(
                        stockRepository,
                        new ShortSaleRowMapper(),
                        inserter,
                        guardedKisExecutor,
                        keyLeaseRegistry);
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

    private BackfillStatus statusOf(LocalDate lastCollectedDate) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode("005930")
                .dataTable("short_sale_domestic")
                .status(BackfillStatusType.IN_PROGRESS)
                .lastCollectedDate(lastCollectedDate)
                .build();
    }

    private KisShortSaleResponse.ShortSaleRow row(String date) {
        return new KisShortSaleResponse.ShortSaleRow(
                date, "12000", "3.5", "900000000", "4.2", "50000", "5.1", "3750000000", "6.3");
    }

    private KisShortSaleResponse response(List<KisShortSaleResponse.ShortSaleRow> rows) {
        return new KisShortSaleResponse("0", "MCA00000", "정상", rows);
    }

    @Nested
    @DisplayName("fetchWindow — fetch 단계 (DB 미접촉)")
    class FetchWindow {

        @Test
        @DisplayName("fetchWindow — inserter(DB) 미호출 (fetch 단계에서 INSERT 없음)")
        void fetchWindow_doesNotCallInserter() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(response(List.of(row("20260612"))));

            ShortSaleFetch fetch =
                    service.fetchWindow(statusOf(ANCHOR), stockOf("005930"), session);

            verify(inserter, never()).insertBatch(anyList());
            assertThat(fetch.rowCount()).isEqualTo(1);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("fetchWindow — 빈 응답 시 rows=빈목록, oldestTradeDate=null, rowCount=0")
        void fetchWindow_emptyResponse_emptyFetch() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(response(List.of()));

            ShortSaleFetch fetch =
                    service.fetchWindow(statusOf(ANCHOR), stockOf("005930"), session);

            assertThat(fetch.rows()).isEmpty();
            assertThat(fetch.oldestTradeDate()).isNull();
            assertThat(fetch.rowCount()).isZero();
            verify(inserter, never()).insertBatch(anyList());
        }

        @Test
        @DisplayName("fetchWindow — anchor를 FID_INPUT_DATE_2, anchor-90을 FID_INPUT_DATE_1으로 전송")
        @SuppressWarnings("unchecked")
        void fetchWindow_sendsAnchorAndLookbackParams() throws Exception {
            // Arrange — ANCHOR=2026-06-13 → DATE_2=20260613, DATE_1=20260315(anchor-90days)
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(response(List.of(row("20260612"))));

            service.fetchWindow(statusOf(ANCHOR), stockOf("005930"), session);

            URI built = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(built.toString()).contains("FID_INPUT_DATE_2=20260613");
            assertThat(built.toString()).contains("FID_INPUT_DATE_1=20260315");
        }

        @Test
        @DisplayName("fetchWindow — 여러 행 중 최소 거래일을 oldestTradeDate로 노출")
        void fetchWindow_multipleRows_returnsOldest() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(
                            response(List.of(row("20260612"), row("20260611"), row("20260610"))));

            ShortSaleFetch fetch =
                    service.fetchWindow(statusOf(ANCHOR), stockOf("005930"), session);

            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 10));
            assertThat(fetch.rowCount()).isEqualTo(3);
            verify(inserter, never()).insertBatch(anyList());
        }
    }

    @Nested
    @DisplayName("persistWindow — persist 단계 (inserter 호출)")
    class PersistWindow {

        @Test
        @DisplayName("persistWindow — inserter.insertBatch 1회 호출 (persist에서 INSERT 발생)")
        void persistWindow_callsInserter() {
            Stock stock = stockOf("005930");
            ShortSaleDomestic entity =
                    ShortSaleDomestic.builder()
                            .stock(stock)
                            .tradeDate(LocalDate.of(2026, 6, 12))
                            .shortSellQty(12_000L)
                            .shortSellVolRate(new BigDecimal("3.5"))
                            .shortSellAmt(900_000_000L)
                            .shortSellAmtRate(new BigDecimal("4.2"))
                            .shortSellAccQty(50_000L)
                            .shortSellAccQtyRate(new BigDecimal("5.1"))
                            .shortSellAccAmt(3_750_000_000L)
                            .shortSellAccAmtRate(new BigDecimal("6.3"))
                            .build();
            ShortSaleFetch fetch =
                    new ShortSaleFetch(List.of(entity), LocalDate.of(2026, 6, 12), 1);

            // Act
            BackfillWindowResult result = service.persistWindow(statusOf(ANCHOR), stock, fetch);

            // Assert
            verify(inserter, times(1)).insertBatch(anyList());
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
            assertThat(result.rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("persistWindow — 빈 fetch → EMPTY 반환, inserter 미호출")
        void persistWindow_emptyFetch_returnsEmpty() {
            ShortSaleFetch emptyFetch = new ShortSaleFetch(List.of(), null, 0);

            BackfillWindowResult result =
                    service.persistWindow(statusOf(ANCHOR), stockOf("005930"), emptyFetch);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(anyList());
        }
    }

    @Nested
    @DisplayName("StockRepository 미호출 보장")
    class NoStockRepoCall {

        @Test
        @DisplayName("fetchWindow — StockRepository를 호출하지 않음 (호출자가 stock을 전달)")
        void fetchWindow_doesNotCallStockRepository() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(response(List.of()));

            service.fetchWindow(statusOf(ANCHOR), stockOf("005930"), session);

            verify(stockRepository, never()).findAllActiveTradable();
        }
    }

    @Nested
    @DisplayName("회귀 가드 — 당일 경로 green 유지")
    class DailyPathRegression {

        @Test
        @DisplayName("collect — saveValidRows가 당일 경로에서 정상 동작 (inserter 1회 호출)")
        void collect_dailyPath_inserterCalled() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class)))
                    .thenReturn(response(List.of(row("20260612"))));

            // Act
            SupplyDemandResult result = service.collect(LocalDate.of(2026, 6, 13));

            // Assert
            assertThat(result.succeeded()).isEqualTo(1);
            verify(inserter, times(1)).insertBatch(anyList());
        }
    }
}
