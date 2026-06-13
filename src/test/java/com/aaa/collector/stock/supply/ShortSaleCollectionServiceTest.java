package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleCollectionService 단위 테스트")
class ShortSaleCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private ShortSaleCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new ShortSaleCollectionService(
                        stockRepository,
                        shortSaleDomesticRepository,
                        batchRestExecutor,
                        distributor);
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

    private KisShortSaleResponse.ShortSaleRow row(String date) {
        return new KisShortSaleResponse.ShortSaleRow(
                date, "12000", "3.5", "900000000", "4.2", "50000", "5.1", "3750000000", "6.3");
    }

    private KisShortSaleResponse response(List<KisShortSaleResponse.ShortSaleRow> rows) {
        return new KisShortSaleResponse("0", "MCA00000", "정상", rows);
    }

    private void stubFetch(String symbol, KisShortSaleResponse r) {
        when(batchRestExecutor.execute(
                        eq(ISA), any(), anyString(), eq(KisShortSaleResponse.class), eq(symbol)))
                .thenReturn(BatchResult.success(r));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActive()).thenReturn(List.of(stock));
        when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
    }

    @Nested
    @DisplayName("collect — 성공 + 매핑 (REQ-041, -042)")
    class CollectSuccess {

        @Test
        @DisplayName("매핑 + 금액 원 단위 무변환 (OI-2)")
        void success_mapping_noConversion() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch("005930", response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            ArgumentCaptor<ShortSaleDomestic> captor =
                    ArgumentCaptor.forClass(ShortSaleDomestic.class);
            verify(shortSaleDomesticRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue())
                    .extracting(
                            ShortSaleDomestic::getTradeDate,
                            ShortSaleDomestic::getShortSellQty,
                            ShortSaleDomestic::getShortSellVolRate,
                            ShortSaleDomestic::getShortSellAmt,
                            ShortSaleDomestic::getShortSellAmtRate,
                            ShortSaleDomestic::getShortSellAccQty,
                            ShortSaleDomestic::getShortSellAccQtyRate,
                            ShortSaleDomestic::getShortSellAccAmt,
                            ShortSaleDomestic::getShortSellAccAmtRate)
                    .containsExactly(
                            LocalDate.of(2026, 6, 12),
                            12_000L,
                            new BigDecimal("3.5"),
                            900_000_000L,
                            new BigDecimal("4.2"),
                            50_000L,
                            new BigDecimal("5.1"),
                            3_750_000_000L,
                            new BigDecimal("6.3"));
        }
    }

    @Nested
    @DisplayName("collect — 검증 (REQ-060 비율 경계 M-2, 음수, 윈도우)")
    class Validation {

        @Test
        @DisplayName("비율 절댓값 ≥ 1000 행 — 저장 제외 (DECIMAL(7,4) 경계, AC-7 S7-1b)")
        void rateOverBoundary_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisShortSaleResponse.ShortSaleRow bad =
                    new KisShortSaleResponse.ShortSaleRow(
                            "20260612",
                            "12000",
                            "1000.0",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(shortSaleDomesticRepository, never())
                    .insertIgnoreDuplicate(any(ShortSaleDomestic.class));
        }

        @Test
        @DisplayName("비율 절댓값 < 1000 정상 — 저장됨")
        void rateWithinBoundary_inserted() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisShortSaleResponse.ShortSaleRow ok =
                    new KisShortSaleResponse.ShortSaleRow(
                            "20260612",
                            "12000",
                            "999.9999",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
            stubFetch("005930", response(List.of(ok)));

            service.collect(TODAY);

            verify(shortSaleDomesticRepository, times(1))
                    .insertIgnoreDuplicate(any(ShortSaleDomestic.class));
        }

        @Test
        @DisplayName("음수 수량 행 — 저장 제외 (공매도는 음수 비정상)")
        void negativeQty_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisShortSaleResponse.ShortSaleRow bad =
                    new KisShortSaleResponse.ShortSaleRow(
                            "20260612",
                            "-12000",
                            "3.5",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(shortSaleDomesticRepository, never())
                    .insertIgnoreDuplicate(any(ShortSaleDomestic.class));
        }

        @Test
        @DisplayName("14일 윈도우 밖 행 — 저장 제외")
        void outsideWindow_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch("005930", response(List.of(row("20260520"), row("20260612"))));

            service.collect(TODAY);

            ArgumentCaptor<ShortSaleDomestic> captor =
                    ArgumentCaptor.forClass(ShortSaleDomestic.class);
            verify(shortSaleDomesticRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("빈 output2 — 0건 succeeded (REQ-063)")
        void empty_succeeded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch("005930", response(List.of()));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("collect — 종목 skip")
    class StockSkip {

        @Test
        @DisplayName("KisTokenIssueException — graceful skip")
        void tokenIssue_skip() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class),
                            eq("005930")))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("BatchResult.skip — skip 집계")
        void batchSkip_counted() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisShortSaleResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "테스트 skip"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("null trade_date 행 — 저장 제외, 종목은 succeeded")
        void nullTradeDate_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisShortSaleResponse.ShortSaleRow bad =
                    new KisShortSaleResponse.ShortSaleRow(
                            null,
                            "12000",
                            "3.5",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
            stubFetch("005930", response(List.of(bad)));

            SupplyDemandResult result = service.collect(TODAY);

            verify(shortSaleDomesticRepository, never())
                    .insertIgnoreDuplicate(any(ShortSaleDomestic.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외")
        void unparseableRow_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisShortSaleResponse.ShortSaleRow bad =
                    new KisShortSaleResponse.ShortSaleRow(
                            "20260612",
                            "x",
                            "3.5",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(shortSaleDomesticRepository, never())
                    .insertIgnoreDuplicate(any(ShortSaleDomestic.class));
        }
    }

    @Nested
    @DisplayName("collect — 대상 없음 / 모든 키 죽음")
    class NoTargets {

        @Test
        @DisplayName("활성 종목 없음 — 0/0/0, execute 미호출")
        void noActiveStocks_zero() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(0);
            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("빈 할당 — execute 0회, 전체 skip")
        void emptyAllocation_skipAll() {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            List<Stock> stocks = List.of(s1, s2);
            when(stockRepository.findAllActive()).thenReturn(stocks);
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            SupplyDemandResult result = service.collect(TODAY);

            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
        }
    }
}
