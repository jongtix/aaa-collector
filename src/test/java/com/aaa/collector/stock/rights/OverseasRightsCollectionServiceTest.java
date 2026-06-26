package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OverseasRightsCollectionService 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>게이트 경유 멀티키 lease·종목별 Virtual Thread 병렬·현금배당 행만 매핑(RD-8)·비현금배당 skip·빈 응답 skip·독성 행 흡수·전 키 사망
 * 단락을 검증한다. {@code OverseasDailyOhlcvCollectionServiceTest} 패턴 답습 — 실제 {@link KeyLeaseRegistry} +
 * mock {@link HealthyKeySelector}로 openSession()을 구동한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasRightsCollectionService 단위 테스트")
class OverseasRightsCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventRepository corporateEventRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private OverseasRightsCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new OverseasRightsCollectionService(
                        stockRepository,
                        corporateEventRepository,
                        corporateEventInserter,
                        guardedKisExecutor,
                        keyLeaseRegistry);
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

    private KisOverseasRightsResponse.RightsRow cashDividendRow(
            String recordDt, String divLockDt, String payDt) {
        return new KisOverseasRightsResponse.RightsRow(
                "20260501", "현금배당", divLockDt, payDt, recordDt, "", "", "", "", "", "", "");
    }

    private KisOverseasRightsResponse response(List<KisOverseasRightsResponse.RightsRow> rows) {
        return new KisOverseasRightsResponse("0", "MCA00000", "정상", rows);
    }

    @Nested
    @DisplayName("collect — 대상 조회 / 게이트 진입")
    class Target {

        @Test
        @DisplayName("findAllActiveOverseasTradable() 진입점 호출 — 미국 STOCK+ETF 한정")
        void collect_callsOverseasTradableQuery() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            service.collect();

            verify(stockRepository).findAllActiveOverseasTradable();
        }

        @Test
        @DisplayName("빈 대상 — 게이트·selectHealthy 미호출, 0건")
        void collect_emptyTarget_noGateCall() throws Exception {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.attemptedStocks()).isZero();
            assertThat(result.succeededRows()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            verify(healthyKeySelector, never()).selectHealthy();
        }

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — per-stock 수집 0회, 전체 skip")
        void collect_allKeysDead_skipAll() throws Exception {
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stockOf("AAPL", Market.NASDAQ, AssetType.STOCK)));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.attemptedStocks()).isEqualTo(1);
            assertThat(result.skippedStocks()).isEqualTo(1);
            assertThat(result.succeededRows()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — 요청 파라미터 (REQ-OVE-021)")
    class RequestParams {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("NCOD=US + SYMB, ST_YMD/ED_YMD 공백으로 게이트 호출")
        void collect_buildsUsRequest() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class), uriCaptor.capture(), anyString(), any()))
                    .thenReturn(response(List.of()));

            // Act
            service.collect();

            // Assert
            URI uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(uri.toString())
                    .contains("/uapi/overseas-price/v1/quotations/rights-by-ice")
                    .contains("NCOD=US")
                    .contains("SYMB=AAPL")
                    .contains("ST_YMD=")
                    .contains("ED_YMD=");
        }

        @Test
        @DisplayName("TR ID = HHDFS78330900")
        void collect_usesCorrectTrId() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));

            ArgumentCaptor<String> trIdCaptor = ArgumentCaptor.forClass(String.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class), any(), trIdCaptor.capture(), any()))
                    .thenReturn(response(List.of()));

            service.collect();

            assertThat(trIdCaptor.getValue()).isEqualTo("HHDFS78330900");
        }
    }

    @Nested
    @DisplayName("collect — 현금배당 매핑 (RD-8, REQ-OVE-061/062)")
    class CashDividendMapping {

        @Test
        @DisplayName(
                "현금배당 행 — record_dt→event_date, div_lock_dt→ex_dividend_date, DIVIDEND+현금배당 매핑·멱등 저장")
        void collect_cashDividend_mapsAndPersists() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedMapping =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedMapping)
                    .extracting(
                            CorporateEvent::getEventType,
                            CorporateEvent::getEventDate,
                            CorporateEvent::getExDividendDate,
                            CorporateEvent::getPayDate,
                            CorporateEvent::getEventSubtype)
                    .containsExactly(
                            EventType.DIVIDEND,
                            LocalDate.of(2026, 5, 11),
                            LocalDate.of(2026, 5, 11),
                            LocalDate.of(2026, 5, 14),
                            "현금배당");
            assertThat(result.succeededRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("record_dt 누락 현금배당 행 — skip (필수 날짜)")
        void collect_cashDividendMissingRecordDt_skips() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(response(List.of(cashDividendRow("", "20260511", "20260514"))));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isZero();
            // W1: 검증 skip은 validation 카운터로만 — 독성 카운터와 분리됨을 함께 검증
            assertThat(result.skippedValidationRows()).isEqualTo(1);
            assertThat(result.skippedToxicRows()).isZero();
        }

        @Test
        @DisplayName("div_lock_dt 누락 현금배당 — ex_dividend_date NULL로 저장(선택 필드)")
        void collect_cashDividendMissingDivLockDt_persistsNullExDate() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(response(List.of(cashDividendRow("20260511", "", ""))));

            service.collect();

            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedNullDates =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedNullDates.getExDividendDate()).isNull();
            assertThat(savedNullDates.getPayDate()).isNull();
        }
    }

    @Nested
    @DisplayName("collect — 비현금배당 skip (REQ-OVE-023a)")
    class NonCashSkip {

        @Test
        @DisplayName("증자·상폐 등 비현금배당 행 — 저장하지 않고 skip")
        void collect_nonCashRows_skipped() throws Exception {
            // Arrange — 상폐 + 증자(비현금배당) 2건, 현금배당 0건
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasRightsResponse.RightsRow delist =
                    new KisOverseasRightsResponse.RightsRow(
                            "20260601",
                            "상장폐지",
                            "",
                            "",
                            "20260615",
                            "",
                            "",
                            "",
                            "20260620",
                            "",
                            "",
                            "");
            KisOverseasRightsResponse.RightsRow rightsIssue =
                    new KisOverseasRightsResponse.RightsRow(
                            "20260601",
                            "증자",
                            "",
                            "",
                            "20260615",
                            "",
                            "",
                            "20260618",
                            "",
                            "",
                            "",
                            "");
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(response(List.of(delist, rightsIssue)));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isZero();
            assertThat(result.skippedNonCashRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("현금배당 + 비현금배당 혼재 — 현금배당만 저장, 비현금은 skip")
        void collect_mixed_onlyCashPersisted() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasRightsResponse.RightsRow delist =
                    new KisOverseasRightsResponse.RightsRow(
                            "20260601",
                            "상장폐지",
                            "",
                            "",
                            "20260615",
                            "",
                            "",
                            "",
                            "20260620",
                            "",
                            "",
                            "");
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(
                            response(
                                    List.of(
                                            cashDividendRow("20260511", "20260511", "20260514"),
                                            delist)));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter, atLeastOnce()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isEqualTo(1);
            assertThat(result.skippedNonCashRows()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 빈 응답 / 독성 행 흡수")
    class EmptyAndToxic {

        @Test
        @DisplayName("rt_cd=0 + output1 빈 배열 — 종목 skip")
        void collect_emptyOutput_skipsStock() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(response(List.of()));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.skippedStocks()).isEqualTo(1);
        }

        @Test
        @DisplayName("독성 행 DataAccessException — 흡수하고 독성 카운터로만 집계, 검증 카운터와 분리 (W1)")
        void collect_toxicRow_absorbed() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            // REQ-INSERT-011: 독성 행에 대해 콜백 호출 시뮬레이션
            final java.sql.SQLException toxicEx = new java.sql.SQLException("toxic", "22001", 1406);
            doAnswer(
                            invocation -> {
                                List<CorporateEvent> rows = invocation.getArgument(0);
                                @SuppressWarnings("unchecked")
                                com.aaa.collector.observability.RowFailureHandler<CorporateEvent>
                                        handler = invocation.getArgument(1);
                                for (CorporateEvent entity : rows) {
                                    handler.onFailure(entity, toxicEx);
                                }
                                return null;
                            })
                    .when(corporateEventInserter)
                    .insertBatchIsolated(any(), any());

            // Act & Assert — 예외 전파 없이 흡수
            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            // W1: DB 독성 행은 독성 카운터로 — 검증 skip 카운터는 0으로 분리됨
            assertThat(result.skippedToxicRows()).isEqualTo(1);
            assertThat(result.skippedValidationRows()).isZero();
        }
    }

    @Nested
    @DisplayName("collect — Virtual Thread 병렬 (REQ-OVE-028)")
    class ParallelCollection {

        @Test
        @DisplayName("다종목 — 모든 종목이 동일 세션으로 게이트 호출, 각 현금배당 저장")
        void collect_multipleStocks_allProcessed() throws Exception {
            // Arrange
            List<Stock> stocks =
                    List.of(
                            stockOf("AAPL", Market.NASDAQ, AssetType.STOCK),
                            stockOf("MSFT", Market.NASDAQ, AssetType.STOCK),
                            stockOf("SPY", Market.AMEX, AssetType.ETF));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            lenient()
                    .when(
                            guardedKisExecutor.execute(
                                    any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            assertThat(result.attemptedStocks()).isEqualTo(3);
            assertThat(result.succeededRows()).isEqualTo(3);
            verify(corporateEventInserter, atLeastOnce()).insertBatchIsolated(any(), any());
        }
    }
}
