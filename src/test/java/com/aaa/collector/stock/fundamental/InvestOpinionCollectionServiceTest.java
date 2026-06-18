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
import com.aaa.collector.stock.AnalystEstimate;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestOpinionCollectionService 단위 테스트")
class InvestOpinionCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Mock private StockRepository stockRepository;
    @Mock private AnalystEstimateRepository analystEstimateRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private InvestOpinionCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new InvestOpinionCollectionService(
                        stockRepository, analystEstimateRepository, batchRestExecutor, distributor);
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

    private KisInvestOpinionResponse.InvestOpinionRow row(String date, String institution) {
        return new KisInvestOpinionResponse.InvestOpinionRow(
                date,
                "매수",
                "2",
                "중립",
                "3",
                institution,
                "95000",
                "82000",
                "13000",
                "15.85",
                "500",
                "0.61");
    }

    private KisInvestOpinionResponse response(
            List<KisInvestOpinionResponse.InvestOpinionRow> rows) {
        return new KisInvestOpinionResponse("0", "MCA00000", "정상", rows);
    }

    private void stubFetch(KisAccountCredential cred, String symbol, KisInvestOpinionResponse r) {
        when(batchRestExecutor.execute(
                        eq(cred),
                        any(),
                        anyString(),
                        eq(KisInvestOpinionResponse.class),
                        eq(symbol)))
                .thenReturn(BatchResult.success(r));
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
        when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
    }

    @Nested
    @DisplayName("collect — 매핑 (AC-OPN-2/3)")
    class Mapping {

        /** 단일 행 응답을 stub하고 저장된 엔티티를 캡처한다. */
        private AnalystEstimate captureSaved(KisInvestOpinionResponse.InvestOpinionRow inputRow) {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(inputRow)));

            service.collect(TODAY);

            ArgumentCaptor<AnalystEstimate> captor = ArgumentCaptor.forClass(AnalystEstimate.class);
            verify(analystEstimateRepository).insertIgnoreDuplicate(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("AC-OPN-2: 비-BigDecimal 8개 필드 매핑 + BIGINT 정수 변환")
        void mapsNonDecimalFields() {
            AnalystEstimate saved = captureSaved(row("20260612", "OO증권"));

            assertThat(saved)
                    .extracting(
                            AnalystEstimate::getTradeDate,
                            AnalystEstimate::getOpinion,
                            AnalystEstimate::getOpinionCode,
                            AnalystEstimate::getPrevOpinion,
                            AnalystEstimate::getPrevOpinionCode,
                            AnalystEstimate::getInstitutionName,
                            AnalystEstimate::getTargetPrice,
                            AnalystEstimate::getPrevClose)
                    .containsExactly(
                            LocalDate.of(2026, 6, 12),
                            "매수",
                            "2",
                            "중립",
                            "3",
                            "OO증권",
                            95_000L,
                            82_000L);
        }

        @Test
        @DisplayName("AC-OPN-2: 괴리도/괴리율 4개 DECIMAL 필드 매핑")
        void mapsGapFields() {
            AnalystEstimate saved = captureSaved(row("20260612", "OO증권"));

            // BigDecimal scale 비교(isEqualByComparingTo)
            assertThat(saved.getGapNDay()).isEqualByComparingTo("13000");
            assertThat(saved.getGapRateNDay()).isEqualByComparingTo("15.85");
            assertThat(saved.getGapFutures()).isEqualByComparingTo("500");
            assertThat(saved.getGapRateFutures()).isEqualByComparingTo("0.61");
        }

        @Test
        @DisplayName("AC-OPN-3: 빈 회원사명 — institution_name='' 저장")
        void emptyInstitution_storedAsEmpty() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260612", ""))));

            service.collect(TODAY);

            ArgumentCaptor<AnalystEstimate> captor = ArgumentCaptor.forClass(AnalystEstimate.class);
            verify(analystEstimateRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getInstitutionName()).isEqualTo("");
        }

        @Test
        @DisplayName("BIGINT .00 소수 접미사 무손실 정수 변환 (target_price/prev_close)")
        void bigIntDecimalSuffix_lossless() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestOpinionResponse.InvestOpinionRow r =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260612",
                            "매수",
                            "2",
                            "중립",
                            "3",
                            "OO증권",
                            "95000.00",
                            "82000.00",
                            "13000",
                            "15.85",
                            "500",
                            "0.61");
            stubFetch(ISA, "005930", response(List.of(r)));

            service.collect(TODAY);

            ArgumentCaptor<AnalystEstimate> captor = ArgumentCaptor.forClass(AnalystEstimate.class);
            verify(analystEstimateRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTargetPrice()).isEqualTo(95_000L);
            assertThat(captor.getValue().getPrevClose()).isEqualTo(82_000L);
        }

        @Test
        @DisplayName("괴리도/괴리율 음수 정상 저장 (부호 무거부, REQ-070a)")
        void negativeGaps_stored() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestOpinionResponse.InvestOpinionRow r =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260612",
                            "매수",
                            "2",
                            "중립",
                            "3",
                            "OO증권",
                            "95000",
                            "82000",
                            "-13000",
                            "-15.85",
                            "-500",
                            "-0.61");
            stubFetch(ISA, "005930", response(List.of(r)));

            service.collect(TODAY);

            ArgumentCaptor<AnalystEstimate> captor = ArgumentCaptor.forClass(AnalystEstimate.class);
            verify(analystEstimateRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getGapNDay()).isEqualByComparingTo("-13000");
            assertThat(captor.getValue().getGapRateNDay()).isEqualByComparingTo("-15.85");
        }
    }

    @Nested
    @DisplayName("collect — 증분 윈도우 호출 (AC-OPN-1, AC-PATH-5)")
    class WindowCall {

        @Test
        @DisplayName("날짜 포맷 = 10자 00+YYYYMMDD, 윈도우 폭 14일")
        void dateFormat10Char_window14Days() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    captureUri("005930", response(List.of()));

            service.collect(TODAY);

            UriBuilder builder = new DefaultUriBuilderFactory().builder();
            URI uri = uriCaptor.getValue().apply(builder);
            String query = uri.getQuery();
            // 14일 전 = 2026-06-01 → 0020260601, 기준일 2026-06-15 → 0020260615
            assertThat(query).contains("FID_INPUT_DATE_1=0020260601");
            assertThat(query).contains("FID_INPUT_DATE_2=0020260615");
            assertThat(query).contains("FID_COND_SCR_DIV_CODE=16633");
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<Function<UriBuilder, URI>> captureUri(
                String symbol, KisInvestOpinionResponse r) {
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisInvestOpinionResponse.class),
                            eq(symbol)))
                    .thenReturn(BatchResult.success(r));
            return uriCaptor;
        }
    }

    @Nested
    @DisplayName("collect — 검증 / skip / 집계 (AC-VAL-1/2/3)")
    class Validation {

        @Test
        @DisplayName("AC-VAL-3: 빈 output — 0건 succeeded (skip 아님)")
        void emptyOutput_zeroRowsSucceeded() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of()));

            FundamentalResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            verify(analystEstimateRepository, never())
                    .insertIgnoreDuplicate(any(AnalystEstimate.class));
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — skip, 같은 응답 정상 행 저장")
        void unparseableRow_skipped_othersStored() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestOpinionResponse.InvestOpinionRow bad =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260611", "매수", "2", "중립", "3", "OO증권", "x", "y", "z", "w", "v", "u");
            stubFetch(ISA, "005930", response(List.of(bad, row("20260612", "OO증권"))));

            service.collect(TODAY);

            ArgumentCaptor<AnalystEstimate> captor = ArgumentCaptor.forClass(AnalystEstimate.class);
            verify(analystEstimateRepository, times(1)).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("trade_date 파싱 불가 행 — skip")
        void unparseableDate_skipped() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("INVALID", "OO증권"))));

            service.collect(TODAY);

            verify(analystEstimateRepository, never())
                    .insertIgnoreDuplicate(any(AnalystEstimate.class));
        }

        @Test
        @DisplayName("AC-VAL-2: KisTokenIssueException — graceful skip")
        void tokenIssue_gracefulSkip() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisInvestOpinionResponse.class),
                            eq("005930")))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            FundamentalResult result = service.collect(TODAY);

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
                            eq(KisInvestOpinionResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "테스트 skip"));

            FundamentalResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 경로/대상 (AC-PATH-1/2/4)")
    class PathAndTarget {

        @Test
        @DisplayName("STOCK-only 조회 사용 — findAllActiveStock 호출, findAllActive/Tradable 미호출")
        void usesFindAllActiveStock() {
            when(stockRepository.findAllActiveStock()).thenReturn(List.of());

            service.collect(TODAY);

            verify(stockRepository).findAllActiveStock();
            verify(stockRepository, never()).findAllActive();
            verify(stockRepository, never()).findAllActiveTradable();
        }

        @Test
        @DisplayName("멀티키 분산 경로 사용 — distributor.distribute 경유")
        void usesDistributor() {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(ISA, "005930", response(List.of(row("20260612", "OO증권"))));

            service.collect(TODAY);

            verify(distributor).distribute(List.of(stock));
        }
    }

    @Nested
    @DisplayName("collect — 행 단위 집계 관측성 (MI-01)")
    class RowTally {

        @Test
        @DisplayName("MI-01: 혼합 응답(유효 1행 + 파싱실패 1행) — insertIgnoreDuplicate 1회, skip 1건")
        void mixedResponse_savedAndSkippedCounted() {
            // Arrange
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestOpinionResponse.InvestOpinionRow invalidRow =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260611", "매수", "2", "중립", "3", "OO증권", "x", "y", "z", "w", "v", "u");
            stubFetch(ISA, "005930", response(List.of(invalidRow, row("20260612", "OO증권"))));

            // Act
            service.collect(TODAY);

            // Assert — 저장 1행, skip 1행(파싱 실패)
            verify(analystEstimateRepository, times(1))
                    .insertIgnoreDuplicate(any(AnalystEstimate.class));
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-PATH-2)")
    class AllKeysDead {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            serviceLogger = (Logger) LoggerFactory.getLogger(InvestOpinionCollectionService.class);
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
            Stock s2 = stockOf("000660");
            List<Stock> stocks = List.of(s1, s2);
            when(stockRepository.findAllActiveStock()).thenReturn(stocks);
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            FundamentalResult result = service.collect(TODAY);

            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
            List<ILoggingEvent> errors =
                    appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
        }
    }
}
