package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.enums.Market;
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
@DisplayName("StockGradeOverseasSymbolProvider")
class StockGradeOverseasSymbolProviderTest {

    @Mock private StockGradeRepository stockGradeRepository;

    @InjectMocks private StockGradeOverseasSymbolProvider provider;

    // ──────────────────────────────────────────────────────────────────
    // AC-SYM-1: A·B 미국 종목 반환, KOSPI 미포함
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOverseasSubscriptionKeys — 해외 tr_key 반환")
    class GetOverseasSubscriptionKeys {

        @Test
        @DisplayName("AC-SYM-1: NASDAQ A등급 AAPL → DNASAAPL, KOSPI 종목 미포함")
        void returnsUsTrKeysAndExcludesKospi() {
            // Arrange — repository가 NASDAQ AAPL A, NYSE SPY B 반환 (KOSPI는 쿼리 레벨에서 이미 제외)
            when(stockGradeRepository.findUsSymbolsWithMarketByGradeIn(anyList(), anyList()))
                    .thenReturn(
                            List.of(
                                    new StockGradeRepository.SymbolWithMarket(
                                            "AAPL", Market.NASDAQ),
                                    new StockGradeRepository.SymbolWithMarket("SPY", Market.NYSE)));

            // Act
            List<String> keys = provider.getOverseasSubscriptionKeys();

            // Assert
            assertThat(keys).containsExactly("DNASAAPL", "DNYSSPY");
        }

        @Test
        @DisplayName("AC-SYM-2: 101종목 → 100개만 반환 (A 우선 grade ASC 정렬 유지)")
        void limitsTo100Symbols() {
            // Arrange — repository에서 101개 반환 (A 60개 + B 41개, grade ASC 순서)
            List<StockGradeRepository.SymbolWithMarket> rows = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                rows.add(new StockGradeRepository.SymbolWithMarket("A" + i, Market.NASDAQ));
            }
            for (int i = 0; i < 41; i++) {
                rows.add(new StockGradeRepository.SymbolWithMarket("B" + i, Market.NYSE));
            }
            when(stockGradeRepository.findUsSymbolsWithMarketByGradeIn(anyList(), anyList()))
                    .thenReturn(rows);

            // Act
            List<String> keys = provider.getOverseasSubscriptionKeys();

            // Assert — 100개로 제한
            assertThat(keys).hasSize(100);
            // A등급 60개 모두 포함, B등급은 40개만
            assertThat(keys.getFirst()).startsWith("DNAS");
        }

        @Test
        @DisplayName("AC-SYM-3: tr_key 형식 검증 — NASDAQ AAPL → DNASAAPL")
        void trKeyFormatNasdaqAppl() {
            // Arrange
            when(stockGradeRepository.findUsSymbolsWithMarketByGradeIn(anyList(), anyList()))
                    .thenReturn(
                            List.of(
                                    new StockGradeRepository.SymbolWithMarket(
                                            "AAPL", Market.NASDAQ)));

            // Act
            List<String> keys = provider.getOverseasSubscriptionKeys();

            // Assert — "D" + "NAS" + "AAPL"
            assertThat(keys).containsExactly("DNASAAPL");
        }

        @Test
        @DisplayName("NYSE SPY → DNYSSPY 형식 검증")
        void trKeyFormatNyseSpy() {
            // Arrange
            when(stockGradeRepository.findUsSymbolsWithMarketByGradeIn(anyList(), anyList()))
                    .thenReturn(
                            List.of(new StockGradeRepository.SymbolWithMarket("SPY", Market.NYSE)));

            // Act
            List<String> keys = provider.getOverseasSubscriptionKeys();

            // Assert — "D" + "NYS" + "SPY"
            assertThat(keys).containsExactly("DNYSSPY");
        }

        @Test
        @DisplayName("빈 결과 → 빈 리스트 반환")
        void returnsEmptyWhenNoSymbols() {
            when(stockGradeRepository.findUsSymbolsWithMarketByGradeIn(anyList(), anyList()))
                    .thenReturn(List.of());

            assertThat(provider.getOverseasSubscriptionKeys()).isEmpty();
        }
    }
}
