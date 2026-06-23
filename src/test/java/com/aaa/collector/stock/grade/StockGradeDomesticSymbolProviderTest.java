package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

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

    @Nested
    @DisplayName("getDomesticSymbols — 국내 종목 선정")
    class GetDomesticSymbols {

        @Test
        @DisplayName("AC-20: A 60개 + B 50개 = 110개 → A 우선으로 100개 절삭")
        void shouldTruncateToMaxSymbolsWithAGradePriority() {
            // Arrange
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                symbols.add("A" + String.format("%03d", i));
            }
            for (int i = 0; i < 50; i++) {
                symbols.add("B" + String.format("%03d", i));
            }
            when(stockGradeRepository.findSymbolsByGradeIn(anyList())).thenReturn(symbols);

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
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                symbols.add("SYM" + String.format("%03d", i));
            }
            when(stockGradeRepository.findSymbolsByGradeIn(anyList())).thenReturn(symbols);

            // Act
            List<String> result = provider.getDomesticSymbols();

            // Assert
            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("빈 결과 → 빈 리스트 반환")
        void shouldReturnEmptyListWhenNoGrades() {
            // Arrange
            when(stockGradeRepository.findSymbolsByGradeIn(anyList())).thenReturn(List.of());

            // Act & Assert
            assertThat(provider.getDomesticSymbols()).isEmpty();
        }
    }
}
