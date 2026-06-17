package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.snapshot.RankingSnapshot;
import com.aaa.collector.stock.grade.snapshot.RankingSnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GradeClassificationService 스냅샷 읽기 기반 분류 테스트 (SPEC-COLLECTOR-GRADE-003 v2.0.0).
 *
 * <p>classifyDomestic() / classifyOverseas() 시장 분리, 스냅샷 읽기 percentile 재계산, 시장 단위 freshness/보류,
 * cold-start 안전성 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GradeClassificationService v2.0 — 스냅샷 읽기 기반 분류")
class GradeClassificationServiceV2Test {

    @Mock private StockRepository stockRepository;
    @Mock private AdtvPercentileCalculator percentileCalculator;
    @Mock private GradeClassifier gradeClassifier;
    @Mock private StockGradePersistService stockGradePersistService;
    @Mock private ListedYearsResolver listedYearsResolver;
    @Mock private RankingSnapshotService snapshotService;

    @InjectMocks private GradeClassificationService service;

    // 2024-06-17 08:20 KST = 2024-06-16 23:20 UTC → KST 날짜 = 2024-06-17
    private static final Instant CLASSIFY_KRX_INSTANT = Instant.parse("2024-06-16T23:20:00Z");
    // 2024-06-17 08:50 EDT = 2024-06-17 12:50 UTC → ET 날짜 = 2024-06-17
    private static final Instant CLASSIFY_US_INSTANT = Instant.parse("2024-06-17T12:50:00Z");

    private Stock buildStock(String symbol, Market market) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2010, 1, 1))
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    private RankingSnapshot buildSnapshot(
            String market, LocalDate date, String symbol, double rankValue) {
        return RankingSnapshot.of(market, date, symbol, rankValue, 1, CLASSIFY_KRX_INSTANT);
    }

    // -----------------------------------------------------------------------
    // 시나리오 4-1: classifyDomestic() — 스냅샷 읽기
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 4-1 — classifyDomestic(): 스냅샷 읽기 후 percentile 재계산")
    class ClassifyDomestic {

        @Test
        @DisplayName("당일 KRX 스냅샷 존재 → percentileCalculator 호출 후 A/B 영속화")
        void classifyDomestic_todaySnapshotExists_persistsGrade() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_KRX_INSTANT, java.time.ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock kospi = buildStock("005930", Market.KOSPI);
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));

            LocalDate today = LocalDate.of(2024, 6, 17); // KST 날짜
            RankingSnapshot snap = buildSnapshot("KRX", today, "005930", 100.0);
            when(snapshotService.findByMarketAndDate("KRX", today)).thenReturn(List.of(snap));
            when(percentileCalculator.calculate(any())).thenReturn(java.util.Map.of("005930", 5.0));
            when(listedYearsResolver.resolve(kospi)).thenReturn(8.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classifyDomestic();

            // Assert: 스냅샷 읽기 → A 영속화
            verify(stockGradePersistService)
                    .persistSingle(eq(kospi), eq(Grade.A), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("classifyDomestic()은 US 종목(NYSE)에 persistSingle 미호출")
        void classifyDomestic_usStocksNotClassified() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_KRX_INSTANT, java.time.ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock usStock = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(usStock));

            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("KRX", today))
                    .thenReturn(Collections.emptyList());

            // Act
            service.classifyDomestic();

            // Assert: US 종목 persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 5-1: classifyOverseas() — 스냅샷 읽기
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 5-1 — classifyOverseas(): 스냅샷 읽기 후 percentile 재계산")
    class ClassifyOverseas {

        @Test
        @DisplayName("당일 US 스냅샷 존재 → A/B 영속화")
        void classifyOverseas_todaySnapshotExists_persistsGrade() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_US_INSTANT, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock nyse = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));

            LocalDate today = LocalDate.of(2024, 6, 17); // ET 날짜
            RankingSnapshot snap = buildSnapshot("US", today, "AAPL", 100.0);
            when(snapshotService.findByMarketAndDate("US", today)).thenReturn(List.of(snap));
            when(percentileCalculator.calculate(any())).thenReturn(java.util.Map.of("AAPL", 5.0));
            when(listedYearsResolver.resolve(nyse)).thenReturn(8.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            // Act
            service.classifyOverseas();

            // Assert
            verify(stockGradePersistService)
                    .persistSingle(eq(nyse), eq(Grade.A), any(ZonedDateTime.class));
        }

        @Test
        @DisplayName("classifyOverseas()는 KRX 종목(KOSPI)에 persistSingle 미호출")
        void classifyOverseas_krxStocksNotClassified() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_US_INSTANT, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock kospi = buildStock("005930", Market.KOSPI);
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));

            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("US", today))
                    .thenReturn(Collections.emptyList());

            // Act
            service.classifyOverseas();

            // Assert: KRX 종목 persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // REQ-GRADE-006: 시장 단위 freshness/withhold
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("REQ-GRADE-006 — 시장 단위 freshness/withhold")
    class FreshnessWithhold {

        @Test
        @DisplayName("KRX 당주기 스냅샷 부재 → KRX 종목 persistSingle 미호출(보류), C 강등 없음")
        void classifyDomestic_noTodaySnapshot_withholds() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_KRX_INSTANT, java.time.ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock kospi = buildStock("005930", Market.KOSPI);
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));
            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("KRX", today))
                    .thenReturn(Collections.emptyList());

            // Act
            service.classifyDomestic();

            // Assert: 보류 — persistSingle 미호출
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("US 스냅샷 부재 → US 보류 (시장 독립)")
        void classifyOverseas_noSnapshot_usWithheld() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_US_INSTANT, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock nyse = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));
            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("US", today))
                    .thenReturn(Collections.emptyList());

            // Act
            service.classifyOverseas();

            // Assert: US 보류
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("정상 스냅샷 + 개별 종목 순위권 밖 → 100.0 fallback(C), 보류 아님")
        void classifyDomestic_stockAbsentInSnapshot_fallbackC() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_KRX_INSTANT, java.time.ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock kospi = buildStock("005930", Market.KOSPI);
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));
            LocalDate today = LocalDate.of(2024, 6, 17);
            // 스냅샷에 다른 종목만 있음 — 005930은 없음
            RankingSnapshot snap = buildSnapshot("KRX", today, "000660", 100.0);
            when(snapshotService.findByMarketAndDate("KRX", today)).thenReturn(List.of(snap));
            when(percentileCalculator.calculate(any())).thenReturn(java.util.Map.of("000660", 5.0));
            when(listedYearsResolver.resolve(kospi)).thenReturn(8.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.C);

            // Act
            service.classifyDomestic();

            // Assert: 보류 아님 — C로 정상 영속화
            verify(stockGradePersistService).persistSingle(eq(kospi), eq(Grade.C), any());
        }

        @Test
        @DisplayName("cold-start(0행) + KRX 스냅샷 부재 → crash 없이 보류 종료")
        void classifyDomestic_coldStartNoSnapshot_noCrash() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_KRX_INSTANT, java.time.ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock kospi = buildStock("005930", Market.KOSPI);
            when(stockRepository.findAllActive()).thenReturn(List.of(kospi));
            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("KRX", today))
                    .thenReturn(Collections.emptyList());

            // Act & Assert: crash 없이 종료
            assertThatCode(service::classifyDomestic).doesNotThrowAnyException();
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }

        @Test
        @DisplayName("cold-start(0행) + US 스냅샷 부재 → crash 없이 보류 종료")
        void classifyOverseas_coldStartNoSnapshot_noCrash() {
            // Arrange
            Clock clock = Clock.fixed(CLASSIFY_US_INSTANT, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock nyse = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));
            LocalDate today = LocalDate.of(2024, 6, 17);
            when(snapshotService.findByMarketAndDate("US", today))
                    .thenReturn(Collections.emptyList());

            // Act & Assert
            assertThatCode(service::classifyOverseas).doesNotThrowAnyException();
            verify(stockGradePersistService, never()).persistSingle(any(), any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // 신선도 술어 시간대 매칭 (C1): EDT·EST 양 시즌
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("신선도 술어 (C1) — EDT·EST 양 시즌 매칭")
    class FreshnessPredicateDst {

        @Test
        @DisplayName("US EDT: ET 날짜=6-17 스냅샷 + 08:50 ET classify(ET=6-17) → 매칭")
        void classifyOverseas_edtSeason_snapshotDateMatches() {
            // 2024-06-17 08:50 EDT = 2024-06-17 12:50 UTC
            Clock clock = Clock.fixed(CLASSIFY_US_INSTANT, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock nyse = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));
            LocalDate etDate = LocalDate.of(2024, 6, 17); // EDT 시즌
            RankingSnapshot snap = buildSnapshot("US", etDate, "AAPL", 100.0);
            when(snapshotService.findByMarketAndDate("US", etDate)).thenReturn(List.of(snap));
            when(percentileCalculator.calculate(any())).thenReturn(java.util.Map.of("AAPL", 5.0));
            when(listedYearsResolver.resolve(nyse)).thenReturn(8.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            verify(stockGradePersistService).persistSingle(eq(nyse), eq(Grade.A), any());
        }

        @Test
        @DisplayName("US EST: ET 날짜=12-17 스냅샷 + 08:50 ET classify(ET=12-17) → 매칭")
        void classifyOverseas_estSeason_snapshotDateMatches() {
            // 2024-12-17 08:50 EST = 2024-12-17 13:50 UTC
            Instant estClassifyInstant = Instant.parse("2024-12-17T13:50:00Z");
            Clock clock = Clock.fixed(estClassifyInstant, java.time.ZoneId.of("America/New_York"));
            ReflectionTestUtils.setField(service, "clock", clock);

            Stock nyse = buildStock("AAPL", Market.NYSE);
            when(stockRepository.findAllActive()).thenReturn(List.of(nyse));
            LocalDate etDate = LocalDate.of(2024, 12, 17); // EST 시즌
            RankingSnapshot snap = buildSnapshot("US", etDate, "AAPL", 100.0);
            when(snapshotService.findByMarketAndDate("US", etDate)).thenReturn(List.of(snap));
            when(percentileCalculator.calculate(any())).thenReturn(java.util.Map.of("AAPL", 5.0));
            when(listedYearsResolver.resolve(nyse)).thenReturn(8.0);
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            service.classifyOverseas();

            verify(stockGradePersistService).persistSingle(eq(nyse), eq(Grade.A), any());
        }
    }
}
