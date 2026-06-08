package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
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
@DisplayName("SubscriptionTargetResolver")
class SubscriptionTargetResolverTest {

    @Mock private DomesticSymbolProvider domesticSymbolProvider;

    @InjectMocks private SubscriptionTargetResolver resolver;

    // ──────────────────────────────────────────────────────────────────
    // 국내 종목 선정 (resolveDomesticSymbols)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDomesticSymbols — 국내 종목 선정")
    class ResolveDomesticSymbols {

        @Test
        @DisplayName("AC-20: domesticSymbolProvider가 100개를 반환하면 그대로 전달된다")
        void shouldDelegate100SymbolsToProvider() {
            // Arrange
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                symbols.add("SYM" + String.format("%03d", i));
            }
            when(domesticSymbolProvider.getDomesticSymbols()).thenReturn(symbols);

            // Act
            List<String> result = resolver.resolveDomesticSymbols();

            // Assert
            assertThat(result).hasSize(100);
            assertThat(result).containsExactlyElementsOf(symbols);
        }

        @Test
        @DisplayName("A 60개 + B 40개 = 100개를 provider가 반환하면 모두 포함된다")
        void shouldReturnAllSymbolsWhenProviderReturnsHundred() {
            // Arrange
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                symbols.add("A" + String.format("%03d", i));
            }
            for (int i = 0; i < 40; i++) {
                symbols.add("B" + String.format("%03d", i));
            }
            when(domesticSymbolProvider.getDomesticSymbols()).thenReturn(symbols);

            // Act
            List<String> result = resolver.resolveDomesticSymbols();

            // Assert — 100개 전부 반환
            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("빈 결과 → 빈 리스트 반환")
        void shouldReturnEmptyListWhenNoGrades() {
            // Arrange
            when(domesticSymbolProvider.getDomesticSymbols()).thenReturn(List.of());

            // Act & Assert
            assertThat(resolver.resolveDomesticSymbols()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 해외 종목 선정 (resolveOverseasSymbols)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveOverseasSymbols — 해외 종목 선정 (Phase 1 스텁)")
    class ResolveOverseasSymbols {

        @Test
        @DisplayName("Phase 1: resolveOverseasSymbols()는 항상 빈 리스트를 반환한다")
        void shouldAlwaysReturnEmptyListForOverseas() {
            assertThat(resolver.resolveOverseasSymbols()).isEmpty();
        }
    }
}
