package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.observability.WatermarkSeries;
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
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 — 게이트 이전 후 회귀 테스트.
 *
 * <p>{@code BatchRestExecutor}+{@code HealthyKeyRoundRobinDistributor} 경로에서 {@code
 * GuardedKisExecutor}+{@code KeyLeaseRegistry} 게이트로 이전했으나, 보존 종단 동작(EXCD 라우팅·MODP=0·zdiv/빈응답/ET 당일
 * 가드·검증·skip 집계·전 키 사망·per-batch selectHealthy 1회)은 동일하게 유지됨을 고정한다
 * (SPEC-COLLECTOR-OVERSEAS-OHLCV-001).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasDailyOhlcvCollectionService 단위 테스트 (게이트 이전)")
class OverseasDailyOhlcvCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 18);
    private static final String TODAY_YMD = "20260618";
    private static final String PREV_YMD = "20260617";

    @Mock private StockRepository stockRepository;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private WarningCountingOhlcvInserter ohlcvInserter;
    @Mock private UsMarketOpenGate usMarketOpenGate;

    private OverseasDailyOhlcvCollectionService service;

    @BeforeEach
    void setUp() {
        // 실제 KeyLeaseRegistry + mock HealthyKeySelector — openSession()이 진짜 LeaseSession을 생성하여
        // selectHealthy() per-batch 1회 호출(REQ-KISGATE-006a)을 검증 가능하게 한다.
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        // 기존 테스트는 휴장일 게이트 행동을 검증하지 않으므로 always-open으로 스텁한다
        lenient().when(usMarketOpenGate.isOpenDay(any(LocalDate.class))).thenReturn(true);
        service =
                new OverseasDailyOhlcvCollectionService(
                        stockRepository,
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        ohlcvInserter,
                        usMarketOpenGate);
    }

    private Stock stockOf(String symbol, Market market, AssetType assetType) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(market)
                .assetType(assetType)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    private KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row(
            String xymd, String price, String tvol, String tamt) {
        return new KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow(
                xymd, price, price, price, price, tvol, tamt);
    }

    /** OHLC를 개별 지정하는 행 생성 — 클램프(aaa-infra#60) 스큐 시나리오 검증용. */
    private KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow rowOhlc(
            String xymd,
            String open,
            String high,
            String low,
            String close,
            String tvol,
            String tamt) {
        return new KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow(
                xymd, close, open, high, low, tvol, tamt);
    }

    private KisOverseasDailyOhlcvResponse response(
            String zdiv, List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> rows) {
        return new KisOverseasDailyOhlcvResponse(
                "0",
                "MCA00000",
                "정상",
                new KisOverseasDailyOhlcvResponse.Output1("DNASAAPL", zdiv, "100"),
                rows);
    }

    /** zdiv=4, 확정(직전 영업일) 정상 행 1건 응답. */
    private KisOverseasDailyOhlcvResponse validResponse() {
        return response("4", List.of(row(PREV_YMD, "296.9200", "42745060", "12697950974")));
    }

    @Nested
    @DisplayName("collect — 대상 조회 (AC-TGT-1, AC-PATH-4)")
    class Target {

        @Test
        @DisplayName("findAllActiveOverseasTradable() 진입점 호출 — 미국 STOCK+ETF 한정 조회")
        void collect_callsOverseasTradableQuery() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            service.collect(TODAY);

            verify(stockRepository).findAllActiveOverseasTradable();
        }

        @Test
        @DisplayName("빈 대상 — attempted=0, 게이트 미호출, selectHealthy 미호출 (AC-PATH-4, REQ-OVOH-030)")
        void collect_emptyTarget_zeroResultNoCall() throws Exception {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            CollectionResult result = service.collect(TODAY);

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            verify(healthyKeySelector, never()).selectHealthy();
        }
    }

    @Nested
    @DisplayName("collect — EXCD 라우팅 (AC-PATH-2, REQ-OVOH-002)")
    class ExcdRouting {

        @Test
        @DisplayName("NASDAQ → EXCD=NAS")
        void collect_nasdaq_routesToNas() throws Exception {
            assertExcd("AAPL", Market.NASDAQ, AssetType.STOCK, "NAS");
        }

        @Test
        @DisplayName("NYSE → EXCD=NYS")
        void collect_nyse_routesToNys() throws Exception {
            assertExcd("F", Market.NYSE, AssetType.STOCK, "NYS");
        }

        @Test
        @DisplayName("AMEX(SPY ETF) → EXCD=AMS — NYSE Arca 실측 정합, 상품기본정보 529와 구분")
        void collect_amex_routesToAms() throws Exception {
            assertExcd("SPY", Market.AMEX, AssetType.ETF, "AMS");
        }

        @SuppressWarnings("unchecked")
        private void assertExcd(
                String symbol, Market market, AssetType assetType, String expectedExcd)
                throws InterruptedException {
            // Arrange
            Stock stock = stockOf(symbol, market, assetType);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse());

            // Act
            service.collect(TODAY);

            // Assert
            assertThat(uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString())
                    .contains("EXCD=" + expectedExcd);
        }
    }

    @Nested
    @DisplayName("collect — dailyprice 요청 파라미터 (AC-SAVE-2, AC-SCOPE-1, REQ-OVOH-003a)")
    class RequestParameters {

        @Test
        @DisplayName("MODP=0(원주가) — 국내 FID_ORG_ADJ_PRC=1(원주가)와 의미 반대, 1 복사 금지")
        @SuppressWarnings("unchecked")
        void fetch_sendsModp0_rawPrice() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            eq("HHDFS76240000"),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse());

            // Act
            service.collect(TODAY);

            // Assert
            String uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString();
            assertThat(uri).contains("MODP=0");
            assertThat(uri).doesNotContain("MODP=1");
            assertThat(uri).contains("GUBN=0");
            assertThat(uri).contains("SYMB=AAPL");
        }

        @Test
        @DisplayName("BYMD=실행 ET 거래일 단건 호출 — 종목당 execute 정확히 1회 (AC-SCOPE-1)")
        @SuppressWarnings("unchecked")
        void fetch_bymdEtToday_singleCall() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse());

            // Act
            service.collect(TODAY);

            // Assert — 종목당 1회 호출, BYMD=ET 거래일
            verify(guardedKisExecutor, times(1))
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            String uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString();
            assertThat(uri).contains("BYMD=" + TODAY_YMD);
        }
    }

    @Nested
    @DisplayName("collect — 멀티키 경로 / 모든 키 죽음 (AC-PATH-1, AC-PATH-3)")
    class MultiKeyPath {

        @Test
        @DisplayName("멀티키 게이트 경유 — 집계 시도=2, 성공=2, selectHealthy 배치당 1회")
        void collect_routesThroughGate() throws Exception {
            // Arrange
            Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(s1, s2));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse());

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();
            // REQ-KISGATE-006a: per-batch 스냅샷 1회
            verify(healthyKeySelector, times(1)).selectHealthy();
        }

        @Nested
        @DisplayName("모든 키 죽음 (AC-PATH-3, REQ-OVOH-031, REQ-KISGATE-024)")
        class AllKeysDead {

            private Logger serviceLogger;
            private ListAppender<ILoggingEvent> listAppender;

            @BeforeEach
            void attachLogAppender() {
                serviceLogger =
                        (Logger) LoggerFactory.getLogger(OverseasDailyOhlcvCollectionService.class);
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
            @DisplayName("빈 스냅샷 — 게이트 0회, 전체 skip, ERROR 1회 (AC-PATH-3)")
            void collect_emptySnapshot_skipAllErrorOnce() throws Exception {
                // Arrange
                Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
                Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
                List<Stock> stocks = List.of(s1, s2);
                when(stockRepository.findAllActiveOverseasTradable()).thenReturn(stocks);
                when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

                // Act
                CollectionResult result = service.collect(TODAY);

                // Assert
                verify(guardedKisExecutor, never())
                        .execute(any(LeaseSession.class), any(), anyString(), any());
                assertThat(result.attempted()).isEqualTo(2);
                assertThat(result.succeeded()).isZero();
                assertThat(result.skipped()).isEqualTo(2);
                List<ILoggingEvent> errorLogs =
                        listAppender.list.stream()
                                .filter(e -> e.getLevel() == Level.ERROR)
                                .toList();
                assertThat(errorLogs).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("collect — 검증 skip (AC-VAL-1, REQ-OVOH-012)")
    class Validation {

        @Test
        @DisplayName("가격 0/음수 행 — 저장 제외")
        void collect_nonPositivePrice_notInserted() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "0", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("가격 > USD 100,000 행 — 저장 제외(상한 가드)")
        void collect_priceAboveMax_notInserted() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100001", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("volume<0(음수) 행 — 저장 제외 (SPEC-COLLECTOR-BACKFILL-006 재정의: tvol==0은 거래정지로 저장)")
        void collect_negativeTvol_notInserted() throws Exception {
            // tvol==0은 거래정지일로 저장 대상이므로, 진짜 거부 케이스를 volume<0(음수)으로 분리(REQ-BACKFILL-093)
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "-1", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("정상 행(volume>0) + tamt < 0(음수) — 저장 제외(MA-01 음수 방어 유지)")
        void collect_normalRowNegativeTamt_notInserted() throws Exception {
            // volume>0(거래정지 아님)인데 tamt<0(음수, 물리적 불가능값) → 데이터 오류로 거부
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "1000", "-5"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("정상 행(volume>0) + tamt==0 — 저장(aaa-infra#93, KIS 아카이브 실데이터 결측 방지)")
        void collect_normalRowZeroTamt_inserted() throws Exception {
            // volume>0(거래정지 아님)인데 tamt==0(KIS 아카이브 실데이터 결측) → 이제 허용(등호 제거)
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "1000", "0"))));

            service.collect(TODAY);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            ParsedOhlcvRow saved = captor.getValue().getFirst();
            assertThat(saved.tradingValue()).isEqualTo(0L);
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외")
        void collect_parseFailure_notInserted() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "abc", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("회귀(MA-01): tamt=1.27e10(AAPL 실측) 정상 저장 — tvol/tamt 상한 미적용")
        void collect_largeTamt_inserted() throws Exception {
            // Arrange — 국내 VOLUME_MAX(1.0e10)를 초과하는 정상 미국 대형주 거래대금
            stubSingle(
                    "AAPL",
                    response("4", List.of(row(PREV_YMD, "296.9200", "42745060", "12697950974"))));

            // Act
            service.collect(TODAY);

            // Assert — reject되지 않고 정상 저장 (ParsedOhlcvRow 캡처로 tvol/tamt 검증)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            ParsedOhlcvRow saved = captor.getValue().getFirst();
            assertThat(saved.volume()).isEqualTo(42_745_060L);
            assertThat(saved.tradingValue()).isEqualTo(12_697_950_974L);
        }
    }

    @Nested
    @DisplayName("collect — OHLC 정합성 클램프 (aaa-infra#60)")
    class OhlcClamp {

        @Test
        @DisplayName("high < max(open,close,low) — high를 max(open,close,low)로 상향, skip하지 않고 저장")
        void collect_highBelowMax_clampedUpAndInserted() throws Exception {
            // Arrange — V 2021-05-05 실측 케이스: open=233.85 > high=231.09(KIS 원본 결함)
            stubSingle(
                    "V",
                    response(
                            "4",
                            List.of(
                                    rowOhlc(
                                            PREV_YMD, "233.85", "231.09", "228.66", "229.21",
                                            "1000", "100000"))));

            // Act
            service.collect(TODAY);

            // Assert — skip되지 않고 high만 233.85(=open)로 클램프되어 저장
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            ParsedOhlcvRow saved = captor.getValue().getFirst();
            assertThat(saved.high()).isEqualByComparingTo("233.85");
            assertThat(saved.low()).isEqualByComparingTo("228.66");
        }

        @Test
        @DisplayName("low > min(open,close,high) — low를 min(open,close,high)로 하향, skip하지 않고 저장")
        void collect_lowAboveMin_clampedDownAndInserted() throws Exception {
            // Arrange — GS 2021-05-05 실측 케이스: low=354.01 >
            // min(open=352.00,close=357.62,high=359.14)
            stubSingle(
                    "GS",
                    response(
                            "4",
                            List.of(
                                    rowOhlc(
                                            PREV_YMD, "352.00", "359.14", "354.01", "357.62",
                                            "1000", "100000"))));

            // Act
            service.collect(TODAY);

            // Assert — skip되지 않고 low만 352.00(=open)으로 클램프되어 저장
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            ParsedOhlcvRow saved = captor.getValue().getFirst();
            assertThat(saved.low()).isEqualByComparingTo("352.00");
            assertThat(saved.high()).isEqualByComparingTo("359.14");
        }

        @Test
        @DisplayName("OHLC 정합성 정상 행 — 클램프 미적용, 원값 그대로 저장")
        void collect_consistentOhlc_notClamped() throws Exception {
            // Arrange — 정상 케이스: high가 이미 max, low가 이미 min
            stubSingle(
                    "AAPL",
                    response(
                            "4",
                            List.of(
                                    rowOhlc(
                                            PREV_YMD, "100.00", "105.00", "98.00", "102.00", "1000",
                                            "100000"))));

            // Act
            service.collect(TODAY);

            // Assert — 원값 그대로 저장(클램프 미개입)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            ParsedOhlcvRow saved = captor.getValue().getFirst();
            assertThat(saved.high()).isEqualByComparingTo("105.00");
            assertThat(saved.low()).isEqualByComparingTo("98.00");
        }
    }

    @Nested
    @DisplayName("거래정지일 volume=0/tamt=0 저장 (SPEC-COLLECTOR-BACKFILL-006 — AC-10/AC-12/EC-6/EC-7)")
    class OverseasTradingHaltVolumeZero {

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<List<ParsedOhlcvRow>> captureInsert() {
            return ArgumentCaptor.forClass(List.class);
        }

        @Test
        @DisplayName(
                "AC-10: OHLC 유효(close<=PRICE_MAX_USD) + volume==0 + tamt==0 → 저장 (비분할 거래정지 모사)")
        void haltRow_volumeZeroTamtZero_isStored() throws Exception {
            // Arrange — volume=0, tamt=0, OHLC 유효(가격 100)
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "0", "0"))));

            // Act
            service.collect(TODAY);

            // Assert
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = captureInsert();
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().volume()).isZero();
            assertThat(captor.getValue().getFirst().tradingValue()).isZero();
        }

        @Test
        @DisplayName("AC-10: volume<0(음수) → 거부")
        void negativeVolume_isRejected() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "-1", "0"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("AC-10: OHLC 무효(close=0) + volume==0 → 거부")
        void invalidOhlc_volumeZero_isRejected() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "0", "0", "0"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("AC-12: OHLC 무효(close>PRICE_MAX_USD) + volume==0 → 거부 (상한 가드 보존)")
        void priceAboveMax_volumeZero_isRejected() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100001", "0", "0"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("AC-12: 정상 행(volume>0, tamt>0)은 비회귀 저장 — 거래정지 완화가 정상 행 검증을 깨지 않음")
        void normalRow_stillStored() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "1000", "100000"))));

            service.collect(TODAY);

            ArgumentCaptor<List<ParsedOhlcvRow>> captor = captureInsert();
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().volume()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("AC-11(당일 일관성): collect 경로도 거래정지(volume=0) 저장 — 공유 parseIfValid 자연 파급")
        void collectPath_haltRow_stored() throws Exception {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "0", "0"))));

            CollectionResult result = service.collect(TODAY);

            verify(ohlcvInserter, times(1)).insertBatch(any(), any(), any(WatermarkSeries.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("EC-7: ET 당일 행 + 거래정지 행 혼재 → ET 당일 제외, 거래정지 저장")
        void etTodayAndHaltRow_etExcluded_haltStored() throws Exception {
            // Arrange — 최신 ET 당일 행 + 직전 거래정지(volume=0) 행
            KisOverseasDailyOhlcvResponse resp =
                    response(
                            "4",
                            List.of(
                                    row(TODAY_YMD, "100", "1000", "100000"),
                                    row(PREV_YMD, "100", "0", "0")));
            stubSingle("AAPL", resp);

            // Act
            service.collect(TODAY);

            // Assert — ET 당일 제외, 거래정지 행만 저장
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = captureInsert();
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().tradeDate())
                    .isEqualTo(LocalDate.of(2026, 6, 17));
            assertThat(captor.getValue().getFirst().volume()).isZero();
        }
    }

    @Nested
    @DisplayName("collect — zdiv 가드 (AC-VAL-4, REQ-OVOH-016)")
    class ZdivGuard {

        @Test
        @DisplayName("zdiv>4 — 종목 skip+WARN, 저장 0건")
        void collect_zdivAboveMax_skipsStock() throws Exception {
            stubSingle("AAPL", response("5", List.of(row(PREV_YMD, "1.40000", "1000", "1000"))));

            CollectionResult result = service.collect(TODAY);

            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
        }

        @Test
        @DisplayName("zdiv=4 — 정상 저장")
        void collect_zdiv4_inserted() throws Exception {
            stubSingle("AAPL", validResponse());

            CollectionResult result = service.collect(TODAY);

            verify(ohlcvInserter, times(1)).insertBatch(any(), any(), any(WatermarkSeries.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("zdiv 비숫자(NumberFormatException) — 보수적 상한 초과 처리, 종목 skip")
        void collect_zdivNonNumeric_skipsStock() throws Exception {
            // Arrange — "N/A"나 빈 문자열처럼 숫자 파싱 불가한 zdiv
            stubSingle("AAPL", response("N/A", List.of(row(PREV_YMD, "296.9200", "1000", "1000"))));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert — parseZdiv NFE 경로: ZDIV_MAX+1 반환 → zdiv>4 가드 발동 → skip
            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
        }
    }

    @Nested
    @DisplayName("collect — 빈응답 가드 (AC-VAL-5, REQ-OVOH-017)")
    class EmptyResponseGuard {

        @Test
        @DisplayName("rt_cd=0이나 output 빈값 — 종목 skip, 저장 0건")
        void collect_emptyOutput_skipsStock() throws Exception {
            // Arrange — GSAT@AMS 실측: rt_cd=0 + 빈 output
            KisOverseasDailyOhlcvResponse empty =
                    new KisOverseasDailyOhlcvResponse("0", "MCA00000", "정상", null, List.of());
            stubSingle("AAPL", empty);

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — ET 당일 행 가드 (AC-VAL-3, REQ-OVOH-015)")
    class EtTodayRowGuard {

        @Test
        @DisplayName("최신 행 xymd==ET today — 그 행 제외, 직전 영업일 행은 저장(KST 비교 시 무력화 회귀)")
        void collect_etTodayRowExcluded_prevBusinessDaySaved() throws Exception {
            // Arrange — 최신 행은 ET 당일(미마감 스냅샷 tvol 비정상), 직전 행은 확정
            KisOverseasDailyOhlcvResponse resp =
                    response(
                            "4",
                            List.of(
                                    row(TODAY_YMD, "296.9200", "78511", "23362105"),
                                    row(PREV_YMD, "295.9500", "42745060", "12697950974")));
            stubSingle("AAPL", resp);

            // Act
            service.collect(TODAY);

            // Assert — ET 당일 행(TODAY_YMD) 미저장, 직전 영업일 행(tvol=42745060)만 저장
            // insertBatch는 1회 호출되고, 배치에는 PREV_YMD 행만 포함 (ET today 가드로 TODAY_YMD 제외)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            List<ParsedOhlcvRow> saved = captor.getValue();
            assertThat(saved).hasSize(1);
            assertThat(saved.getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 6, 17));
            assertThat(saved.getFirst().volume()).isEqualTo(42_745_060L);
            assertThat(saved.getFirst().tradingValue()).isEqualTo(12_697_950_974L);
        }
    }

    @Nested
    @DisplayName("collect — 실패 종단 / 집계 (AC-VAL-2, AC-AGG-1, REQ-KISGATE-022)")
    class FailureAndAggregation {

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패")
        void collect_tokenIssue_gracefulSkip() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("token")));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("retryable 재시도 소진(KisRateLimitException 전파) — skip 집계 (구 BatchResult.skip 등가)")
        void collect_retryableExhausted_countedSkipped() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenThrow(new KisRateLimitException("isa", "EGW00201 소진"));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("일부 성공·일부 skip — attempted=succeeded+skipped (AC-AGG-1)")
        void collect_partialSuccess_aggregates() throws Exception {
            // Arrange — 첫 종목 성공, 둘째 종목 재시도 소진 skip
            Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(s1, s2));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse())
                    .thenThrow(new KisRateLimitException("gold", "skip"));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded() + result.skipped()).isEqualTo(result.attempted());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collectWindow — 백필 윈도우 메서드 (REQ-BACKFILL-002, T3)")
    class BackfillWindow {

        private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 31);

        private LeaseSession openSession() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            return new KeyLeaseRegistry(healthyKeySelector).openSession();
        }

        @Test
        @DisplayName("주어진 anchor를 BYMD 파라미터로 전송 (당일 ET 거래일과 무관)")
        @SuppressWarnings("unchecked")
        void collectWindow_sendsGivenAnchorAsBymd() throws Exception {
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            eq("HHDFS76240000"),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(response("4", List.of(row("20260130", "100", "1000", "1000"))));

            service.collectWindow(
                    stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession(), ANCHOR);

            String uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString();
            assertThat(uri).contains("BYMD=20260131");
            assertThat(uri).contains("MODP=0");
        }

        @Test
        @DisplayName("당일 수집과 동일한 검증·INSERT IGNORE 경로 재사용 (ohlcvInserter.insertBatch 호출)")
        void collectWindow_reusesValidateInsertPath() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            response(
                                    "4",
                                    List.of(
                                            row(
                                                    "20260130",
                                                    "296.9200",
                                                    "42745060",
                                                    "12697950974"))));

            service.collectWindow(
                    stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession(), ANCHOR);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ParsedOhlcvRow>> captor = ArgumentCaptor.forClass(List.class);
            verify(ohlcvInserter, times(1))
                    .insertBatch(any(), captor.capture(), any(WatermarkSeries.class));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().getFirst().tradeDate())
                    .isEqualTo(LocalDate.of(2026, 1, 30));
        }

        @Test
        @DisplayName("반환 결과 — 최소 거래일과 행 수를 노출")
        void collectWindow_returnsOldestAndRowCount() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            response(
                                    "4",
                                    List.of(
                                            row("20260130", "100", "1000", "1000"),
                                            row("20260128", "100", "1000", "1000"),
                                            row("20260129", "100", "1000", "1000"))));

            BackfillWindowResult result =
                    service.collectWindow(
                            stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession(), ANCHOR);

            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 28));
            assertThat(result.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 응답 — oldest=null, rowCount=0 (REQ-BACKFILL-012 입력)")
        void collectWindow_emptyResponse_zeroResult() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            new KisOverseasDailyOhlcvResponse(
                                    "0", "MCA00000", "정상", null, List.of()));

            BackfillWindowResult result =
                    service.collectWindow(
                            stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession(), ANCHOR);

            assertThat(result.oldestTradeDate()).isNull();
            assertThat(result.rowCount()).isZero();
            verifyNoInsert();
        }
    }

    @Nested
    @DisplayName("fetchWindow / persistWindow — fetch·persist 분리 (T3, REQ-TXBOUNDARY-002)")
    class FetchPersistWindow {

        private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 31);

        private LeaseSession openSession() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA, GOLD));
            return new KeyLeaseRegistry(healthyKeySelector).openSession();
        }

        @Test
        @DisplayName("fetchWindow — ohlcvInserter.insertBatch 미호출 (fetch 단계에서 INSERT 없음)")
        void fetchWindow_doesNotCallRepository() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            response(
                                    "4",
                                    List.of(
                                            row(
                                                    "20260130",
                                                    "296.9200",
                                                    "42745060",
                                                    "12697950974"))));

            OverseasDailyOhlcvFetch fetch =
                    service.fetchWindow(
                            ANCHOR, stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession());

            verify(ohlcvInserter, never()).insertBatch(any(), any(), any(WatermarkSeries.class));
            assertThat(fetch.rowCount()).isEqualTo(1);
            assertThat(fetch.rawRowCount()).isEqualTo(1);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 30));
        }

        @Test
        @DisplayName(
                "AC-11: fetchWindow — 원본 3건(volume=0 거래정지 1건 포함) → rawRowCount=3, rowCount=3 (전부 저장)")
        void fetchWindow_rawRowCount_haltRowIncluded() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            response(
                                    "4",
                                    List.of(
                                            row("20260130", "100", "1000", "100000"),
                                            row("20260129", "100", "0", "0"),
                                            row("20260128", "100", "1000", "100000"))));

            OverseasDailyOhlcvFetch fetch =
                    service.fetchWindow(
                            ANCHOR, stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession());

            assertThat(fetch.rawRowCount()).isEqualTo(3);
            assertThat(fetch.rowCount()).isEqualTo(3);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 28));
        }

        @Test
        @DisplayName(
                "aaa-infra#51 회귀 방지: fetchWindow — anchor 날짜 행이 rawRowCount에 포함됨 (ET today 가드 미적용)")
        void fetchWindow_anchorDateRow_includedInRawRowCount() throws Exception {
            // anchor=2026-01-31(과거 날짜) — fetchWindow 백필 경로는 ET today 가드 없음
            // anchor 날짜 행(20260131)이 rawRowCount·kept 모두에 포함되어야 한다
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            response(
                                    "4",
                                    List.of(
                                            row("20260131", "100", "1000", "100000"),
                                            row("20260130", "100", "0", "0"))));

            OverseasDailyOhlcvFetch fetch =
                    service.fetchWindow(
                            ANCHOR, stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession());

            // anchor 날짜(20260131) 포함 → rawRowCount=2, rowCount=2 (거래정지 행도 저장)
            assertThat(fetch.rawRowCount()).isEqualTo(2);
            assertThat(fetch.rowCount()).isEqualTo(2);
            assertThat(fetch.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 30));
        }

        @Test
        @DisplayName(
                "aaa-infra#51 회귀 방지: fetchWindow — 거래일 anchor 100행 응답 시 rawRowCount=100 (GROUP_A 오종료 방지)")
        void fetchWindow_tradingDayAnchor_rawRowCount100_preventsEarlyTermination()
                throws Exception {
            // Arrange — BYMD=거래일(anchor)이면 첫 행 xymd가 anchor와 같음.
            // 수정 전: isEtToday(xymd, anchor)=true → rawRowCount=99 → decideGroupA COMPLETED 오종료
            // 수정 후: ET 가드 미적용 → rawRowCount=100 → IN_PROGRESS 유지
            List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> hundredRows =
                    new java.util.ArrayList<>();
            // 첫 행 = anchor 날짜 (xymd == anchor)
            hundredRows.add(row("20260131", "100", "1000", "100000"));
            // 나머지 99행 (i>=31부터 날짜가 "20260101"로 중복됨 — 의도적, rawRowCount=100 검증이 목적)
            for (int i = 1; i <= 99; i++) {
                hundredRows.add(
                        row(
                                String.format("202601%02d", Math.max(1, 31 - i)),
                                "100",
                                "1000",
                                "100000"));
            }
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(response("4", hundredRows));

            OverseasDailyOhlcvFetch fetch =
                    service.fetchWindow(
                            ANCHOR, stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession());

            // rawRowCount=100 → BackfillTerminationPolicy.decideGroupA: 100 >= 100 → IN_PROGRESS
            assertThat(fetch.rawRowCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("fetchWindow — 빈 응답(output null) → rows=빈목록, oldestTradeDate=null")
        void fetchWindow_emptyOutput_emptyFetch() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(
                            new KisOverseasDailyOhlcvResponse(
                                    "0", "MCA00000", "정상", null, List.of()));

            OverseasDailyOhlcvFetch fetch =
                    service.fetchWindow(
                            ANCHOR, stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), openSession());

            assertThat(fetch.rows()).isEmpty();
            assertThat(fetch.oldestTradeDate()).isNull();
            assertThat(fetch.rowCount()).isZero();
        }

        @Test
        @DisplayName(
                "persistWindow — ohlcvInserter.insertBatch 호출 (persist에서 INSERT, REQ-INSERT-005)")
        void persistWindow_callsInserter() {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            ParsedOhlcvRow parsedRow =
                    new ParsedOhlcvRow(
                            LocalDate.of(2026, 1, 30),
                            new BigDecimal("296.9200"),
                            new BigDecimal("296.9200"),
                            new BigDecimal("296.9200"),
                            new BigDecimal("296.9200"),
                            42_745_060L,
                            12_697_950_974L);
            // rowCount=1(저장), rawRowCount=2(원본 — 거부 1건 가정) — persistWindow가 rawRowCount를 전달함을 검증
            OverseasDailyOhlcvFetch fetch =
                    new OverseasDailyOhlcvFetch(
                            List.of(parsedRow), LocalDate.of(2026, 1, 30), 1, 2);

            BackfillWindowResult result = service.persistWindow(stock, fetch);

            verify(ohlcvInserter, times(1)).insertBatch(any(), any(), any(WatermarkSeries.class));
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 1, 30));
            assertThat(result.rowCount()).isEqualTo(1);
            // AC-11: rawRowCount(원본 행수)는 rowCount(저장 행수)와 분리되어 그대로 전달된다
            assertThat(result.rawRowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("persistWindow — 빈 fetch → EMPTY 반환, inserter 미호출")
        void persistWindow_emptyFetch_returnsEmpty() {
            OverseasDailyOhlcvFetch emptyFetch = new OverseasDailyOhlcvFetch(List.of(), null, 0, 0);

            BackfillWindowResult result =
                    service.persistWindow(
                            stockOf("AAPL", Market.NASDAQ, AssetType.STOCK), emptyFetch);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(ohlcvInserter, never()).insertBatch(any(), any(), any(WatermarkSeries.class));
        }

        @Test
        @DisplayName("회귀 가드 — 당일 경로(collect) green 유지: saveValidRows가 collect에서 정상 동작")
        void dailyPathRegression_collectStillWorks() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class)))
                    .thenReturn(validResponse());

            CollectionResult result = service.collect(TODAY);

            assertThat(result.succeeded()).isEqualTo(1);
            verify(ohlcvInserter, times(1)).insertBatch(any(), any(), any(WatermarkSeries.class));
        }
    }

    // ── helpers ──

    private void stubSingle(String symbol, KisOverseasDailyOhlcvResponse resp)
            throws InterruptedException {
        Stock stock = stockOf(symbol, Market.NASDAQ, AssetType.STOCK);
        lenient().when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
        lenient().when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        lenient()
                .when(
                        guardedKisExecutor.execute(
                                any(LeaseSession.class),
                                any(),
                                anyString(),
                                eq(KisOverseasDailyOhlcvResponse.class)))
                .thenReturn(resp);
    }

    private void verifyNoInsert() {
        verify(ohlcvInserter, never()).insertBatch(any(), any(), any(WatermarkSeries.class));
    }
}
