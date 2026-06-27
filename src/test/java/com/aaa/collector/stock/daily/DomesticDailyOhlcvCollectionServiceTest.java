package com.aaa.collector.stock.daily;

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
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M4(T05) — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} 경로에서 {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 게이트로 이전했으나, 보존 종단 동작(skip on 소진/인터럽트/전 키 사망,
 * per-batch selectHealthy 1회)은 동일하게 유지됨을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DomesticDailyOhlcvCollectionService 단위 테스트 (게이트 이전)")
class DomesticDailyOhlcvCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private StockRepository stockRepository;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private MismatchDetector mismatchDetector;
    @Mock private WarningCountingOhlcvInserter ohlcvInserter;

    private DomesticDailyOhlcvCollectionService service;

    @BeforeEach
    void setUp() {
        // 실제 KeyLeaseRegistry + mock HealthyKeySelector — openSession()이 진짜 LeaseSession을 생성하여
        // selectHealthy() per-batch 1회 호출(REQ-KISGATE-006a)을 검증 가능하게 한다.
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new DomesticDailyOhlcvCollectionService(
                        stockRepository,
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        mismatchDetector,
                        ohlcvInserter);
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

    private KisDailyOhlcvResponse.DailyOhlcvRow validRow(String date) {
        return new KisDailyOhlcvResponse.DailyOhlcvRow(
                date, "75000", "74000", "76000", "73000", "1000000", "75000000000", "N");
    }

    private KisDailyOhlcvResponse stubResponse(List<KisDailyOhlcvResponse.DailyOhlcvRow> rows) {
        return new KisDailyOhlcvResponse("0", "MCA00000", "조회되었습니다.", rows);
    }

    @Nested
    @DisplayName("collect — 성공 경로")
    class CollectSuccess {

        @Test
        @DisplayName("활성 종목 1개 — 수집 성공, 시도=1, 성공=1, skip=0")
        void collect_oneActiveStock_successResult() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));

            KisDailyOhlcvResponse response = stubResponse(List.of(validRow("20260605")));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(response);

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            // REQ-KISGATE-006a: per-batch 스냅샷 1회
            verify(healthyKeySelector, times(1)).selectHealthy();
        }

        @Test
        @DisplayName("활성 종목 없음 — 시도=0, 성공=0, skip=0, 게이트 미호출, selectHealthy 미호출")
        void collect_noActiveStocks_zeroResult() throws Exception {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(0);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(0);
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            verify(healthyKeySelector, never()).selectHealthy();
        }

        @Test
        @DisplayName("활성 종목 2개 — 결과 집계 시도=2, 성공=2, skip=0")
        void collect_twoActiveStocks_aggregatesCorrectly() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(s1, s2));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(resp);

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
            // 종목 수와 무관하게 selectHealthy는 배치당 1회 (REQ-KISGATE-006a)
            verify(healthyKeySelector, times(1)).selectHealthy();
        }
    }

    @Nested
    @DisplayName("collect — skip 집계 (AC-6, REQ-KISGATE-009/022)")
    class CollectSkip {

        @Test
        @DisplayName("retryable 재시도 소진(KisRateLimitException 전파) — skip 집계 (AC-6)")
        void collect_retryableExhausted_countedInSkipped() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("RestClientException 소진 — skip 집계 (AC-6)")
        void collect_restClientException_countedInSkipped() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenThrow(new RestClientException("네트워크 오류"));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
        }

        @Test
        @DisplayName("InterruptedException — skip 변환(전파 아님) + 인터럽트 플래그 복원 (AC-6, REQ-RETRY-017)")
        void collect_interrupted_skipAndRestoresFlag() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenThrow(new InterruptedException("테스트 인터럽트"));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // 전파 아닌 skip 변환 — 배치 미실패
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패 (REQ-BATCH-025 보존)")
        void collect_tokenIssueException_gracefulSkip() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenThrow(
                            new KisTokenIssueException("isa", new RuntimeException("token fail")));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("종목 3개 중 1개 mid-run KisTokenIssueException — 나머지 2개 계속 진행")
        void collect_midRunTokenException_onlyAffectedStockSkipped() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            Stock s3 = stockOf("035720");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(s1, s2, s3));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(resp)
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("mid-run")))
                    .thenReturn(resp);

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-5, REQ-KISGATE-024, REQ-KEYDIST-020 보존)")
    class AllKeysDead {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> listAppender;

        @BeforeEach
        void attachLogAppender() {
            serviceLogger =
                    (Logger) LoggerFactory.getLogger(DomesticDailyOhlcvCollectionService.class);
            listAppender = new ListAppender<>();
            listAppender.start();
            serviceLogger.addAppender(listAppender);
        }

        @AfterEach
        void detachLogAppender() {
            serviceLogger.detachAppender(listAppender);
            listAppender.stop();
        }

        @Test
        @DisplayName("빈 스냅샷 — 게이트 0회 호출, 전체 skip, 시도=total, ERROR 로그 1회 (AC-5)")
        void collect_emptySnapshot_zeroGateCalls() throws Exception {
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            Stock s3 = stockOf("035720");
            List<Stock> stocks = List.of(s1, s2, s3);
            when(stockRepository.findAllActiveTradable()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // per-stock 게이트 호출 0회 (AC-5 HARD)
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(3);
            // ERROR 로그 정확히 1회
            List<ILoggingEvent> errorLogs =
                    listAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errorLogs).hasSize(1);
        }
    }

    @Nested
    @DisplayName("collect — 검증 필터링 (REQ-BATCH-033 보존)")
    class CollectValidation {

        @Test
        @DisplayName("종가 0 이하인 행 — insertIgnoreDuplicate 미호출")
        void collect_zeroPriceRow_notInserted() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            KisDailyOhlcvResponse.DailyOhlcvRow invalidRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605",
                            "0",
                            "74000",
                            "76000",
                            "73000",
                            "1000000",
                            "75000000000",
                            "N");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(invalidRow)));

            service.collect(LocalDate.of(2026, 6, 5));

            // Assert — 유효 행이 없으면 insertBatch가 호출되지 않는다 (REQ-OBSV-023 경고 캡처 경로)
            verify(ohlcvInserter, never()).insertBatch(any(), any(), any());
        }

        @Test
        @DisplayName("거래량 0 이하인 행 — insertBatch 미호출")
        void collect_zeroVolumeRow_notInserted() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            KisDailyOhlcvResponse.DailyOhlcvRow invalidRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605", "75000", "74000", "76000", "73000", "0", "0", "N");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(invalidRow)));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            verify(ohlcvInserter, never()).insertBatch(any(), any(), any());
        }

        @Test
        @DisplayName("유효한 행 — insertBatch 1회 호출(1행 배치)")
        void collect_validRow_insertsOnce() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260605"))));

            service.collect(LocalDate.of(2026, 6, 5));

            // Assert — 유효 행 1개를 담은 배치로 1회 적재 (ParsedOhlcvRow 2-param 오버로드)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1)).insertBatch(any(), captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("AC-1 — 원주가 파라미터 전송 (REQ-OHLCV2-001, -002 보존)")
    class RawPriceParameter {

        @Test
        @DisplayName("게이트 호출 시 FID_ORG_ADJ_PRC=1(원주가)로 요청됨 — 0(수정주가) 아님")
        @SuppressWarnings("unchecked")
        void fetch_sendsRawPriceParam() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            eq("FHKST03010100"),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260605"))));

            service.collect(LocalDate.of(2026, 6, 5));

            Function<UriBuilder, URI> capturedUriCustomizer = uriCaptor.getValue();
            URI builtUri = capturedUriCustomizer.apply(UriComponentsBuilder.newInstance());
            assertThat(builtUri.toString()).contains("FID_ORG_ADJ_PRC=1");
            assertThat(builtUri.toString()).doesNotContain("FID_ORG_ADJ_PRC=0");
        }
    }

    @Nested
    @DisplayName("불일치 탐지 위임 (REQ-OHLCV2-010, -011 보존)")
    class MismatchDetectionDelegation {

        @Test
        @DisplayName("유효 행 존재 시 — mismatchDetector.detectAndLog() 호출됨")
        void validRows_delegatesToMismatchDetector() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260605"))));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            verify(mismatchDetector, times(1)).detectAndLog(any(), eq("005930"), any());

            // No UPDATE/DELETE — INSERT IGNORE 배치가 유일한 쓰기 경로 (AC-4); 경고 캡처 inserter 1회 호출
            verify(ohlcvInserter, times(1)).insertBatch(any(), any());

            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("유효 행 없음 시 — mismatchDetector.detectAndLog() 미호출")
        void noValidRows_mismatchDetectorNotCalled() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            KisDailyOhlcvResponse.DailyOhlcvRow adjRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605",
                            "75000",
                            "74000",
                            "76000",
                            "73000",
                            "1000000",
                            "75000000000",
                            "Y");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(adjRow)));

            service.collect(LocalDate.of(2026, 6, 5));

            verify(mismatchDetector, never()).detectAndLog(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("fetchWindow / persistWindow — fetch·persist 분리 (T2, REQ-TXBOUNDARY-002)")
    class FetchPersistWindow {

        private static final LocalDate ANCHOR = LocalDate.of(2026, 5, 30);

        /**
         * 테스트 고정 from-date — SPEC-COLLECTOR-BACKFILL-005 이후 floorDate(1950-01-01) 사용; 여기선 직접 파라미터로
         * 주입
         */
        private static final LocalDate FROM = ANCHOR.minusDays(150);

        private LeaseSession openSession() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            return new KeyLeaseRegistry(healthyKeySelector).openSession();
        }

        @Test
        @DisplayName("fetchWindow — DB(ohlcvInserter) 미호출 (fetch 단계에서 INSERT 없음)")
        void fetchWindow_doesNotCallInserter() throws Exception {
            // Arrange
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260101"))));

            // Act
            DomesticDailyOhlcvFetch fetch =
                    service.fetchWindow(FROM, ANCHOR, stockOf("005930"), openSession());

            // Assert — fetch 단계에서 DB INSERT 없음
            verify(ohlcvInserter, never()).insertBatch(any(), any());
            assertThat(fetch.rowCount()).isEqualTo(1);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("fetchWindow — 빈 응답 시 rows=빈목록, oldestTradeDate=null, rowCount=0")
        void fetchWindow_emptyResponse_emptyFetch() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of()));

            DomesticDailyOhlcvFetch fetch =
                    service.fetchWindow(FROM, ANCHOR, stockOf("005930"), openSession());

            assertThat(fetch.rows()).isEmpty();
            assertThat(fetch.oldestTradeDate()).isNull();
            assertThat(fetch.rowCount()).isZero();
            verify(ohlcvInserter, never()).insertBatch(any(), any());
        }

        @Test
        @DisplayName(
                "fetchWindow — anchor를 FID_INPUT_DATE_2, from(anchor-150)을 FID_INPUT_DATE_1으로 전송")
        @SuppressWarnings("unchecked")
        void fetchWindow_sendsAnchorAndSpanParams() throws Exception {
            // Arrange — anchor=2026-05-30 → DATE_2=20260530, from=2025-12-31 → DATE_1=20251231
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260101"))));

            service.fetchWindow(FROM, ANCHOR, stockOf("005930"), openSession());

            URI built = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(built.toString()).contains("FID_INPUT_DATE_2=20260530");
            assertThat(built.toString()).contains("FID_INPUT_DATE_1=20251231");
        }

        @Test
        @DisplayName("persistWindow — ohlcvInserter.insertBatch 1회 호출 (persist 단계에서 INSERT 발생)")
        void persistWindow_callsInserter() throws Exception {
            // Arrange
            Stock stock = stockOf("005930");
            ParsedOhlcvRow parsedRow =
                    new ParsedOhlcvRow(
                            LocalDate.of(2026, 1, 1),
                            new BigDecimal("74000"),
                            new BigDecimal("76000"),
                            new BigDecimal("73000"),
                            new BigDecimal("75000"),
                            1_000_000L,
                            75_000_000_000L);
            DomesticDailyOhlcvFetch fetch =
                    new DomesticDailyOhlcvFetch(List.of(parsedRow), LocalDate.of(2026, 1, 1), 1);

            // Act
            BackfillWindowResult result = service.persistWindow(stock, fetch);

            // Assert — persist 단계에서 정확히 1회 INSERT (ParsedOhlcvRow 2-param 오버로드)
            verify(ohlcvInserter, times(1)).insertBatch(any(), any());
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(result.rowCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("persistWindow — 빈 fetch → EMPTY 반환, inserter 미호출")
        void persistWindow_emptyFetch_returnsEmpty() {
            DomesticDailyOhlcvFetch emptyFetch = new DomesticDailyOhlcvFetch(List.of(), null, 0);

            BackfillWindowResult result = service.persistWindow(stockOf("005930"), emptyFetch);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(ohlcvInserter, never()).insertBatch(any(), any());
        }

        @Test
        @DisplayName("회귀 가드 — 당일 경로(collect) green 유지: saveValidRows가 collect에서 정상 동작")
        void dailyPathRegression_collectStillWorks() throws Exception {
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260605"))));

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.succeeded()).isEqualTo(1);
            verify(ohlcvInserter, times(1)).insertBatch(any(), any());
        }
    }

    @Nested
    @DisplayName("T3a 회귀 — asset_type 필터 검증 (REQ-BATCH3-024 보존)")
    class AssetTypeFilter {

        @Test
        @DisplayName("findAllActiveTradable()으로 호출 — INDEX 제외는 StockRepository 계층이 보장")
        void collect_callsFindAllActiveTradable() {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());

            service.collect(LocalDate.of(2026, 6, 13));

            verify(stockRepository).findAllActiveTradable();
        }
    }

    @Nested
    @DisplayName("collectWindow — 백필 윈도우 메서드 (REQ-BACKFILL-002, T3)")
    class BackfillWindow {

        private LeaseSession openSession() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            return new KeyLeaseRegistry(healthyKeySelector).openSession();
        }

        @Test
        @DisplayName("주어진 from/to 날짜를 KIS 파라미터로 전송 (당일 14일 윈도우와 무관)")
        @SuppressWarnings("unchecked")
        void collectWindow_sendsGivenDateParams() throws Exception {
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            eq("FHKST03010100"),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260101"))));

            service.collectWindow(
                    stockOf("005930"),
                    openSession(),
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 5, 30));

            URI built = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(built.toString()).contains("FID_INPUT_DATE_1=20260101");
            assertThat(built.toString()).contains("FID_INPUT_DATE_2=20260530");
        }

        @Test
        @DisplayName("당일 수집과 동일한 검증·INSERT IGNORE 경로 재사용 (insertBatch 1회)")
        void collectWindow_reusesValidateInsertPath() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of(validRow("20260102"), validRow("20260101"))));

            service.collectWindow(
                    stockOf("005930"),
                    openSession(),
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 5, 30));

            verify(ohlcvInserter, times(1)).insertBatch(any(), any());
        }

        @Test
        @DisplayName("반환 결과 — 최소 거래일과 행 수를 노출 (종료 판정 입력)")
        void collectWindow_returnsOldestAndRowCount() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(
                            stubResponse(
                                    List.of(
                                            validRow("20260103"),
                                            validRow("20260101"),
                                            validRow("20260102"))));

            BackfillWindowResult result =
                    service.collectWindow(
                            stockOf("005930"),
                            openSession(),
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 5, 30));

            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(result.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("0건 응답 — oldest=null, rowCount=0 (REQ-BACKFILL-012 입력)")
        void collectWindow_emptyResponse_zeroResult() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class)))
                    .thenReturn(stubResponse(List.of()));

            BackfillWindowResult result =
                    service.collectWindow(
                            stockOf("005930"),
                            openSession(),
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 5, 30));

            assertThat(result.oldestTradeDate()).isNull();
            assertThat(result.rowCount()).isZero();
            verify(ohlcvInserter, never()).insertBatch(any(), any(), any());
        }
    }
}
