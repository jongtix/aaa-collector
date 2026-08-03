package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ShortSaleOverseasInterestCollectionService}의 Interest 경로 상장일 게이트 단위 테스트
 * (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M1, REQ-SSOI-005/-006/-007/-008, AC-05/-06/-07/-07a/-08).
 *
 * <p>{@code isGatedOut}은 private이므로 {@link FinraCdnDailyLoaderImplTest}의 {@code ListedDateGate}와
 * 동일하게 공개 진입점({@code collectShortInterest})을 통해 행동을 검증한다. {@link BatchMetrics}는 실제 registry 기반
 * 인스턴스를 사용해 신규 카운터의 등록·증분·타입을 정확히 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
        "ShortSaleOverseasInterestCollectionService — Interest 경로 상장일 게이트"
                + " (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M1)")
class ShortSaleOverseasInterestGateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final int INTEREST_LOOKBACK_DAYS = 40;

    @Mock private FinraShortSaleClient finraClient;
    @Mock private StockRepository stockRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private WatermarkMetrics watermarkMetrics;
    @Mock private BatchLastLoadRepository batchLastLoadRepository;

    private SimpleMeterRegistry meterRegistry;
    private ShortSaleOverseasInterestCollectionService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        BatchMetrics batchMetrics =
                new BatchMetrics(meterRegistry, Clock.systemDefaultZone(), batchLastLoadRepository);
        service =
                new ShortSaleOverseasInterestCollectionService(
                        finraClient,
                        stockRepository,
                        shortSaleOverseasRepository,
                        batchMetrics,
                        watermarkMetrics);
    }

    private static Stock stock(long id, String symbol, LocalDate listedDate) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(Market.NYSE)
                        .assetType(AssetType.STOCK)
                        .listedDate(listedDate)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static FinraConsolidatedShortInterestResponse row(
            String symbol, LocalDate settlementDate) {
        return new FinraConsolidatedShortInterestResponse(
                symbol, settlementDate, BigDecimal.valueOf(1_000_000L), null);
    }

    private static FinraConsolidatedShortInterestResponse rowWithIssueName(
            String symbol, LocalDate settlementDate, String issueName) {
        return new FinraConsolidatedShortInterestResponse(
                symbol,
                issueName,
                settlementDate,
                BigDecimal.valueOf(1_000_000L),
                null,
                null,
                null);
    }

    private void stubActiveStock(Stock stock) {
        when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
        when(shortSaleOverseasRepository.findExistingInterestPairsByStockIds(any(), any(), any()))
                .thenReturn(Map.of());
    }

    @Nested
    @DisplayName("AC-05: listedDate 확정 — 상장일 이전 행 제외 (SERV 실측 케이스)")
    class ListedDateNonNullExclusion {

        @Test
        @DisplayName(
                "SERV(listedDate=2024-03-08)의 舊 ServiceMaster Global Holdings 구간(2020-06-30) 행은 제외되고"
                        + " 게이트 카운터가 1 증가한다")
        void servLegacyOwnerSegment_excludedByGate() {
            // Arrange: spec.md §1.2 실측 근거 — SERV 舊 소유자 구간(2017-12-29~2020) SI 오염
            LocalDate servListedDate = LocalDate.of(2024, 3, 8);
            LocalDate legacyOwnerSettlementDate = LocalDate.of(2020, 6, 30);
            Stock serv = stock(1L, "SERV", servListedDate);
            stubActiveStock(serv);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("SERV", legacyOwnerSettlementDate)));

            // Act
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert: 적재 미호출 + skipped=1 + 게이트 카운터 1
            verify(shortSaleOverseasRepository, never())
                    .upsertInterest(any(), any(), any(), any(), any(), any());
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(
                            meterRegistry
                                    .get("aaa_collector_finra_interest_gate_skip_total")
                                    .counter()
                                    .count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("AC-06/-07: listedDate 미상 — 최근 구간(40일)은 통과, 과거 구간은 방어적 차단")
    class ListedDateNullWindowGate {

        @Test
        @DisplayName("AC-06: listedDate=null + settlementDate가 최근 40일 이내면 게이트 없이 정상 적재된다")
        void nullListedDate_withinRecentWindow_persistsWithoutGate() {
            // Arrange: EC-04 — Yahoo 취득 실패로 listedDate가 null로 남은 신규 워치리스트 종목
            LocalDate recentSettlementDate = TODAY.minusDays(10);
            Stock newlyWatched = stock(2L, "NEWCO", null);
            stubActiveStock(newlyWatched);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("NEWCO", recentSettlementDate)));

            // Act
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert: 정상 적재, 게이트 카운터 미증가
            verify(shortSaleOverseasRepository)
                    .upsertInterest(eq(2L), eq(recentSettlementDate), any(), any(), any(), any());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isZero();
            assertThat(
                            meterRegistry
                                    .get("aaa_collector_finra_interest_gate_skip_total")
                                    .counter()
                                    .count())
                    .isZero();
        }

        @Test
        @DisplayName("AC-07: listedDate=null + settlementDate가 40일을 초과해 과거면 제외되고 게이트 카운터가 증가한다")
        void nullListedDate_beyondRecentWindow_excludedByGate() {
            // Arrange: 최근 구간(40일) 밖 — 방어적 차단(002 REQ-SSOG-014의 무게이트 정책과 의도적으로 다름)
            LocalDate staleSettlementDate = TODAY.minusDays(INTEREST_LOOKBACK_DAYS + 1);
            Stock newlyWatched = stock(3L, "OLDCO", null);
            stubActiveStock(newlyWatched);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("OLDCO", staleSettlementDate)));

            // Act
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert: 적재 미호출 + skipped=1 + 게이트 카운터 1
            verify(shortSaleOverseasRepository, never())
                    .upsertInterest(any(), any(), any(), any(), any(), any());
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(
                            meterRegistry
                                    .get("aaa_collector_finra_interest_gate_skip_total")
                                    .counter()
                                    .count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("AC-07a: 경계값 — settlementDate == today.minusDays(INTEREST_LOOKBACK_DAYS)")
    class BoundaryExactly40Days {

        @Test
        @DisplayName("경계 정확히 40일째는 isBefore(recentWindowStart)가 false이므로 게이트 없이 통과한다")
        void exactBoundary_isNotBeforeRecentWindowStart_passesGate() {
            // Arrange: settlementDate = today - 40일 (recentWindowStart와 정확히 동일 — isBefore=false)
            LocalDate boundarySettlementDate = TODAY.minusDays(INTEREST_LOOKBACK_DAYS);
            Stock newlyWatched = stock(4L, "BNDCO", null);
            stubActiveStock(newlyWatched);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("BNDCO", boundarySettlementDate)));

            // Act
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert: 경계값은 포함(통과) — plan.md isBefore(recentWindowStart) 규칙 고정
            verify(shortSaleOverseasRepository)
                    .upsertInterest(eq(4L), eq(boundarySettlementDate), any(), any(), any(), any());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isZero();
            assertThat(
                            meterRegistry
                                    .get("aaa_collector_finra_interest_gate_skip_total")
                                    .counter()
                                    .count())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("AC-08: 신규 Interest 게이트 카운터는 CDN Daily와 별개 시계열로 0값 등록된다")
    class CounterRegistration {

        @Test
        @DisplayName("게이트 스킵이 0건이어도 카운터가 등록되어 있고 CDN Daily 카운터와 이름이 다르다")
        void counterRegisteredAtZero_distinctFromCdnDailyCounter() {
            // Arrange: 게이트 스킵이 발생하지 않는 정상 케이스
            Stock aapl = stock(5L, "AAPL", LocalDate.of(2000, 1, 1));
            stubActiveStock(aapl);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("AAPL", TODAY.minusDays(1))));

            // Act
            service.collectShortInterest(TODAY);

            // Assert: 신규 카운터가 0값으로 등록되어 있고(계측 연결 확인), Counter 타입이며 CDN Daily 카운터와 별개 이름
            Meter meter = meterRegistry.get("aaa_collector_finra_interest_gate_skip_total").meter();
            assertThat(meter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
            assertThat(meterRegistry.find("aaa_collector_finra_ticker_reuse_skip_total").counter())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("M4: 게이트 제외 진단 로깅에 issueName이 포함된다 (REQ-SSOI-009, plan.md 핵심 아키텍처 결정 4)")
    class GateExclusionDiagnosticLogging {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> listAppender;

        @BeforeEach
        void attachLogAppender() {
            serviceLogger =
                    (Logger)
                            LoggerFactory.getLogger(
                                    ShortSaleOverseasInterestCollectionService.class);
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
        @DisplayName("게이트 제외 행의 舊 소유자 issueName이 로그 라인에 그대로 나타난다")
        void gatedOutRow_logsIssueNameForTriageWithoutException() {
            // Arrange: SERV 실측 케이스 재사용 — 舊 소유자 ServiceMaster Global Holdings 구간
            LocalDate servListedDate = LocalDate.of(2024, 3, 8);
            LocalDate legacyOwnerSettlementDate = LocalDate.of(2020, 6, 30);
            String legacyOwnerIssueName = "ServiceMaster Global Holdings, Inc.";
            Stock serv = stock(1L, "SERV", servListedDate);
            stubActiveStock(serv);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(
                            List.of(
                                    rowWithIssueName(
                                            "SERV",
                                            legacyOwnerSettlementDate,
                                            legacyOwnerIssueName)));

            // Act — 예외 없이 실행되어야 한다(마일스톤 완료 조건)
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert: 게이트 제외 결과는 기존과 동일 + 로그 라인에 issueName 포함
            assertThat(result.skipped()).isEqualTo(1);
            List<ILoggingEvent> gateExclusionLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.INFO)
                            .filter(e -> e.getFormattedMessage().contains("상장일 게이트 제외"))
                            .toList();
            assertThat(gateExclusionLogs).hasSize(1);
            assertThat(gateExclusionLogs.getFirst().getFormattedMessage())
                    .contains("issueName=" + legacyOwnerIssueName)
                    .contains("symbol=SERV")
                    .contains("settlementDate=" + legacyOwnerSettlementDate)
                    .contains("listedDate=" + servListedDate);
        }

        @Test
        @DisplayName("issueName이 null이어도 예외 없이 실행되고 로그 라인에 issueName=null로 남는다")
        void gatedOutRow_nullIssueName_logsWithoutException() {
            // Arrange: FINRA 응답에 issueName이 없는 방어적 케이스
            LocalDate staleSettlementDate = TODAY.minusDays(INTEREST_LOOKBACK_DAYS + 1);
            Stock oldco = stock(3L, "OLDCO", null);
            stubActiveStock(oldco);
            when(finraClient.fetchConsolidatedShortInterestForSymbols(any(), any(), any()))
                    .thenReturn(List.of(row("OLDCO", staleSettlementDate)));

            // Act
            ShortSaleOverseasInterestCollectionService.InterestResult result =
                    service.collectShortInterest(TODAY);

            // Assert
            assertThat(result.skipped()).isEqualTo(1);
            List<ILoggingEvent> gateExclusionLogs =
                    listAppender.list.stream()
                            .filter(e -> e.getLevel() == Level.INFO)
                            .filter(e -> e.getFormattedMessage().contains("상장일 게이트 제외"))
                            .toList();
            assertThat(gateExclusionLogs).hasSize(1);
            assertThat(gateExclusionLogs.getFirst().getFormattedMessage())
                    .contains("issueName=null");
        }
    }
}
