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

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T08) — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} → {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 이전. 보존 종단 동작·deal_date 매핑·검증을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditBalanceCollectionService 단위 테스트 (게이트 이전)")
class CreditBalanceCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private CreditBalanceInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private CreditBalanceCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new CreditBalanceCollectionService(
                        stockRepository,
                        new CreditBalanceRowMapper(),
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

    private KisCreditBalanceResponse.CreditBalanceRow row(String dealDate, String stlmDate) {
        return new KisCreditBalanceResponse.CreditBalanceRow(
                dealDate, stlmDate, "100", "50", "1000", "700", "350", "7000", "1.5", "2.5", "10",
                "5", "100", "70", "35", "700", "0.5", "0.3");
    }

    private KisCreditBalanceResponse response(
            List<KisCreditBalanceResponse.CreditBalanceRow> rows) {
        return new KisCreditBalanceResponse("0", "MCA00000", "정상", rows);
    }

    private void stubFetch(KisCreditBalanceResponse r) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisCreditBalanceResponse.class)))
                .thenReturn(r);
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
    }

    @Nested
    @DisplayName("collect — 성공 + 매핑 (REQ-051, -052, -053 보존)")
    class CollectSuccess {

        @Test
        @DisplayName("trade_date에 deal_date 매핑(stlm_date 미사용) + 만원 무변환 (AC-6 S6-2/S6-3)")
        @SuppressWarnings("unchecked")
        void success_dealDateMapping_noConversion() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612", "20260616"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            ArgumentCaptor<List<CreditBalance>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            // [HARD] trade_date == deal_date(20260612), NOT stlm_date(20260616) (REQ-052)
            // + 융자/대주 전 필드 만원 무변환(REQ-053)을 단일 assert로 검증
            assertThat(captor.getValue().getFirst())
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
            verify(healthyKeySelector, times(1)).selectHealthy();
        }
    }

    @Nested
    @DisplayName("collect — 검증 (보존)")
    class Validation {

        @Test
        @DisplayName("비율 절댓값 ≥ 1000 행 — 저장 제외 (M-2)")
        void rateOverBoundary_excluded() throws Exception {
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
            stubFetch(response(List.of(bad)));

            service.collect(TODAY);

            verify(inserter, never()).insertBatch(anyList());
        }

        @Test
        @DisplayName("음수 수량 행 — 저장 제외")
        void negativeQty_excluded() throws Exception {
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
            stubFetch(response(List.of(bad)));

            service.collect(TODAY);

            verify(inserter, never()).insertBatch(anyList());
        }

        @Test
        @DisplayName("14일 윈도우 밖 행 — 저장 제외")
        @SuppressWarnings("unchecked")
        void outsideWindow_excluded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260520", "20260524"), row("20260612", "20260616"))));

            service.collect(TODAY);

            ArgumentCaptor<List<CreditBalance>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter, times(1)).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().getTradeDate())
                    .isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("빈 output — 0건 succeeded (REQ-063)")
        void empty_succeeded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of()));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("collect — 종목 skip (AC-6, REQ-KISGATE-009/022 보존)")
    class StockSkip {

        @Test
        @DisplayName("KisTokenIssueException — graceful skip")
        void tokenIssue_skip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("retryable 소진(KisRateLimitException) — skip 집계 (AC-6)")
        void retryableExhausted_counted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("RestClientException 소진 — skip 집계 (AC-6)")
        void restClientException_counted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenThrow(new RestClientException("네트워크"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("InterruptedException — skip 변환(전파 아님) (AC-6, REQ-RETRY-017)")
        void interrupted_skip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisCreditBalanceResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("null deal_date 행 — 저장 제외, 종목은 succeeded")
        void nullDealDate_excluded() throws Exception {
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
            stubFetch(response(List.of(bad)));

            SupplyDemandResult result = service.collect(TODAY);

            verify(inserter, never()).insertBatch(anyList());
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외")
        void unparseableRow_excluded() throws Exception {
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
            stubFetch(response(List.of(bad)));

            service.collect(TODAY);

            verify(inserter, never()).insertBatch(anyList());
        }
    }

    @Nested
    @DisplayName("collect — 대상 없음 / 모든 키 죽음 (AC-5 보존)")
    class NoTargets {

        @Test
        @DisplayName("활성 종목 없음 — 0/0/0, 게이트 미호출")
        void noActiveStocks_zero() throws Exception {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(0);
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }

        @Test
        @DisplayName("빈 스냅샷 — 게이트 0회, 전체 skip (AC-5)")
        void emptySnapshot_skipAll() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            List<Stock> stocks = List.of(s1, s2);
            when(stockRepository.findAllActiveTradable()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            SupplyDemandResult result = service.collect(TODAY);

            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("T3a 회귀 — asset_type 필터 검증 (REQ-BATCH3-024 보존)")
    class AssetTypeFilter {

        @Test
        @DisplayName("findAllActiveTradable()으로 호출 — INDEX 제외는 StockRepository 계층이 보장")
        void collect_callsFindAllActiveTradable() {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

            service.collect(TODAY);

            verify(stockRepository).findAllActiveTradable();
        }

        @Test
        @DisplayName("STOCK+ETF 2건 모두 수집 시도")
        void stockEtf_included() throws Exception {
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
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            stubFetch(response(List.of(row("20260612", "20260616"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(2);
        }
    }
}
