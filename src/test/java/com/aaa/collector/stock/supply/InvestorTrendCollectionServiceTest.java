package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.InvestorTrend;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestorTrendCollectionService 단위 테스트")
class InvestorTrendCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private InvestorTrendRepository investorTrendRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private InvestorTrendCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new InvestorTrendCollectionService(
                        stockRepository, investorTrendRepository, batchRestExecutor, distributor);
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

    private void stubFetch(KisAccountCredential cred, String symbol, KisInvestorTrendResponse r) {
        when(batchRestExecutor.execute(
                        eq(cred),
                        any(),
                        anyString(),
                        eq(KisInvestorTrendResponse.class),
                        eq(symbol)))
                .thenReturn(BatchResult.success(r));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
    }

    @Nested
    @DisplayName("collect — 성공 경로 + 집계")
    class CollectSuccess {

        @Test
        @DisplayName("활성 종목 1개 — 시도=1, 성공=1, skip=0")
        void oneActiveStock_success() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
        }

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
        @DisplayName("종목 2개, 키 2개 — 시도=2, 성공=2")
        void twoStocks_aggregates() {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));
            stubFetch(ISA, "005930", response(List.of(row("20260612"))));
            stubFetch(GOLD, "000660", response(List.of(row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("collect — 매핑 / 단위 변환 (REQ-031, -032, -033, -034)")
    class Mapping {

        @Test
        @DisplayName("누적 거래대금·순매수 거래대금 백만원→원 ×1,000,000 변환 (REQ-033, AC-4 S4-2)")
        void valuesConvertedToWon() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260612"))));

            service.collect(TODAY);

            ArgumentCaptor<InvestorTrend> captor = ArgumentCaptor.forClass(InvestorTrend.class);
            verify(investorTrendRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue())
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
    @DisplayName("collect — 검증 건별 skip (REQ-060, -063)")
    class Validation {

        @Test
        @DisplayName("순매수 수량·거래대금 음수는 정상 허용 — 저장됨 (R-F)")
        void negativeNetValues_allowed_inserted() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260612"))));

            service.collect(TODAY);

            verify(investorTrendRepository, times(1))
                    .insertIgnoreDuplicate(any(InvestorTrend.class));
        }

        @Test
        @DisplayName("총 거래량 음수 행 — 저장 제외 (음수 비정상 컬럼)")
        void negativeTotalVolume_excluded() {
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
            stubFetch(ISA, "005930", response(List.of(bad)));

            service.collect(TODAY);

            verify(investorTrendRepository, never())
                    .insertIgnoreDuplicate(any(InvestorTrend.class));
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외, 같은 종목 다음 행 계속")
        void unparseableRow_excluded_othersContinue() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestorTrendResponse.InvestorTrendRow bad =
                    new KisInvestorTrendResponse.InvestorTrendRow(
                            "20260611", "x", "y", "z", "a", "b", "c", "d", "e");
            stubFetch(ISA, "005930", response(List.of(bad, row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            ArgumentCaptor<InvestorTrend> captor = ArgumentCaptor.forClass(InvestorTrend.class);
            verify(investorTrendRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("14일 윈도우 밖 행 — 저장 제외")
        void rowOutsideWindow_excluded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            // 윈도우 시작일 = 2026-05-30. 2026-05-20은 윈도우 밖
            stubFetch(ISA, "005930", response(List.of(row("20260520"), row("20260612"))));

            service.collect(TODAY);

            ArgumentCaptor<InvestorTrend> captor = ArgumentCaptor.forClass(InvestorTrend.class);
            verify(investorTrendRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("빈 output2 — 0건 succeeded (skip 아님, REQ-063)")
        void emptyOutput2_zeroRowsSucceeded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of()));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            verify(investorTrendRepository, never())
                    .insertIgnoreDuplicate(any(InvestorTrend.class));
        }
    }

    @Nested
    @DisplayName("collect — 종목 단위 graceful skip (REQ-061, -012)")
    class StockSkip {

        @Test
        @DisplayName("BatchResult.skip — skip 집계")
        void batchSkip_counted() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "테스트 skip"));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패")
        void tokenIssue_gracefulSkip() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class),
                            eq("005930")))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 경계 커버리지 WARN (REQ-025, AC-3 S3-4a/b/c)")
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
        void minCoversBottom_noWarn() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260528"), row("20260612"))));

            service.collect(TODAY);

            assertThat(warns()).isEmpty();
        }

        @Test
        @DisplayName("S3-4b: 최소 trade_date(2026-06-02) > 윈도우 시작일(2026-05-30) — WARN 1건, 반환분 저장")
        void minMissesBottom_warnAndStillSaves() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260602"), row("20260612"))));

            SupplyDemandResult result = service.collect(TODAY);

            assertThat(warns()).hasSize(1);
            // 반환분은 정상 멱등 저장 (수집 미중단)
            verify(investorTrendRepository, times(2))
                    .insertIgnoreDuplicate(any(InvestorTrend.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("S3-4c: tail-gap(하단 2026-05-28 커버, 최근 일자 빠짐) — WARN 미발생")
        void tailGap_noWarn() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            // 하단 커버(2026-05-28), 최근 일자(06-12,06-13) 없음 — tail-gap은 WARN 대상 아님
            stubFetch(ISA, "005930", response(List.of(row("20260528"), row("20260605"))));

            service.collect(TODAY);

            assertThat(warns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (REQ-KEYDIST-020)")
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
        @DisplayName("빈 할당 — execute 0회, 전체 skip, ERROR 로그 1회")
        void emptyAllocation_skipAll_errorLog() {
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
            List<ILoggingEvent> errors =
                    appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
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
            stubFetch(ISA, "005930", response(List.of(row("20260612"))));
            stubFetch(ISA, "069500", response(List.of(row("20260612"))));

            // Act
            SupplyDemandResult result = service.collect(TODAY);

            // Assert — STOCK+ETF 2건 시도, INDEX 없음
            assertThat(result.attempted()).isEqualTo(2);
        }
    }
}
