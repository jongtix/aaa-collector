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
    }
}
