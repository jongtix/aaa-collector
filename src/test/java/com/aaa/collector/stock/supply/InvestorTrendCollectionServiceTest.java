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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T07) — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} → {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 이전. 보존 종단 동작·매핑·검증·경계 커버리지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestorTrendCollectionService 단위 테스트 (게이트 이전)")
class InvestorTrendCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private InvestorTrendInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private InvestorTrendCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new InvestorTrendCollectionService(
                        stockRepository,
                        inserter,
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        new BackfillWindowAdvancer(150, 10));
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

    private KisInvestorTrendResponse.InvestorTrendRow row(String date) {
        return new KisInvestorTrendResponse.InvestorTrendRow(
                date, "1000", "-2000", "3000", "7500", "-15000", "22500", "5000000", "375000");
    }

    private KisInvestorTrendResponse response(
            List<KisInvestorTrendResponse.InvestorTrendRow> rows) {
        return new KisInvestorTrendResponse("0", "MCA00000", "정상", rows);
    }

    private void stubFetch(KisInvestorTrendResponse r) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisInvestorTrendResponse.class)))
                .thenReturn(r);
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
    }

    @Nested
    @DisplayName("collect — 성공 경로 + 집계")
    class CollectSuccess {

        @Test
        @DisplayName("활성 종목 1개 — 시도=1, 성공=1, skip=0, selectHealthy 1회")
        void oneActiveStock_success() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            verify(healthyKeySelector, times(1)).selectHealthy();
        }

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
        @DisplayName("종목 2개 — 시도=2, 성공=2, selectHealthy 1회")
        void twoStocks_aggregates() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(s1, s2));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            stubFetch(response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            verify(healthyKeySelector, times(1)).selectHealthy();
        }
    }

    @Nested
    @DisplayName("collect — 매핑 / 단위 변환 (REQ-031~034 보존)")
    class Mapping {

        @Test
        @DisplayName("누적 거래대금·순매수 거래대금 백만원→원 ×1,000,000 변환 (REQ-033, AC-4 S4-2)")
        @SuppressWarnings("unchecked")
        void valuesConvertedToWon() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612"))));

            service.collect(TODAY);

            ArgumentCaptor<List<InvestorTrend>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst())
                    .extracting(
                            InvestorTrend::getTradeDate,
                            InvestorTrend::getForeignNetQty,
                            InvestorTrend::getInstitutionNetQty,
                            InvestorTrend::getIndividualNetQty,
                            InvestorTrend::getForeignNetValue,
                            InvestorTrend::getInstitutionNetValue,
                            InvestorTrend::getIndividualNetValue,
                            InvestorTrend::getTotalVolume,
                            InvestorTrend::getTotalTradingValue)
                    .containsExactly(
                            LocalDate.of(2026, 6, 12),
                            1000L,
                            -2000L,
                            3000L,
                            7_500_000_000L,
                            -15_000_000_000L,
                            22_500_000_000L,
                            5_000_000L,
                            375_000_000_000L);
        }
    }

    @Nested
    @DisplayName("collect — 검증 건별 skip (REQ-060, -063 보존)")
    class Validation {

        @Test
        @DisplayName("순매수 수량·거래대금 음수는 정상 허용 — 저장됨 (R-F)")
        void negativeNetValues_allowed_inserted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612"))));

            service.collect(TODAY);

            verify(inserter, times(1)).insertBatch(anyList());
        }

        @Test
        @DisplayName("총 거래량 음수 행 — 저장 제외 (음수 비정상 컬럼)")
        void negativeTotalVolume_excluded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestorTrendResponse.InvestorTrendRow bad =
                    new KisInvestorTrendResponse.InvestorTrendRow(
                            "20260612",
                            "1000",
                            "-2000",
                            "3000",
                            "7500",
                            "-15000",
                            "22500",
                            "-5000000",
                            "375000");
            stubFetch(response(List.of(bad)));

            service.collect(TODAY);

            verify(inserter, never()).insertBatch(anyList());
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외, 같은 종목 다음 행 계속")
        @SuppressWarnings("unchecked")
        void unparseableRow_excluded_othersContinue() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestorTrendResponse.InvestorTrendRow bad =
                    new KisInvestorTrendResponse.InvestorTrendRow(
                            "20260611", "x", "y", "z", "a", "b", "c", "d", "e");
            stubFetch(response(List.of(bad, row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            ArgumentCaptor<List<InvestorTrend>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter, times(1)).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().getTradeDate())
                    .isEqualTo(LocalDate.of(2026, 6, 12));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("14일 윈도우 밖 행 — 저장 제외")
        @SuppressWarnings("unchecked")
        void rowOutsideWindow_excluded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260520"), row("20260612"))));

            service.collect(TODAY);

            ArgumentCaptor<List<InvestorTrend>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter, times(1)).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().getTradeDate())
                    .isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("빈 output2 — 0건 succeeded (skip 아님, REQ-063)")
        void emptyOutput2_zeroRowsSucceeded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of()));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            verify(inserter, never()).insertBatch(anyList());
        }
    }

    @Nested
    @DisplayName("collect — 종목 단위 graceful skip (AC-6, REQ-KISGATE-009/022 보존)")
    class StockSkip {

        @Test
        @DisplayName("retryable 소진(KisRateLimitException) — skip 집계 (AC-6)")
        void retryableExhausted_counted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
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
                            eq(KisInvestorTrendResponse.class)))
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
                            eq(KisInvestorTrendResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패")
        void tokenIssue_gracefulSkip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 경계 커버리지 WARN (REQ-025 보존)")
    class WindowCoverage {

        private Logger checkerLogger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            checkerLogger = (Logger) LoggerFactory.getLogger(WindowCoverageChecker.class);
            appender = new ListAppender<>();
            appender.start();
            checkerLogger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            checkerLogger.detachAppender(appender);
            appender.stop();
        }

        private List<ILoggingEvent> warns() {
            return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        }

        @Test
        @DisplayName("S3-4a: 최소 trade_date(2026-05-28) ≤ 윈도우 시작일(2026-05-30) — WARN 미발생")
        void minCoversBottom_noWarn() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260528"), row("20260612"))));

            service.collect(TODAY);

            assertThat(warns()).isEmpty();
        }

        @Test
        @DisplayName("S3-4b: 최소 trade_date(2026-06-02) > 윈도우 시작일(2026-05-30) — WARN 1건, 반환분 저장")
        @SuppressWarnings("unchecked")
        void minMissesBottom_warnAndStillSaves() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260602"), row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(warns()).hasSize(1);
            // 반환분은 정상 멱등 저장 (수집 미중단)
            ArgumentCaptor<List<InvestorTrend>> captor = ArgumentCaptor.forClass(List.class);
            verify(inserter, times(1)).insertBatch(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("S3-4c: tail-gap(하단 2026-05-28 커버, 최근 일자 빠짐) — WARN 미발생")
        void tailGap_noWarn() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260528"), row("20260605"))));

            service.collect(TODAY);

            assertThat(warns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-5, REQ-KISGATE-024 보존)")
    class AllKeysDead {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            serviceLogger = (Logger) LoggerFactory.getLogger(InvestorTrendCollectionService.class);
            appender = new ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("빈 스냅샷 — 게이트 0회, 전체 skip, ERROR 로그 1회 (AC-5)")
        void emptySnapshot_skipAll_errorLog() throws Exception {
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
            List<ILoggingEvent> errors =
                    appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
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
            stubFetch(response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(2);
        }
    }
}
