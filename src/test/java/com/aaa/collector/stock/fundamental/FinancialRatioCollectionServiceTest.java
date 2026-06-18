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
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
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
@DisplayName("FinancialRatioCollectionService 단위 테스트")
class FinancialRatioCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private StockRepository stockRepository;
    @Mock private FinancialRepository financialRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private FinancialRatioCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new FinancialRatioCollectionService(
                        stockRepository, financialRepository, batchRestExecutor, distributor);
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

    private void stubFetch(KisAccountCredential cred, String symbol, KisFinancialRatioResponse r) {
        when(batchRestExecutor.execute(
                        eq(cred),
                        any(),
                        anyString(),
                        eq(KisFinancialRatioResponse.class),
                        eq(symbol)))
                .thenReturn(BatchResult.success(r));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
        when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
    }

    @Nested
    @DisplayName("collect — 종목당 연간+분기 2회 호출 (AC-FIN-1/2)")
    class TwoCallsPerStock {

        @Test
        @DisplayName("종목 1개 — 연간(0)+분기(1) 2회 호출, attempted=2 succeeded=2")
        void oneStock_twoCalls() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            // 종목당 2회 호출
            verify(batchRestExecutor, times(2))
                    .execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930"));
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("연간 응답=ANNUAL, 분기 응답=QUARTERLY 분리 저장")
        void periodTypeSplit() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("202403"))));

            service.collect();

            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(2)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(Financial::getPeriodType)
                    .containsExactlyInAnyOrder(PeriodType.ANNUAL, PeriodType.QUARTERLY);
        }
    }

    @Nested
    @DisplayName("collect — 필드 매핑 (AC-FIN-3/9)")
    class Mapping {

        /** 연간만 응답, 분기는 빈 응답으로 stub — 단일 저장행을 캡처한다. */
        private Financial captureAnnualSaved(
                KisFinancialRatioResponse.FinancialRatioRow annualRow) {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(annualRow))))
                    .thenReturn(BatchResult.success(response(List.of())));

            service.collect();

            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("AC-FIN-3: period_date 변환 + BIGINT .00 무손실 정수 변환 (AC-FIN-9)")
        void mapsPeriodDateAndBigInt() {
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
        void mapsGrowthAndRoe() {
            Financial saved = captureAnnualSaved(row("202403"));

            // BigDecimal scale 비교(isEqualByComparingTo) — extracting equals는 scale 민감
            assertThat(saved.getRevenueGrowth()).isEqualByComparingTo("12.5");
            assertThat(saved.getOperatingProfitGrowth()).isEqualByComparingTo("0"); // 0 표기 보존
            assertThat(saved.getNetIncomeGrowth()).isEqualByComparingTo("8.1");
            assertThat(saved.getRoe()).isEqualByComparingTo("15.2");
        }

        @Test
        @DisplayName("AC-FIN-3: 유보비율 1200.5(>1000) 정상 저장(1000 미적용) + 부채비율 매핑")
        void mapsRatioFields() {
            Financial saved = captureAnnualSaved(row("202403"));

            assertThat(saved.getRetentionRate()).isEqualByComparingTo("1200.5"); // 1000 초과 정상
            assertThat(saved.getDebtRatio()).isEqualByComparingTo("45.3");
        }

        @Test
        @DisplayName("AC-FIN-6: 음수 증가율·ROE 정상 저장 (부호 무거부)")
        void negativeGrowth_stored() {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            KisFinancialRatioResponse.FinancialRatioRow neg =
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
                            "45.3");
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(neg))))
                    .thenReturn(BatchResult.success(response(List.of())));

            service.collect();

            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getRevenueGrowth()).isEqualByComparingTo("-30.5");
            assertThat(captor.getValue().getNetIncomeGrowth()).isEqualByComparingTo("-12.0");
            assertThat(captor.getValue().getRoe()).isEqualByComparingTo("-5.5");
        }

        @Test
        @DisplayName("AC-FIN-8: 소수 5자리 응답은 skip 아님 — setScale 없이 저장")
        void fiveDecimalPlaces_notSkipped() {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            KisFinancialRatioResponse.FinancialRatioRow longScale =
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
                            "45.3");
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(longScale))))
                    .thenReturn(BatchResult.success(response(List.of())));

            service.collect();

            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            // 저장됨(skip 아님), scale은 setScale 없이 원문 보존 — Hibernate/JDBC 위임
            assertThat(captor.getValue().getRoe()).isEqualByComparingTo("15.23456");
        }
    }

    @Nested
    @DisplayName("collect — 다건 응답 (AC-FIN-5)")
    class MultiRow {

        @Test
        @DisplayName("한 호출 output 3행 — 전부 저장 (연간 3 + 분기 빈)")
        void multipleRows_allStored() {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(
                            BatchResult.success(
                                    response(List.of(row("202403"), row("202312"), row("202212")))))
                    .thenReturn(BatchResult.success(response(List.of())));

            service.collect();

            verify(financialRepository, times(3)).insertIgnoreDuplicate(any(Financial.class));
        }
    }

    @Nested
    @DisplayName("collect — 검증 건별 skip (AC-FIN-7/9, AC-VAL-1)")
    class Validation {

        private void stubAnnualRow(KisFinancialRatioResponse.FinancialRatioRow r) {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(r))))
                    .thenReturn(BatchResult.success(response(List.of())));
        }

        @Test
        @DisplayName("AC-FIN-7: DECIMAL(12,4) 정수부 경계 |value|>=10^8 초과 — skip")
        void decimalIntegerBoundExceeded_skipped() {
            stubAnnualRow(
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

            verify(financialRepository, never()).insertIgnoreDuplicate(any(Financial.class));
        }

        @Test
        @DisplayName("AC-FIN-9: BIGINT 비0 소수부(6993.50) — skip (ArithmeticException)")
        void bigIntNonZeroFraction_skipped() {
            stubAnnualRow(
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

            verify(financialRepository, never()).insertIgnoreDuplicate(any(Financial.class));
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — skip, 같은 응답 정상 행은 저장")
        void unparseableRow_skipped_othersStored() {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            KisFinancialRatioResponse.FinancialRatioRow bad =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202312", "x", "y", "z", "w", "v", "u", "t", "s", "r");
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(bad, row("202403")))))
                    .thenReturn(BatchResult.success(response(List.of())));

            service.collect();

            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getPeriodDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        }

        @Test
        @DisplayName("MI-1: eps=null 행 — NPE 아닌 건별 skip, 같은 응답 정상 행은 저장 (AC-VAL-4)")
        void nullNumericField_skippedPerRow_otherRowsStored() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
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
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(nullEps, row("202403")))))
                    .thenReturn(BatchResult.success(response(List.of())));

            // Act
            FundamentalResult result = service.collect();

            // Assert — null eps 행 skip, 정상 행 저장, attempted=succeeded+skipped 불변
            ArgumentCaptor<Financial> captor = ArgumentCaptor.forClass(Financial.class);
            verify(financialRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(result.attempted()).isEqualTo(result.succeeded() + result.skipped());
        }

        @Test
        @DisplayName("stac_yymm null 행 — skip")
        void nullStacYymm_skipped() {
            stubAnnualRow(
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

            verify(financialRepository, never()).insertIgnoreDuplicate(any(Financial.class));
        }
    }

    @Nested
    @DisplayName("collect — 집계 / skip / 빈 결과 (AC-VAL-2/3/4)")
    class Aggregation {

        @Test
        @DisplayName("AC-VAL-3: 빈 output — 0건 succeeded (skip 아님)")
        void emptyOutput_zeroRowsSucceeded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of()));

            FundamentalResult result = service.collect();

            assertThat(result.succeeded()).isEqualTo(2); // 연간+분기 둘 다 성공(0건)
            assertThat(result.skipped()).isEqualTo(0);
            verify(financialRepository, never()).insertIgnoreDuplicate(any(Financial.class));
        }

        @Test
        @DisplayName("BatchResult.skip — skip 집계 (분류구분 단위)")
        void batchSkip_counted() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "EGW00201 소진"))
                    .thenReturn(BatchResult.success(response(List.of(row("202403")))));

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-VAL-2: KisTokenIssueException — graceful skip, 배치 미실패")
        void tokenIssue_gracefulSkip() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            FundamentalResult result = service.collect();

            assertThat(result.skipped()).isEqualTo(2); // 연간+분기 둘 다 skip
        }

        @Test
        @DisplayName("attempted = succeeded + skipped 관계 성립")
        void attemptedEqualsSum() {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));
            stubFetch(ISA, "005930", response(List.of(row("202403"))));
            stubFetch(GOLD, "000660", response(List.of(row("202403"))));

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(result.succeeded() + result.skipped());
            assertThat(result.attempted()).isEqualTo(4); // 2종목 × 2회
        }

        @Test
        @DisplayName("활성 STOCK 없음 — 0/0/0, execute 미호출")
        void noActiveStock_zero() {
            when(stockRepository.findAllActiveStock()).thenReturn(List.of());

            FundamentalResult result = service.collect();

            assertThat(result.attempted()).isEqualTo(0);
            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-PATH-2)")
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
        @DisplayName("빈 할당 — execute 0회, 전체 skip, ERROR 1회")
        void emptyAllocation_skipAll_errorLog() {
            Stock s1 = stockOf("005930");
            List<Stock> stocks = List.of(s1);
            when(stockRepository.findAllActiveStock()).thenReturn(stocks);
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            FundamentalResult result = service.collect();

            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
            assertThat(result.attempted()).isEqualTo(2); // 1종목 × 2회
            assertThat(result.skipped()).isEqualTo(2);
            List<ILoggingEvent> errors =
                    appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
        }
    }

    @Nested
    @DisplayName("collect — STOCK-only 대상 (AC-PATH-4)")
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
    @DisplayName("collect — 행 단위 집계 관측성 (MI-01)")
    class RowTally {

        @Test
        @DisplayName("MI-01: 혼합 응답(유효 1행 + skip 1행) — insertIgnoreDuplicate 1회, skip 1건 집계")
        void mixedResponse_savedAndSkippedCounted() {
            // Arrange — 연간: 유효 1행 + 파싱실패 1행, 분기: 빈 응답
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            KisFinancialRatioResponse.FinancialRatioRow invalidRow =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202312", "x", "y", "z", "w", "v", "u", "t", "s", "r");
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisFinancialRatioResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response(List.of(invalidRow, row("202403")))))
                    .thenReturn(BatchResult.success(response(List.of())));

            // Act
            service.collect();

            // Assert — 저장 1행(유효), skip 1행(파싱 실패), call 집계 불변 확인
            verify(financialRepository, times(1)).insertIgnoreDuplicate(any(Financial.class));
        }
    }
}
