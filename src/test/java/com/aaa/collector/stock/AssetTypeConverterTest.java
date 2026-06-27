package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.stock.enums.AssetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssetTypeConverterTest {

    private final AssetTypeConverter converter = new AssetTypeConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(AssetType.STOCK)).isEqualTo("STOCK");
            assertThat(converter.convertToDatabaseColumn(AssetType.ETF)).isEqualTo("ETF");
            assertThat(converter.convertToDatabaseColumn(AssetType.ETN)).isEqualTo("ETN");
            assertThat(converter.convertToDatabaseColumn(AssetType.COMMODITY))
                    .isEqualTo("COMMODITY");
            assertThat(converter.convertToDatabaseColumn(AssetType.INDEX)).isEqualTo("INDEX");
        }

        @Test
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }
    }

    @Nested
    @DisplayName("convertToEntityAttribute")
    class ConvertToEntityAttribute {

        @Test
        void 저장문자열을_상수로_변환한다() {
            assertThat(converter.convertToEntityAttribute("STOCK")).isEqualTo(AssetType.STOCK);
            assertThat(converter.convertToEntityAttribute("ETF")).isEqualTo(AssetType.ETF);
            assertThat(converter.convertToEntityAttribute("ETN")).isEqualTo(AssetType.ETN);
            assertThat(converter.convertToEntityAttribute("COMMODITY"))
                    .isEqualTo(AssetType.COMMODITY);
            assertThat(converter.convertToEntityAttribute("INDEX")).isEqualTo(AssetType.INDEX);
        }

        @Test
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void 미지값이면_IllegalArgumentException을_던진다() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
