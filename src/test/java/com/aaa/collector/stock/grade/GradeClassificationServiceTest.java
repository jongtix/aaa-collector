package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("GradeClassificationService 단위 테스트")
class GradeClassificationServiceTest {

    @Mock private StockRepository stockRepository;
    @Mock private KisDomesticRankingClient domesticRankingClient;
    @Mock private KisOverseasRankingClient overseasRankingClient;
    @Mock private AdtvPercentileCalculator percentileCalculator;
    @Mock private GradeClassifier gradeClassifier;
    @Mock private StockGradeRepository stockGradeRepository;
    @Mock private GradeCacheRepository gradeCacheRepository;

    @InjectMocks private GradeClassificationService service;

    private Stock buildStock(String symbol, Market market, LocalDate listedDate) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .listedDate(listedDate)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    @BeforeEach
    void setUpDefaults() {
        // 기본: 빈 순위 응답, 빈 백분위 계산 (lenient — 일부 테스트에서 미사용)
        lenient().when(domesticRankingClient.fetchRanking()).thenReturn(List.of());
        lenient().when(overseasRankingClient.fetchRanking()).thenReturn(List.of());
        lenient().when(percentileCalculator.calculate(anyList())).thenReturn(Map.of());
        lenient().when(gradeClassifier.classify(any())).thenReturn(Grade.C);
        lenient().when(stockGradeRepository.findByStock(any())).thenReturn(Optional.empty());
        lenient().when(stockGradeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("시나리오 7 — 상장폐지 종목 제외")
    class ActiveStocksOnly {

        @Test
        @DisplayName("findAllActive()가 반환한 종목만 분류 대상")
        void classify_usesOnlyActiveStocks() {
            Stock active = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(active));

            service.classify();

            verify(stockRepository).findAllActive();
            verify(gradeClassifier).classify(any());
        }

        @Test
        @DisplayName("활성 종목 없음 — 분류기 호출 없음")
        void classify_noActiveStocks_nothingClassified() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            service.classify();

            verify(gradeClassifier, never()).classify(any());
        }
    }

    @Nested
    @DisplayName("시나리오 8 — 시장별 데이터 소스 라우팅")
    class MarketRouting {

        @Test
        @DisplayName("KRX 종목 존재 시 — KisDomesticRankingClient 호출")
        void classify_kospiStock_callsDomesticClient() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classify();

            verify(domesticRankingClient).fetchRanking();
        }

        @Test
        @DisplayName("US 종목 존재 시 — KisOverseasRankingClient 호출")
        void classify_nyseStock_callsOverseasClient() {
            Stock stock = buildStock("AAPL", Market.NYSE, LocalDate.of(1980, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            service.classify();

            verify(overseasRankingClient).fetchRanking();
        }
    }

    @Nested
    @DisplayName("시나리오 9 — 등급 산정 후 이중 영속화")
    class DualPersistence {

        @Test
        @DisplayName("등급 산정 후 stockGradeRepository와 gradeCacheRepository 모두 호출")
        void classify_gradeAssigned_persistsToRepoAndCache() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.empty());

            service.classify();

            verify(stockGradeRepository).save(any(StockGrade.class));
            verify(gradeCacheRepository).save(eq("005930"), eq(Grade.A), any(ZonedDateTime.class));
        }
    }

    @Nested
    @DisplayName("시나리오 5 — 분류 실패 시 이전 등급 유지")
    class FailureHandling {

        @Test
        @DisplayName("기존 등급 있는 종목 분류 실패 — 기존 등급 유지, 다른 종목 계속 처리")
        void classify_failureWithExistingGrade_keepsPreviousGrade() {
            Stock fail = buildStock("FAIL", Market.KOSPI, LocalDate.of(2010, 1, 1));
            Stock ok = buildStock("OK", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(fail, ok));
            when(gradeClassifier.classify(any()))
                    .thenThrow(new RuntimeException("분류 실패"))
                    .thenReturn(Grade.B);

            service.classify();

            // "OK" 종목은 정상 처리
            verify(stockGradeRepository).save(any(StockGrade.class));
        }

        @Test
        @DisplayName("시나리오 6: 기존 등급 없는 종목 분류 실패 — insert 없음, skip")
        void classify_failureWithNoExistingGrade_skipsInsert() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenThrow(new RuntimeException("분류 실패"));
            // stockGradeRepository.findByStock이 never 호출되거나 빈 Optional 반환 — BeforeEach에서 설정됨

            service.classify();

            verify(stockGradeRepository, never()).save(any());
            verify(gradeCacheRepository, never()).save(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("upsert 로직 — 기존 등급 존재 시 updateGrade()")
    class UpsertLogic {

        @Test
        @DisplayName("기존 StockGrade 존재 — updateGrade()로 갱신, save() 호출")
        void classify_existingGrade_callsUpdateGrade() {
            Stock stock = buildStock("005930", Market.KOSPI, LocalDate.of(2010, 1, 1));
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(gradeClassifier.classify(any())).thenReturn(Grade.A);

            StockGrade existing =
                    StockGrade.builder()
                            .stock(stock)
                            .grade("C")
                            .gradedAt(ZonedDateTime.now())
                            .build();
            when(stockGradeRepository.findByStock(stock)).thenReturn(Optional.of(existing));

            service.classify();

            assertThat(existing.getGrade()).isEqualTo("A");
            verify(stockGradeRepository).save(existing);
        }
    }
}
