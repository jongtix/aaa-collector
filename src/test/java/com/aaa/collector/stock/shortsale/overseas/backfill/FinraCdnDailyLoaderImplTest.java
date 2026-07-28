package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link FinraCdnDailyLoaderImpl} 단위 테스트 (SPEC-COLLECTOR-BACKFILL-008 T3/T4,
 * SPEC-COLLECTOR-BACKFILL-011 §2.6).
 *
 * <p>{@link FinraCdnShortSaleBackfillOrchestrator}에서 추출한 시설 합산·심볼 매칭·UPSERT·kept/raw 계산 로직을 오케스트레이터
 * 없이 직접 검증한다(코드리뷰 — PMD CouplingBetweenObjects 완화 리팩터로 이관된 테스트).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinraCdnDailyLoaderImpl — 시설 합산·심볼 매칭·kept/raw (SPEC-COLLECTOR-BACKFILL-008/-011)")
class FinraCdnDailyLoaderImplTest {

    @Mock private FinraCdnFileParser parser;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private BatchLastLoadRepository batchLastLoadRepository;

    /**
     * 실 {@link BatchMetrics} 인스턴스 — AC-18(Counter 증분·타입 assert)을 검증하려면 mock이 아닌 실제 registry 기반 계측이
     * 필요하다(SimpleMeterRegistry, 다른 AC에서는 계측 부수효과로 취급해 assert하지 않음).
     */
    private SimpleMeterRegistry meterRegistry;

    private FinraCdnDailyLoaderImpl loader;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        BatchMetrics batchMetrics =
                new BatchMetrics(meterRegistry, Clock.systemDefaultZone(), batchLastLoadRepository);
        loader = new FinraCdnDailyLoaderImpl(parser, shortSaleOverseasRepository, batchMetrics);
    }

    private static Stock stock(long id, String symbol) {
        return stock(id, symbol, null);
    }

    private static Stock stock(long id, String symbol, LocalDate listedDate) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(listedDate)
                        .build();
        ReflectionTestUtils.setField(stock, "id", id);
        return stock;
    }

    private static ParsedRow row(String symbol, long shortVol, long totalVol) {
        return new ParsedRow(symbol, BigDecimal.valueOf(shortVol), BigDecimal.valueOf(totalVol));
    }

    private static BigDecimal bd(String value) {
        BigDecimal expected = new BigDecimal(value);
        return argThat(actual -> actual != null && actual.compareTo(expected) == 0);
    }

    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 테스트 전용, 이후 읽기만 함
    private static Map<String, Stock> symbolMap(Stock... stocks) {
        Map<String, Stock> map = new HashMap<>();
        for (Stock s : stocks) {
            map.put(s.getSymbol(), s);
        }
        return map;
    }

    @Nested
    @DisplayName("시설 다중 파일 합산 (AC-BF-04)")
    class FacilitySummation {

        @Test
        @DisplayName("시설 2개 파일 존재 시 종목별 short/total volume이 파일 합과 일치한다")
        void multiFacilityFiles_summedPerStock() {
            LocalDate target = LocalDate.of(2013, 1, 2);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "AAPL"));
            when(parser.parse("FNSQ-BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("AAPL", 100, 1000)), 0));
            when(parser.parse("FNYX-BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("AAPL", 50, 500)), 0));

            FinraCdnDailyLoadOutcome outcome =
                    loader.loadDate(target, List.of("FNSQ-BODY", "FNYX-BODY"), symbolMap);

            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(target),
                            bd("150"),
                            bd("1500"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(outcome.kept()).isEqualTo(1);
            assertThat(outcome.raw()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("종목 매칭 — 범위·정규화·워런트 제외 (AC-BF-08/-09/-11)")
    class SymbolMatching {

        @Test
        @DisplayName("슬래시 클래스주식(BRK/B)은 정규화되어 매칭, 워런트(/WS)·범위 밖 종목은 자연 제외된다")
        void matching_normalizesSlashAndExcludesOutOfScope() {
            LocalDate target = LocalDate.of(2013, 1, 2);
            Map<String, Stock> symbolMap = symbolMap(stock(2L, "BRK.B"));
            when(parser.parse("BODY"))
                    .thenReturn(
                            new ParsedFileResult(
                                    List.of(
                                            row("BRK/B", 10, 100),
                                            row("AAPL/WS", 5, 50),
                                            row("MSFT", 20, 200)),
                                    0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(2L),
                            eq(target),
                            bd("10"),
                            bd("100"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            verify(shortSaleOverseasRepository, times(1))
                    .upsertDaily(
                            anyLong(),
                            eq(target),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(outcome.kept()).isEqualTo(1);
            assertThat(outcome.unmatched()).isEqualTo(2);
            assertThat(outcome.raw()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("upsertDaily interest 파라미터 null 계약 (AC-BF-20/-21)")
    class UpsertInterestNullContract {

        @Test
        @DisplayName("백필 적재는 shortInterest/shortInterestDate에 항상 null을 전달한다")
        void loadDate_alwaysPassesNullInterestParams() {
            LocalDate target = LocalDate.of(2013, 1, 2);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "AAPL"));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("AAPL", 10, 100)), 0));

            loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(target),
                            bd("10"),
                            bd("100"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
        }
    }

    @Nested
    @DisplayName("관측성 — kept/raw/skipped/unmatched 집계")
    class ObservabilityCounts {

        @Test
        @DisplayName("파싱 skip·매칭 실패가 혼재해도 예외 없이 kept=0으로 집계된다")
        void mixedFailures_countsSkippedAndUnmatched() {
            LocalDate target = LocalDate.of(2013, 1, 3);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "AAPL"));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("UNMATCHED", 1, 2)), 3));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(outcome.kept()).isZero();
            assertThat(outcome.skipped()).isEqualTo(3);
            assertThat(outcome.unmatched()).isEqualTo(1);
            assertThat(outcome.raw()).isEqualTo(1);
        }

        @Test
        @DisplayName("파일 본문이 비어 있으면 raw=kept=0")
        void emptyFileBodies_zeroOutcome() {
            LocalDate target = LocalDate.of(2013, 1, 4);
            Map<String, Stock> symbolMap = symbolMap();

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of(), symbolMap);

            assertThat(outcome.kept()).isZero();
            assertThat(outcome.raw()).isZero();
            assertThat(outcome.skipped()).isZero();
            assertThat(outcome.unmatched()).isZero();
        }
    }

    @Nested
    @DisplayName("상장일 게이트 — 티커 재사용 오염 차단 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 REQ-SSOG-013~018)")
    class ListedDateGate {

        @Test
        @DisplayName("AC-13: 거래일이 상장일보다 이르면 upsert 미호출 + gateExcluded +1")
        void tradeDateBeforeListedDate_excludesRow() {
            LocalDate armListedDate = LocalDate.of(2023, 9, 14);
            LocalDate target = LocalDate.of(2011, 3, 29);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "ARM", armListedDate));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("ARM", 10, 100)), 0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(outcome.gateExcluded()).isEqualTo(1);
            assertThat(outcome.kept()).isZero();
        }

        /**
         * AC-13a: DELL 회귀 고정 — DELL은 현재(2016-08-17 상장, 舊Dell Inc 대비 신규 IPO) CIK 1571996이며, 舊Dell
         * Inc(2013 상장폐지, CIK 826083)와는 별개 법인이다. FINRA 심볼 재사용으로 유입되는 2009~2013 舊Dell 구간(CIK 826083)은
         * 현재 DELL 종목 레코드와 무관한 오염 데이터이므로 게이트가 반드시 차단해야 한다(spec.md §2 B2 케이스북, CIK 실측 근거).
         */
        @Test
        @DisplayName("AC-13a: DELL 舊법인(CIK 826083) 구간은 상장일 게이트로 제외된다")
        void dellLegacyCikSegment_excludedByGate() {
            LocalDate dellListedDate =
                    LocalDate.of(2016, 8, 17); // 현 DELL(CIK 1571996) Yahoo 최초 거래일
            LocalDate target = LocalDate.of(2011, 6, 15); // 舊Dell Inc(CIK 826083) 구간 — 오염
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "DELL", dellListedDate));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("DELL", 10, 100)), 0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(outcome.gateExcluded()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-14: 상장일 미상(NULL)이면 게이트 미적용 — upsert 정상 호출")
        void listedDateNull_gateNotApplied() {
            LocalDate target = LocalDate.of(2010, 1, 5);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "Z", null));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("Z", 10, 100)), 0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository, times(1))
                    .upsertDaily(
                            eq(1L),
                            eq(target),
                            bd("10"),
                            bd("100"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(outcome.gateExcluded()).isZero();
        }

        @Test
        @DisplayName("AC-15: 거래일이 상장일과 같으면(경계 당일) 정상 적재된다")
        void tradeDateEqualsListedDate_loadsNormally() {
            LocalDate coinListedDate = LocalDate.of(2021, 4, 14);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "COIN", coinListedDate));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("COIN", 10, 100)), 0));

            FinraCdnDailyLoadOutcome outcome =
                    loader.loadDate(coinListedDate, List.of("BODY"), symbolMap);

            verify(shortSaleOverseasRepository, times(1))
                    .upsertDaily(
                            eq(1L),
                            eq(coinListedDate),
                            bd("10"),
                            bd("100"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(outcome.gateExcluded()).isZero();
        }

        @Test
        @DisplayName("AC-16: 미등록 심볼(unmatched)과 게이트 제외(gateExcluded)는 독립 집계된다")
        void unmatchedAndGateExcluded_countedIndependently() {
            LocalDate target = LocalDate.of(2013, 1, 2);
            LocalDate keptListedDate = LocalDate.of(2000, 1, 1);
            LocalDate excludedListedDate = LocalDate.of(2020, 1, 1); // target보다 이후 — 거래일이 상장일보다 이름
            Map<String, Stock> symbolMap =
                    symbolMap(
                            stock(1L, "KEPT", keptListedDate),
                            stock(2L, "EXCLUDED", excludedListedDate));
            when(parser.parse("BODY"))
                    .thenReturn(
                            new ParsedFileResult(
                                    List.of(
                                            row("UNMATCHED", 1, 2),
                                            row("EXCLUDED", 3, 4),
                                            row("KEPT", 5, 6)),
                                    0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            assertThat(outcome.unmatched()).isEqualTo(1);
            assertThat(outcome.gateExcluded()).isEqualTo(1);
            assertThat(outcome.kept()).isEqualTo(1);
        }

        @Test
        @DisplayName(
                "AC-17: 정방향 covered-gap walk 경로(FinraCdnCoveredGapFiller)도 동일 loadDate를 통해 게이트가"
                        + " 관통된다")
        void forwardCoveredGapWalkPath_gateAppliesThroughSameLoader() {
            LocalDate armListedDate = LocalDate.of(2023, 9, 14);
            LocalDate cursor = LocalDate.of(2011, 3, 29);
            Map<String, Stock> symbolMap = symbolMap(stock(1L, "ARM", armListedDate));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(row("ARM", 10, 100)), 0));
            FinraCdnDailyFileClient client = mock(FinraCdnDailyFileClient.class);
            when(client.fetch(cursor)).thenReturn(new FinraCdnFetchResult.Found(List.of("BODY")));
            // 별도 배선 없이 오케스트레이터 backward walk와 동일한 FinraCdnDailyLoaderImpl 인스턴스를 주입한다(AC-17).
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, symbolMap);

            CoveredFillResult result = filler.persistStep(cursor);

            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(result.kept()).isZero();
        }

        @Test
        @DisplayName("AC-18: 게이트 제외 3건 발생 시 Counter가 3 증가하고 타입이 정확히 Counter다")
        void gateExclusion_incrementsCounterWithCorrectType() {
            LocalDate listedDate = LocalDate.of(2023, 9, 14);
            LocalDate target = LocalDate.of(2011, 3, 29); // 상장일보다 이름 — 3개 심볼 모두 제외 대상
            Map<String, Stock> symbolMap =
                    symbolMap(
                            stock(1L, "A", listedDate),
                            stock(2L, "B", listedDate),
                            stock(3L, "C", listedDate));
            when(parser.parse("BODY"))
                    .thenReturn(
                            new ParsedFileResult(
                                    List.of(row("A", 1, 1), row("B", 1, 1), row("C", 1, 1)), 0));

            FinraCdnDailyLoadOutcome outcome = loader.loadDate(target, List.of("BODY"), symbolMap);

            assertThat(outcome.gateExcluded()).isEqualTo(3);
            assertThat(
                            meterRegistry
                                    .get("aaa_collector_finra_ticker_reuse_skip_total")
                                    .counter()
                                    .count())
                    .isEqualTo(3.0);
            Meter meter = meterRegistry.get("aaa_collector_finra_ticker_reuse_skip_total").meter();
            assertThat(meter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        }
    }
}
