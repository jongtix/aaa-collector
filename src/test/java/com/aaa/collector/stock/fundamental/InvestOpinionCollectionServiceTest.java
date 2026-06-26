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
import com.aaa.collector.stock.AnalystEstimate;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
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
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T09) — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} → {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 이전. 보존 종단 동작·매핑·검증·행 단위 집계를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestOpinionCollectionService 단위 테스트 (게이트 이전)")
class InvestOpinionCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Mock private StockRepository stockRepository;
    @Mock private AnalystEstimateRepository analystEstimateRepository;
    @Mock private AnalystEstimateInserter analystEstimateInserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    @Captor private ArgumentCaptor<List<AnalystEstimate>> inserterCaptor;

    private InvestOpinionCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new InvestOpinionCollectionService(
                        stockRepository,
                        analystEstimateRepository,
                        analystEstimateInserter,
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

    private void stubFetch(KisInvestOpinionResponse r) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisInvestOpinionResponse.class)))
                .thenReturn(r);
    }

    private void singleStock(Stock stock) {
        when(stockRepository.findAllActiveStock()).thenReturn(List.of(stock));
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
    }

    @Nested
    @DisplayName("collect — 매핑 (AC-OPN-2/3 보존)")
    class Mapping {

        private AnalystEstimate captureSaved(KisInvestOpinionResponse.InvestOpinionRow inputRow)
                throws InterruptedException {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(inputRow)));

            service.collect(TODAY);

            verify(analystEstimateInserter).insertBatch(inserterCaptor.capture());
            return inserterCaptor.getAllValues().stream()
                    .flatMap(List::stream)
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("AC-OPN-2: 비-BigDecimal 8개 필드 매핑 + BIGINT 정수 변환")
        void mapsNonDecimalFields() throws Exception {
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
        void mapsGapFields() throws Exception {
            AnalystEstimate saved = captureSaved(row("20260612", "OO증권"));

            assertThat(saved.getGapNDay()).isEqualByComparingTo("13000");
            assertThat(saved.getGapRateNDay()).isEqualByComparingTo("15.85");
            assertThat(saved.getGapFutures()).isEqualByComparingTo("500");
            assertThat(saved.getGapRateFutures()).isEqualByComparingTo("0.61");
        }

        @Test
        @DisplayName("AC-OPN-3: 빈 회원사명 — institution_name='' 저장")
        void emptyInstitution_storedAsEmpty() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612", ""))));

            service.collect(TODAY);

            verify(analystEstimateInserter).insertBatch(inserterCaptor.capture());
            AnalystEstimate savedInst =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedInst.getInstitutionName()).isEqualTo("");
        }

        @Test
        @DisplayName("BIGINT .00 소수 접미사 무손실 정수 변환 (target_price/prev_close)")
        void bigIntDecimalSuffix_lossless() throws Exception {
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
            stubFetch(response(List.of(r)));

            service.collect(TODAY);

            verify(analystEstimateInserter).insertBatch(inserterCaptor.capture());
            AnalystEstimate savedBigInt =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedBigInt.getTargetPrice()).isEqualTo(95_000L);
            assertThat(savedBigInt.getPrevClose()).isEqualTo(82_000L);
        }

        @Test
        @DisplayName("괴리도/괴리율 음수 정상 저장 (부호 무거부, REQ-070a)")
        void negativeGaps_stored() throws Exception {
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
            stubFetch(response(List.of(r)));

            service.collect(TODAY);

            verify(analystEstimateInserter).insertBatch(inserterCaptor.capture());
            AnalystEstimate savedGap =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedGap.getGapNDay()).isEqualByComparingTo("-13000");
            assertThat(savedGap.getGapRateNDay()).isEqualByComparingTo("-15.85");
        }
    }

    @Nested
    @DisplayName("collect — 증분 윈도우 호출 (AC-OPN-1 보존)")
    class WindowCall {

        @Test
        @DisplayName("날짜 포맷 = 10자 00+YYYYMMDD, 윈도우 폭 14일")
        @SuppressWarnings("unchecked")
        void dateFormat10Char_window14Days() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisInvestOpinionResponse.class)))
                    .thenReturn(response(List.of()));

            service.collect(TODAY);

            UriBuilder builder = new DefaultUriBuilderFactory().builder();
            URI uri = uriCaptor.getValue().apply(builder);
            String query = uri.getQuery();
            assertThat(query).contains("FID_INPUT_DATE_1=0020260601");
            assertThat(query).contains("FID_INPUT_DATE_2=0020260615");
            assertThat(query).contains("FID_COND_SCR_DIV_CODE=16633");
        }
    }

    @Nested
    @DisplayName("collect — 검증 / skip / 집계 (AC-VAL-1/2/3 보존)")
    class Validation {

        @Test
        @DisplayName("AC-VAL-3: 빈 output — 0건 succeeded (skip 아님)")
        void emptyOutput_zeroRowsSucceeded() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of()));

            FundamentalResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            verify(analystEstimateInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — skip, 같은 응답 정상 행 저장")
        void unparseableRow_skipped_othersStored() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            KisInvestOpinionResponse.InvestOpinionRow bad =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260611", "매수", "2", "중립", "3", "OO증권", "x", "y", "z", "w", "v", "u");
            stubFetch(response(List.of(bad, row("20260612", "OO증권"))));

            service.collect(TODAY);

            verify(analystEstimateInserter, times(1)).insertBatch(inserterCaptor.capture());
            AnalystEstimate savedBad =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedBad.getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
        }

        @Test
        @DisplayName("trade_date 파싱 불가 행 — skip")
        void unparseableDate_skipped() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("INVALID", "OO증권"))));

            service.collect(TODAY);

            verify(analystEstimateInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("AC-VAL-2: KisTokenIssueException — graceful skip")
        void tokenIssue_gracefulSkip() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestOpinionResponse.class)))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("fail")));

            FundamentalResult result = service.collect(TODAY);

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
                            eq(KisInvestOpinionResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));

            FundamentalResult result = service.collect(TODAY);

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
                            eq(KisInvestOpinionResponse.class)))
                    .thenThrow(new RestClientException("네트워크"));

            FundamentalResult result = service.collect(TODAY);

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
                            eq(KisInvestOpinionResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"));

            FundamentalResult result = service.collect(TODAY);

            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 경로/대상 (AC-PATH-1 보존)")
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
        @DisplayName("게이트 경유 + per-batch selectHealthy 1회 (AC-1, REQ-KISGATE-006a)")
        void usesGateWithSingleSnapshot() throws Exception {
            Stock stock = stockOf("005930");
            singleStock(stock);
            stubFetch(response(List.of(row("20260612", "OO증권"))));

            service.collect(TODAY);

            verify(guardedKisExecutor, times(1))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestOpinionResponse.class));
            verify(healthyKeySelector, times(1)).selectHealthy();
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
            KisInvestOpinionResponse.InvestOpinionRow invalidRow =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260611", "매수", "2", "중립", "3", "OO증권", "x", "y", "z", "w", "v", "u");
            stubFetch(response(List.of(invalidRow, row("20260612", "OO증권"))));

            service.collect(TODAY);

            verify(analystEstimateInserter, times(1)).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-5, REQ-KISGATE-024 보존)")
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
        @DisplayName("빈 스냅샷 — 게이트 0회, 전체 skip, ERROR 1회 (AC-5)")
        void emptySnapshot_skipAll_errorLog() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            List<Stock> stocks = List.of(s1, s2);
            when(stockRepository.findAllActiveStock()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            FundamentalResult result = service.collect(TODAY);

            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(2);
            List<ILoggingEvent> errors =
                    appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
        }
    }
}
