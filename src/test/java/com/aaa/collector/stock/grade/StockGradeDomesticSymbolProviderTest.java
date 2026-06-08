package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockGradeDomesticSymbolProvider")
class StockGradeDomesticSymbolProviderTest {

    @Mock private StockGradeRepository stockGradeRepository;

    @InjectMocks private StockGradeDomesticSymbolProvider provider;

    /** 테스트용 Stock 생성 헬퍼. */
    private Stock buildStock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트종목-" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2000, 1, 1))
                .build();
    }

    /** 테스트용 StockGrade 생성 헬퍼. */
    private StockGrade buildStockGrade(String symbol, String grade) {
        return StockGrade.builder()
                .stock(buildStock(symbol))
                .grade(grade)
                .gradedAt(ZonedDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("getDomesticSymbols — 국내 종목 선정")
    class GetDomesticSymbols {

        @Test
        @DisplayName("AC-20: A 60개 + B 50개 = 110개 → A 우선으로 100개 절삭")
        void shouldTruncateToMaxSymbolsWithAGradePriority() {
            // Arrange
            List<StockGrade> grades = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                grades.add(buildStockGrade("A" + String.format("%03d", i), "A"));
            }
            for (int i = 0; i < 50; i++) {
                grades.add(buildStockGrade("B" + String.format("%03d", i), "B"));
            }
            when(stockGradeRepository.findByGradeInOrderByGradeAsc(anyList())).thenReturn(grades);

            // Act
            List<String> result = provider.getDomesticSymbols();

            // Assert — 정확히 100개, 첫 60개가 A등급
            assertThat(result).hasSize(100);
            assertThat(result.subList(0, 60)).allMatch(s -> s.startsWith("A"));
        }

        @Test
        @DisplayName("정확히 100개 등급 → 절삭 없이 전체 반환")
        void shouldReturnAllWhenExactlyMaxSymbols() {
            // Arrange
            List<StockGrade> grades = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                grades.add(buildStockGrade("SYM" + String.format("%03d", i), "A"));
            }
            when(stockGradeRepository.findByGradeInOrderByGradeAsc(anyList())).thenReturn(grades);

            // Act
            List<String> result = provider.getDomesticSymbols();

            // Assert
            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("빈 결과 → 빈 리스트 반환")
        void shouldReturnEmptyListWhenNoGrades() {
            // Arrange
            when(stockGradeRepository.findByGradeInOrderByGradeAsc(anyList()))
                    .thenReturn(List.of());

            // Act & Assert
            assertThat(provider.getDomesticSymbols()).isEmpty();
        }
    }
}
