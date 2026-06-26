package com.aaa.collector.stock.fundamental;

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
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Financial;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.enums.PeriodType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T10) — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} → {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 이전. 종목당 연간+분기 2회 호출(게이트 lease 2회)·매핑·검증·집계를 고정한다.
 * 게이트는 credential/symbol 인자를 받지 않으므로 division별(연간→분기) 순차 stub은 {@code thenReturn} 체인으로 표현한다(한 종목의
 * collectStock은 단일 VT에서 순차 실행).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinancialRatioCollectionService 단위 테스트 (게이트 이전)")
class FinancialRatioCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private StockRepository stockRepository;
    @Mock private FinancialRepository financialRepository;
    @Mock private FinancialInserter financialInserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    @Captor private ArgumentCaptor<List<Financial>> inserterCaptor;

    private FinancialRatioCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new FinancialRatioCollectionService(
                        stockRepository,
                        financialRepository,
                        financialInserter,
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

    /** AC-FIN-3 표준 정상 행. */
    private KisFinancialRatioResponse.FinancialRatioRow row(String stacYymm) {
        return new KisFinancialRatioResponse.FinancialRatioRow(
                stacYymm,
                "12.5",
                "0",
                "8.1",
                "15.2",
                "6993.00",
                "57655",
                "71907.00",
                "1200.5",
                "45.3");
    }

    private KisFinancialRatioResponse response(
            List<KisFinancialRatioResponse.FinancialRatioRow> rows) {
        return new KisFinancialRatioResponse("0", "MCA00000", "정상", rows);
    }

    /** 연간·분기 두 호출 모두 동일 응답으로 stub한다. */
    private void stubFetchAll(KisFinancialRatioResponse r) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisFinancialRatioResponse.class)))
                .thenReturn(r);
    }

    /** 연간(첫 호출)=annual, 분기(둘째 호출)=빈 응답으로 stub한다 — 단일 종목 단일 VT 순차 실행 전제. */
    private void stubAnnualThenEmpty(KisFinancialRatioResponse.FinancialRatioRow annualRow)
            throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisFinancialRatioResponse.class)))
                .thenReturn(response(List.of(annualRow)))
                .thenReturn(response(List.of()));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
    }

    @Nested
    @DisplayName("collect — 종목당 연간+분기 2회 호출 (AC-FIN-1/2 보존)")
    class TwoCallsPerStock {

        @Test
        @DisplayName("종목 1개 — 연간+분기 2회 게이트 호출, attempted=2 succeeded=2, selectHealthy 1회")
        void oneStock_twoCalls() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetchAll(response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            verify(guardedKisExecutor, times(2))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class));
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
            // REQ-KISGATE-006a: 종목당 2회 호출이어도 selectHealthy는 배치당 1회
            verify(healthyKeySelector, times(1)).selectHealthy();
        }

        @Test
        @DisplayName("연간 응답=ANNUAL, 분기 응답=QUARTERLY 분리 저장")
        void periodTypeSplit() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetchAll(response(List.of(row("202403"))));

            service.collect();

            verify(financialInserter, times(2)).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .extracting(Financial::getPeriodType)
                    .containsExactlyInAnyOrder(PeriodType.ANNUAL, PeriodType.QUARTERLY);
        }
    }

    @Nested
    @DisplayName("collect — 필드 매핑 (AC-FIN-3/9 보존)")
    class Mapping {

        private Financial captureAnnualSaved(KisFinancialRatioResponse.FinancialRatioRow annualRow)
                throws InterruptedException {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubAnnualThenEmpty(annualRow);

            service.collect();

            verify(financialInserter, times(1)).insertBatch(inserterCaptor.capture());
            return inserterCaptor.getAllValues().stream()
                    .flatMap(List::stream)
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("AC-FIN-3: period_date 변환 + BIGINT .00 무손실 정수 변환 (AC-FIN-9)")
        void mapsPeriodDateAndBigInt() throws Exception {
            Financial saved = captureAnnualSaved(row("202403"));

            assertThat(saved)
                    .extracting(
                            Financial::getPeriodDate,
                            Financial::getEps,
                            Financial::getSps,
                            Financial::getBps)
                    .containsExactly(LocalDate.of(2024, 3, 1), 6993L, 57_655L, 71_907L);
        }

        @Test
        @DisplayName("AC-FIN-3: 증가율/ROE DECIMAL 매핑 + 영업이익 0 표기 보존")
        void mapsGrowthAndRoe() throws Exception {
            Financial saved = captureAnnualSaved(row("202403"));

            assertThat(saved.getRevenueGrowth()).isEqualByComparingTo("12.5");
            assertThat(saved.getOperatingProfitGrowth()).isEqualByComparingTo("0");
            assertThat(saved.getNetIncomeGrowth()).isEqualByComparingTo("8.1");
            assertThat(saved.getRoe()).isEqualByComparingTo("15.2");
        }

        @Test
        @DisplayName("AC-FIN-3: 유보비율 1200.5(>1000) 정상 저장(1000 미적용) + 부채비율 매핑")
        void mapsRatioFields() throws Exception {
            Financial saved = captureAnnualSaved(row("202403"));

            assertThat(saved.getRetentionRate()).isEqualByComparingTo("1200.5");
            assertThat(saved.getDebtRatio()).isEqualByComparingTo("45.3");
        }

        @Test
        @DisplayName("AC-FIN-6: 음수 증가율·ROE 정상 저장 (부호 무거부)")
        void negativeGrowth_stored() throws Exception {
            Financial saved =
                    captureAnnualSaved(
                            new KisFinancialRatioResponse.FinancialRatioRow(
                                    "202403",
                                    "-30.5",
                                    "0",
                                    "-12.0",
                                    "-5.5",
                                    "6993.00",
                                    "57655",
                                    "71907.00",
                                    "1200.5",
                                    "45.3"));

            assertThat(saved.getRevenueGrowth()).isEqualByComparingTo("-30.5");
            assertThat(saved.getNetIncomeGrowth()).isEqualByComparingTo("-12.0");
            assertThat(saved.getRoe()).isEqualByComparingTo("-5.5");
        }

        @Test
        @DisplayName("AC-FIN-8: 소수 5자리 응답은 skip 아님 — setScale 없이 저장")
        void fiveDecimalPlaces_notSkipped() throws Exception {
            Financial saved =
                    captureAnnualSaved(
                            new KisFinancialRatioResponse.FinancialRatioRow(
                                    "202403",
                                    "12.5",
                                    "0",
                                    "8.1",
                                    "15.23456",
                                    "6993.00",
                                    "57655",
                                    "71907.00",
                                    "1200.5",
                                    "45.3"));

            assertThat(saved.getRoe()).isEqualByComparingTo("15.23456");
        }
    }

    @Nested
    @DisplayName("collect — 다건 응답 (AC-FIN-5 보존)")
    class MultiRow {

        @Test
        @DisplayName("한 호출 output 3행 — 전부 저장 (연간 3 + 분기 빈)")
        void multipleRows_allStored() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenReturn(response(List.of(row("202403"), row("202312"), row("202212"))))
                    .thenReturn(response(List.of()));

            service.collect();

            // 3행 모두 단일 배치 INSERT (AC-4 배치 통합, REQ-INSERT-009)
            verify(financialInserter, times(1)).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collect — 검증 건별 skip (AC-FIN-7/9, AC-VAL-1 보존)")
    class Validation {

        @Test
        @DisplayName("AC-FIN-7: DECIMAL(12,4) 정수부 경계 |value|>=10^8 초과 — skip")
        void decimalIntegerBoundExceeded_skipped() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubAnnualThenEmpty(
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202403",
                            "100000000.0",
                            "0",
                            "8.1",
                            "15.2",
                            "6993.00",
                            "57655",
                            "71907.00",
                            "1200.5",
                            "45.3"));

            service.collect();

            verify(financialInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("AC-FIN-9: BIGINT 비0 소수부(6993.50) — skip (ArithmeticException)")
        void bigIntNonZeroFraction_skipped() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubAnnualThenEmpty(
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202403",
                            "12.5",
                            "0",
                            "8.1",
                            "15.2",
                            "6993.50",
                            "57655",
                            "71907.00",
                            "1200.5",
                            "45.3"));

            service.collect();

            verify(financialInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — skip, 같은 응답 정상 행은 저장")
        void unparseableRow_skipped_othersStored() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisFinancialRatioResponse.FinancialRatioRow bad =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202312", "x", "y", "z", "w", "v", "u", "t", "s", "r");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenReturn(response(List.of(bad, row("202403"))))
                    .thenReturn(response(List.of()));

            service.collect();

            verify(financialInserter, times(1)).insertBatch(inserterCaptor.capture());
            Financial capturedEntity =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(capturedEntity.getPeriodDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        }

        @Test
        @DisplayName("MI-1: eps=null 행 — NPE 아닌 건별 skip, 같은 응답 정상 행은 저장 (AC-VAL-4)")
        void nullNumericField_skippedPerRow_otherRowsStored() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisFinancialRatioResponse.FinancialRatioRow nullEps =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202312",
                            "12.5",
                            "0",
                            "8.1",
                            "15.2",
                            null,
                            "57655",
                            "71907.00",
                            "1200.5",
                            "45.3");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenReturn(response(List.of(nullEps, row("202403"))))
                    .thenReturn(response(List.of()));

            FundamentalResult result = service.collect();

            verify(financialInserter, times(1)).insertBatch(any());
            assertThat(result.attempted()).isEqualTo(result.succeeded() + result.skipped());
        }

        @Test
        @DisplayName("stac_yymm null 행 — skip")
        void nullStacYymm_skipped() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubAnnualThenEmpty(
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            null,
                            "12.5",
                            "0",
                            "8.1",
                            "15.2",
                            "6993.00",
                            "57655",
                            "71907.00",
                            "1200.5",
                            "45.3"));

            service.collect();

            verify(financialInserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collect — 집계 / skip / 빈 결과 (AC-VAL-2/3/4 보존, AC-6)")
    class Aggregation {

        @Test
        @DisplayName("AC-VAL-3: 빈 output — 0건 succeeded (skip 아님)")
        void emptyOutput_zeroRowsSucceeded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetchAll(response(List.of()));

            FundamentalResult result = service.collect();

            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
            verify(financialInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("retryable 소진(KisRateLimitException) — division 단위 skip 집계 (AC-6)")
        void retryableExhausted_counted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"))
                    .thenReturn(response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
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
                            eq(KisFinancialRatioResponse.class)))
                    .thenThrow(new RestClientException("네트워크"))
                    .thenReturn(response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("InterruptedException — division skip 변환(전파 아님) (AC-6, REQ-RETRY-017)")
        void interrupted_skip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"))
                    .thenReturn(response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-VAL-2: KisTokenIssueException — graceful skip, 배치 미실패")
        void tokenIssue_gracefulSkip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            FundamentalResult result = service.collect();

            assertThat(result.skipped()).isEqualTo(2);
        }

        @Test
        @DisplayName("attempted = succeeded + skipped 관계 성립 (2종목 × 2회)")
        void attemptedEqualsSum() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(s1, s2));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            stubFetchAll(response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(result.succeeded() + result.skipped());
            assertThat(result.attempted()).isEqualTo(4);
        }

        @Test
        @DisplayName("활성 STOCK 없음 — 0/0/0, 게이트 미호출")
        void noActiveStock_zero() throws Exception {
            when(stockRepository.findAllActiveStock()).thenReturn(List.of());

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(0);
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-5, REQ-KISGATE-024 보존)")
    class AllKeysDead {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            serviceLogger = (Logger) LoggerFactory.getLogger(FinancialRatioCollectionService.class);
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
        @DisplayName("빈 스냅샷 — 게이트 0회, 전체 skip(2), ERROR 1회 (AC-5)")
        void emptySnapshot_skipAll_errorLog() throws Exception {
            Stock s1 = stockOf("005930");
            List<Stock> stocks = List.of(s1);
            when(stockRepository.findAllActiveStock()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            FundamentalResult result = service.collect();

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
    @DisplayName("collect — STOCK-only 대상 (AC-PATH-4 보존)")
    class StockOnly {

        @Test
        @DisplayName("findAllActiveStock()으로 조회 — STOCK-only 캡슐화 진입점 호출")
        void usesFindAllActiveStock() {
            when(stockRepository.findAllActiveStock()).thenReturn(List.of());

            service.collect();

            verify(stockRepository).findAllActiveStock();
            verify(stockRepository, never()).findAllActive();
            verify(stockRepository, never()).findAllActiveTradable();
        }
    }

    @Nested
    @DisplayName("collect — 행 단위 집계 관측성 (MI-01 보존)")
    class RowTally {

        @Test
        @DisplayName("MI-01: 혼합 응답(유효 1행 + 파싱실패 1행) — insertIgnoreDuplicate 1회")
        void mixedResponse_savedAndSkippedCounted() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisFinancialRatioResponse.FinancialRatioRow invalidRow =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202312", "x", "y", "z", "w", "v", "u", "t", "s", "r");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class)))
                    .thenReturn(response(List.of(invalidRow, row("202403"))))
                    .thenReturn(response(List.of()));

            service.collect();

            verify(financialInserter, times(1)).insertBatch(any());
        }
    }
}
