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
import com.aaa.collector.stock.CreditBalance;
import com.aaa.collector.stock.CreditBalanceRepository;
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
@DisplayName("CreditBalanceCollectionService 단위 테스트")
class CreditBalanceCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private CreditBalanceRepository creditBalanceRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private CreditBalanceCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new CreditBalanceCollectionService(
                        stockRepository, creditBalanceRepository, batchRestExecutor, distributor);
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

    /** deal_date(매매일자)와 stlm_date(결제일자)를 다르게 둔다. */
    private KisCreditBalanceResponse.CreditBalanceRow row(String dealDate, String stlmDate) {
        return new KisCreditBalanceResponse.CreditBalanceRow(
                dealDate, stlmDate, "100", "50", "1000", "700", "350", "7000", "1.5", "2.5", "10",
                "5", "100", "70", "35", "700", "0.5", "0.3");
    }

    private KisCreditBalanceResponse response(
            List<KisCreditBalanceResponse.CreditBalanceRow> rows) {
        return new KisCreditBalanceResponse("0", "MCA00000", "정상", rows);
    }

    private void stubFetch(String symbol, KisCreditBalanceResponse r) {
        when(batchRestExecutor.execute(
                        eq(ISA),
                        any(),
                        anyString(),
                        eq(KisCreditBalanceResponse.class),
                        eq(symbol)))
                .thenReturn(BatchResult.success(r));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
    }

    @Nested
    @DisplayName("collect — 성공 + 매핑 (REQ-051, -052, -053)")
    class CollectSuccess {

        @Test
        @DisplayName("trade_date에 deal_date 매핑(stlm_date 미사용) + 만원 무변환 (AC-6 S6-2/S6-3)")
        void success_dealDateMapping_noConversion() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            // deal_date=20260612, stlm_date=20260616 → trade_date는 deal_date여야 함
            stubFetch("005930", response(List.of(row("20260612", "20260616"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            ArgumentCaptor<CreditBalance> captor = ArgumentCaptor.forClass(CreditBalance.class);
            verify(creditBalanceRepository).insertIgnoreDuplicate(captor.capture());
            // [HARD] trade_date == deal_date(20260612), NOT stlm_date(20260616) (REQ-052)
            // + 융자/대주 전 필드 만원 무변환(REQ-053)을 단일 assert로 검증
            assertThat(captor.getValue())
                    .extracting(
                            CreditBalance::getTradeDate,
                            CreditBalance::getLoanNewQty,
                            CreditBalance::getLoanRepayQty,
                            CreditBalance::getLoanBalanceQty,
                            CreditBalance::getLoanNewAmt,
                            CreditBalance::getLoanRepayAmt,
                            CreditBalance::getLoanBalanceAmt,
                            CreditBalance::getLoanBalanceRate,
                            CreditBalance::getLoanSupplyRate,
                            CreditBalance::getLendNewQty,
                            CreditBalance::getLendRepayQty,
                            CreditBalance::getLendBalanceQty,
                            CreditBalance::getLendNewAmt,
                            CreditBalance::getLendRepayAmt,
                            CreditBalance::getLendBalanceAmt,
                            CreditBalance::getLendBalanceRate,
                            CreditBalance::getLendSupplyRate)
                    .containsExactly(
                            LocalDate.of(2026, 6, 12),
                            100L,
                            50L,
                            1000L,
                            700L,
                            350L,
                            7000L,
                            new BigDecimal("1.5"),
                            new BigDecimal("2.5"),
                            10L,
                            5L,
                            100L,
                            70L,
                            35L,
                            700L,
                            new BigDecimal("0.5"),
                            new BigDecimal("0.3"));
        }
    }

    @Nested
    @DisplayName("collect — 검증")
    class Validation {

        @Test
        @DisplayName("비율 절댓값 ≥ 1000 행 — 저장 제외 (M-2)")
        void rateOverBoundary_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisCreditBalanceResponse.CreditBalanceRow bad =
                    new KisCreditBalanceResponse.CreditBalanceRow(
                            "20260612",
                            "20260616",
                            "100",
                            "50",
                            "1000",
                            "700",
                            "350",
                            "7000",
                            "1000.0",
                            "2.5",
                            "10",
                            "5",
                            "100",
                            "70",
                            "35",
                            "700",
                            "0.5",
                            "0.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(creditBalanceRepository, never())
                    .insertIgnoreDuplicate(any(CreditBalance.class));
        }

        @Test
        @DisplayName("음수 수량 행 — 저장 제외")
        void negativeQty_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisCreditBalanceResponse.CreditBalanceRow bad =
                    new KisCreditBalanceResponse.CreditBalanceRow(
                            "20260612",
                            "20260616",
                            "-100",
                            "50",
                            "1000",
                            "700",
                            "350",
                            "7000",
                            "1.5",
                            "2.5",
                            "10",
                            "5",
                            "100",
                            "70",
                            "35",
                            "700",
                            "0.5",
                            "0.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(creditBalanceRepository, never())
                    .insertIgnoreDuplicate(any(CreditBalance.class));
        }

        @Test
        @DisplayName("14일 윈도우 밖 행 — 저장 제외")
        void outsideWindow_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(
                    "005930",
                    response(List.of(row("20260520", "20260524"), row("20260612", "20260616"))));

            service.collect(TODAY);

            ArgumentCaptor<CreditBalance> captor = ArgumentCaptor.forClass(CreditBalance.class);
            verify(creditBalanceRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("빈 output — 0건 succeeded (REQ-063)")
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
                            eq(KisCreditBalanceResponse.class),
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
                            eq(KisCreditBalanceResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "테스트 skip"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("null deal_date 행 — 저장 제외, 종목은 succeeded")
        void nullDealDate_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisCreditBalanceResponse.CreditBalanceRow bad =
                    new KisCreditBalanceResponse.CreditBalanceRow(
                            null,
                            "20260616",
                            "100",
                            "50",
                            "1000",
                            "700",
                            "350",
                            "7000",
                            "1.5",
                            "2.5",
                            "10",
                            "5",
                            "100",
                            "70",
                            "35",
                            "700",
                            "0.5",
                            "0.3");
            stubFetch("005930", response(List.of(bad)));

            SupplyDemandResult result = service.collect(TODAY);

            verify(creditBalanceRepository, never())
                    .insertIgnoreDuplicate(any(CreditBalance.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외")
        void unparseableRow_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisCreditBalanceResponse.CreditBalanceRow bad =
                    new KisCreditBalanceResponse.CreditBalanceRow(
                            "20260612",
                            "20260616",
                            "x",
                            "50",
                            "1000",
                            "700",
                            "350",
                            "7000",
                            "1.5",
                            "2.5",
                            "10",
                            "5",
                            "100",
                            "70",
                            "35",
                            "700",
                            "0.5",
                            "0.3");
            stubFetch("005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(creditBalanceRepository, never())
                    .insertIgnoreDuplicate(any(CreditBalance.class));
        }
    }

    @Nested
    @DisplayName("collect — 대상 없음 / 모든 키 죽음")
    class NoTargets {

        @Test
        @DisplayName("활성 종목 없음 — 0/0/0, execute 미호출")
        void noActiveStocks_zero() {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

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
            when(stockRepository.findAllActiveTradable()).thenReturn(stocks);
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            SupplyDemandResult result = service.collect(TODAY);

            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("T3a 회귀 — asset_type 필터 검증 (REQ-BATCH3-024)")
    class AssetTypeFilter {

        @Test
        @DisplayName("findAllActiveTradable()으로 호출 — INDEX 제외는 StockRepository 계층이 보장")
        void collect_callsFindAllActiveTradable() {
            // Arrange
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

            // Act
            service.collect(TODAY);

            // Assert — INDEX 제외 캡슐화 진입점 호출 확인 (INDEX 제외 자체는 StockRepositoryTest가 검증)
            verify(stockRepository).findAllActiveTradable();
        }

        @Test
        @DisplayName("INDEX 종목은 수집 대상 제외, STOCK+ETF만 API 호출")
        void indexStock_excluded_stockEtf_included() {
            // Arrange — 필터 결과로 STOCK+ETF만 반환
            Stock stockRow = stockOf("005930");
            Stock etfRow =
                    Stock.builder()
                            .symbol("069500")
                            .nameKo("KODEX 200")
                            .market(Market.KOSPI)
                            .assetType(AssetType.ETF)
                            .listedDate(LocalDate.of(2015, 1, 1))
                            .build();
            List<Stock> tradableStocks = List.of(stockRow, etfRow);
            when(stockRepository.findAllActiveTradable()).thenReturn(tradableStocks);
            when(distributor.distribute(tradableStocks)).thenReturn(Map.of(ISA, tradableStocks));
            stubFetch("005930", response(List.of(row("20260612", "20260616"))));
            stubFetch("069500", response(List.of(row("20260612", "20260616"))));

            // Act
            SupplyDemandResult result = service.collect(TODAY);

            // Assert — STOCK+ETF 2건 시도, INDEX 없음
            assertThat(result.attempted()).isEqualTo(2);
        }
    }
}
