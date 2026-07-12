package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StockGrade 단위 테스트")
class StockGradeTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Stock buildStock() {
        return Stock.builder()
                .symbol("005930")
                .nameKo("삼성전자")
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2000, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("updateGrade — 등급 및 gradedAt 갱신")
    class UpdateGrade {

        @Test
        @DisplayName("grade와 gradedAt이 새 값으로 갱신된다")
        void updateGrade_setsNewGradeAndGradedAt() {
            // Arrange
            Stock stock = buildStock();
            ZonedDateTime originalGradedAt = ZonedDateTime.of(2025, 1, 1, 9, 0, 0, 0, KST);
            StockGrade stockGrade =
                    StockGrade.builder().stock(stock).grade("C").gradedAt(originalGradedAt).build();

            ZonedDateTime newGradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);

            // Act
            stockGrade.updateGrade("A", newGradedAt);

            // Assert
            assertThat(stockGrade.getGrade()).isEqualTo("A");
            assertThat(stockGrade.getGradedAt()).isEqualTo(newGradedAt);
        }

        @Test
        @DisplayName("updateGrade 호출 시 stock 필드는 변경되지 않는다")
        void updateGrade_stockFieldPreserved() {
            // Arrange
            Stock stock = buildStock();
            ZonedDateTime gradedAt = ZonedDateTime.now(KST);
            StockGrade stockGrade =
                    StockGrade.builder().stock(stock).grade("B").gradedAt(gradedAt).build();

            // Act
            stockGrade.updateGrade("A", ZonedDateTime.now(KST));

            // Assert
            assertThat(stockGrade.getStock()).isSameAs(stock);
        }

        @Test
        @DisplayName("시나리오 10: grade/gradedAt 외 다른 필드는 변경되지 않는다")
        void updateGrade_onlyGradeAndGradedAtChanged_otherFieldsPreserved() {
            // Arrange
            Stock stock = buildStock();
            ZonedDateTime originalGradedAt = ZonedDateTime.of(2025, 1, 1, 9, 0, 0, 0, KST);
            StockGrade stockGrade =
                    StockGrade.builder().stock(stock).grade("C").gradedAt(originalGradedAt).build();
            Stock originalStock = stockGrade.getStock();

            // Act
            ZonedDateTime newGradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);
            stockGrade.updateGrade("A", newGradedAt);

            // Assert — grade와 gradedAt만 변경
            assertThat(stockGrade.getGrade()).isEqualTo("A");
            assertThat(stockGrade.getGradedAt()).isEqualTo(newGradedAt);
            // 다른 필드 보존 확인
            assertThat(stockGrade.getStock()).isSameAs(originalStock);
        }

        @Test
        @DisplayName(
                "등급 불변이어도 gradedAt은 무조건 새 실행 시각으로 갱신된다"
                        + " (SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3 — watchlist-sync-krx/us warm-start seed 계약)")
        void updateGrade_gradedAtAdvancesEvenWhenGradeUnchanged() {
            // Arrange: 이 계약이 깨지면(예: 등급 불변 시 UPDATE 스킵 최적화)
            // StockGradeRepository.findMaxGradedAtByMarketsIn을
            // watchlist-sync-krx/us의 last_load seed로 쓰는 BatchMetricsWarmStarter 배선이 조용히 무효화된다.
            Stock stock = buildStock();
            ZonedDateTime originalGradedAt = ZonedDateTime.of(2025, 1, 1, 9, 0, 0, 0, KST);
            StockGrade stockGrade =
                    StockGrade.builder().stock(stock).grade("A").gradedAt(originalGradedAt).build();

            ZonedDateTime newRunGradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);

            // Act: 등급값 자체는 "A" → "A"로 불변
            stockGrade.updateGrade("A", newRunGradedAt);

            // Assert: 그래도 gradedAt은 새 실행 시각으로 전진해야 한다
            assertThat(stockGrade.getGrade()).isEqualTo("A");
            assertThat(stockGrade.getGradedAt()).isEqualTo(newRunGradedAt);
            assertThat(stockGrade.getGradedAt()).isNotEqualTo(originalGradedAt);
        }
    }
}
