package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
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

    private FinraCdnDailyLoaderImpl loader;

    @BeforeEach
    void setUp() {
        loader = new FinraCdnDailyLoaderImpl(parser, shortSaleOverseasRepository);
    }

    private static Stock stock(long id, String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
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
}
