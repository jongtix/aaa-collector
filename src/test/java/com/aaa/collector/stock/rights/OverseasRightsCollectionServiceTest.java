package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.assertj.core.groups.Tuple;
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
 * OverseasRightsCollectionService 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-ETC-001,
 * SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001).
 *
 * <p>게이트 경유 멀티키 lease·종목별 Virtual Thread 병렬·현금배당 행만 매핑(RD-8)·비현금배당 skip·빈 응답 skip·독성 행 흡수·전 키 사망
 * 단락을 검증한다. CTRGT011R 프리페치 자체(페이징·fail-closed·03/75 독립성 등)는 {@link DividendAmountPrefetcher}로 위임되어
 * {@link DividendAmountPrefetcherTest}가 커버하므로, 본 테스트는 {@link DividendAmountPrefetcher}를 mock하여
 * 병합·행생성(경로 A)·defer·카운터 전달 로직만 검증한다. {@code OverseasDailyOhlcvCollectionServiceTest} 패턴 답습 — 실제
 * {@link KeyLeaseRegistry} + mock {@link HealthyKeySelector}로 openSession()을 구동한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasRightsCollectionService 단위 테스트")
class OverseasRightsCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final String RIGHT_TYPE_GENERAL = DividendAmountPrefetcher.RIGHT_TYPE_GENERAL;
    private static final String RIGHT_TYPE_SPECIAL = DividendAmountPrefetcher.RIGHT_TYPE_SPECIAL;

    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private UsMarketOpenGate usMarketOpenGate;
    @Mock private DividendAmountPrefetcher dividendAmountPrefetcher;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private OverseasRightsCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        // 기존 테스트는 휴장일 게이트 행동을 검증하지 않으므로 always-open으로 스텁한다
        lenient().when(usMarketOpenGate.isOpenDay(any(LocalDate.class))).thenReturn(true);
        // 기본값: 확정 매칭 없는 빈 프리페치 — 명시적으로 재정의하지 않는 테스트는 전부 미확정 defer(REQ-ODA-022) 경로를 탄다.
        lenient()
                .when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                .thenReturn(emptyPrefetch());
        service =
                new OverseasRightsCollectionService(
                        stockRepository,
                        corporateEventInserter,
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        usMarketOpenGate,
                        dividendAmountPrefetcher);
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

    // ── DividendAmountPrefetch 테스트 보조 ─────────────────────────────────────

    private DividendAmountItem item(String rghtTypeCd, String amount, String currencyCode) {
        return new DividendAmountItem(
                rghtTypeCd,
                new BigDecimal(amount),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                currencyCode);
    }

    private DividendAmountPrefetch emptyPrefetch() {
        return new DividendAmountPrefetch(Map.of(), Set.of(), 0, 0);
    }

    /** 단일 (symbol, acplBassDt) 키에 항목 리스트를 매핑한 정상(비degraded) 프리페치. */
    private DividendAmountPrefetch prefetchWith(
            String symbol, LocalDate acplBassDt, List<DividendAmountItem> items) {
        Map<DividendAmountKey, List<DividendAmountItem>> map =
                Map.of(new DividendAmountKey(symbol, acplBassDt), List.copyOf(items));
        return new DividendAmountPrefetch(map, Set.of(), 0, 0);
    }

    /** 단일 (symbol, acplBassDt) 키가 74(스크립배당)로 확정 관측된 프리페치(amountsByKey는 비어 있음). */
    private DividendAmountPrefetch scripPrefetch(String symbol, LocalDate acplBassDt) {
        return new DividendAmountPrefetch(
                Map.of(), Set.of(new DividendAmountKey(symbol, acplBassDt)), 0, 0);
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
        @DisplayName("빈 대상 — 게이트·selectHealthy·프리페치 미호출, 0건")
        void collect_emptyTarget_noGateCall() throws Exception {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.attemptedStocks()).isZero();
            assertThat(result.succeededRows()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
            verify(healthyKeySelector, never()).selectHealthy();
            verify(dividendAmountPrefetcher, never()).prefetch(any(), any());
        }

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — per-stock 수집 0회, 전체 skip, 프리페치도 미실행")
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
            verify(dividendAmountPrefetcher, never()).prefetch(any(), any());
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
                            any(LeaseSession.class),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
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
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(response(List.of()));

            service.collect();

            verify(guardedKisExecutor)
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            eq("HHDFS78330900"),
                            eq(KisOverseasRightsResponse.class));
        }

        @Test
        @DisplayName("프리페치가 활성 종목 심볼 집합으로 호출된다")
        void collect_prefetchCalledWithTrackedSymbols() throws Exception {
            Stock aapl = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock msft = stockOf("MSFT", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl, msft));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(response(List.of()));

            service.collect();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> symbolsCaptor = ArgumentCaptor.forClass(Set.class);
            verify(dividendAmountPrefetcher)
                    .prefetch(any(LeaseSession.class), symbolsCaptor.capture());
            assertThat(symbolsCaptor.getValue()).containsExactlyInAnyOrder("AAPL", "MSFT");
        }
    }

    @Nested
    @DisplayName(
            "collect — 현금배당 + 프리페치 확정 매칭 병합 (AC-1, REQ-OVE-061/062, REQ-ODA-020~023, 043, 046)")
    class CashDividendMapping {

        @Test
        @DisplayName(
                "AC-1: 일반배당(03) 확정 매칭 — record_dt→event_date, div_lock_dt→ex_dividend_date, "
                        + "event_subtype=일반배당, cash_amount/currency_code 채움")
        void collect_generalDividend_mapsAndPersists() throws Exception {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(
                            prefetchWith(
                                    "AAPL",
                                    LocalDate.of(2026, 5, 11),
                                    List.of(item(RIGHT_TYPE_GENERAL, "0.26000", "USD"))));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved)
                    .extracting(
                            CorporateEvent::getEventType,
                            CorporateEvent::getEventDate,
                            CorporateEvent::getExDividendDate,
                            CorporateEvent::getPayDate,
                            CorporateEvent::getEventSubtype,
                            CorporateEvent::getCurrencyCode)
                    .containsExactly(
                            EventType.DIVIDEND,
                            LocalDate.of(2026, 5, 11),
                            LocalDate.of(2026, 5, 11),
                            LocalDate.of(2026, 5, 14),
                            "일반배당",
                            "USD");
            assertThat(saved.getCashAmount()).isEqualByComparingTo("0.26000");
            assertThat(result.succeededRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-1b: 특별배당(75) 확정 매칭 — event_subtype=특별배당 (03만 조회 시 누락되었을 사례, D1)")
        void collect_specialDividend_mapsAndPersists() throws Exception {
            Stock stock = stockOf("SD", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260520", "20260520", "20260525"))));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(
                            prefetchWith(
                                    "SD",
                                    LocalDate.of(2026, 5, 20),
                                    List.of(item(RIGHT_TYPE_SPECIAL, "0.20000", "USD"))));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getEventSubtype()).isEqualTo("특별배당");
            assertThat(saved.getCashAmount()).isEqualByComparingTo("0.20000");
            assertThat(result.succeededRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-1d: 동일일자 03+75 병존 — 별도 2행 생성(SUM 금지, 경로 A, RD-13)")
        void collect_generalAndSpecialSameDate_createsTwoSeparateRows() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(
                            prefetchWith(
                                    "AAPL",
                                    LocalDate.of(2026, 5, 11),
                                    List.of(
                                            item(RIGHT_TYPE_GENERAL, "0.50000", "USD"),
                                            item(RIGHT_TYPE_SPECIAL, "2.00000", "USD"))));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            List<CorporateEvent> saved =
                    inserterCaptor.getAllValues().stream().flatMap(List::stream).toList();
            assertThat(saved).hasSize(2);
            assertThat(saved)
                    .extracting(CorporateEvent::getEventSubtype, CorporateEvent::getCashAmount)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple("일반배당", new BigDecimal("0.50000")),
                            Tuple.tuple("특별배당", new BigDecimal("2.00000")));
            assertThat(result.succeededRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("record_dt 누락 현금배당 행 — skip (필수 날짜)")
        void collect_cashDividendMissingRecordDt_skips() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(response(List.of(cashDividendRow("", "20260511", "20260514"))));

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isZero();
            // W1: 검증 skip은 validation 카운터로만 — 독성 카운터와 분리됨을 함께 검증
            assertThat(result.skippedValidationRows()).isEqualTo(1);
            assertThat(result.skippedToxicRows()).isZero();
        }
    }

    @Nested
    @DisplayName("collect — 미확정/미매칭 defer (AC-2, REQ-ODA-022, 030, D5)")
    class UnconfirmedDefer {

        @Test
        @DisplayName("프리페치 확정 매칭 없음(기본 빈 프리페치) — 행 생성 없이 skippedUnconfirmed 증가")
        void collect_noConfirmedMatch_defersRow() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            // dividendAmountPrefetcher는 setUp() 기본값(빈 프리페치) 유지 — 확정 매칭 없음

            OverseasRightsCollectionResult result = service.collect();

            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isZero();
            assertThat(result.skippedUnconfirmed()).isEqualTo(1);
        }

        @Test
        @DisplayName("D5: 프리페치 유형 폐기(degraded)로 맵이 비어도 skippedUnconfirmed에 이중 계상하지 않고 카운터로만 전달")
        void collect_degradedPrefetch_doesNotDoubleCountSkippedUnconfirmed() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            // 유형 폐기(절단)로 맵이 비어있는 degraded 프리페치
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(new DividendAmountPrefetch(Map.of(), Set.of(), 1, 0));

            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            assertThat(result.prefetchTruncated()).isEqualTo(1);
            assertThat(result.prefetchFailed()).isZero();
            // D5: 유형 폐기로 인한 defer는 skippedUnconfirmed에 이중 계상되지 않는다
            assertThat(result.skippedUnconfirmed()).isZero();
        }

        @Test
        @DisplayName("프리페치 전면 실패(빈 맵 + prefetchFailed=2) — 해외 배당 행 0건, 카운터 그대로 전달(AC-6)")
        void collect_totalPrefetchFailure_noRowsCreated() throws Exception {
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(new DividendAmountPrefetch(Map.of(), Set.of(), 0, 2));

            OverseasRightsCollectionResult result = service.collect();

            assertThat(result.succeededRows()).isZero();
            assertThat(result.prefetchFailed()).isEqualTo(2);
            assertThat(result.skippedUnconfirmed()).isZero();
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
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
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(response(List.of(delist, rightsIssue)));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.succeededRows()).isZero();
            assertThat(result.skippedNonCashRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("현금배당(확정 매칭) + 비현금배당 혼재 — 현금배당만 저장, 비현금은 skip")
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
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(
                                    List.of(
                                            cashDividendRow("20260511", "20260511", "20260514"),
                                            delist)));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(
                            prefetchWith(
                                    "AAPL",
                                    LocalDate.of(2026, 5, 11),
                                    List.of(item(RIGHT_TYPE_GENERAL, "0.26000", "USD"))));

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
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
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
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(
                            prefetchWith(
                                    "AAPL",
                                    LocalDate.of(2026, 5, 11),
                                    List.of(item(RIGHT_TYPE_GENERAL, "0.26000", "USD"))));
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
        @DisplayName("다종목 — 모든 종목이 동일 세션으로 게이트 호출, 각 확정 매칭 현금배당 저장")
        void collect_multipleStocks_allProcessed() throws Exception {
            // Arrange
            Stock aapl = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock msft = stockOf("MSFT", Market.NASDAQ, AssetType.STOCK);
            Stock spy = stockOf("SPY", Market.AMEX, AssetType.ETF);
            List<Stock> stocks = List.of(aapl, msft, spy);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(stocks);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            lenient()
                    .when(
                            guardedKisExecutor.execute(
                                    any(LeaseSession.class),
                                    any(),
                                    anyString(),
                                    eq(KisOverseasRightsResponse.class)))
                    .thenReturn(
                            response(List.of(cashDividendRow("20260511", "20260511", "20260514"))));
            LocalDate eventDate = LocalDate.of(2026, 5, 11);
            Map<DividendAmountKey, List<DividendAmountItem>> map =
                    Map.of(
                            new DividendAmountKey("AAPL", eventDate),
                            List.of(item(RIGHT_TYPE_GENERAL, "0.26000", "USD")),
                            new DividendAmountKey("MSFT", eventDate),
                            List.of(item(RIGHT_TYPE_GENERAL, "0.68000", "USD")),
                            new DividendAmountKey("SPY", eventDate),
                            List.of(item(RIGHT_TYPE_GENERAL, "1.50000", "USD")));
            when(dividendAmountPrefetcher.prefetch(any(LeaseSession.class), any()))
                    .thenReturn(new DividendAmountPrefetch(map, Set.of(), 0, 0));

            // Act
            OverseasRightsCollectionResult result = service.collect();

            // Assert
            assertThat(result.attemptedStocks()).isEqualTo(3);
            assertThat(result.succeededRows()).isEqualTo(3);
            verify(corporateEventInserter, atLeastOnce()).insertBatchIsolated(any(), any());
        }
    }
}
