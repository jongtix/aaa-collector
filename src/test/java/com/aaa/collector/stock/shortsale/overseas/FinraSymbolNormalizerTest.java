package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FinraSymbolNormalizer — FINRA 슬래시 클래스주식 표기 정규화")
class FinraSymbolNormalizerTest {

    @Test
    @DisplayName("슬래시 클래스주식 표기를 점 표기로 변환한다 (BRK/B → BRK.B, AC-NORM-1)")
    void slashToDot() {
        assertThat(FinraSymbolNormalizer.normalize("BRK/B")).isEqualTo("BRK.B");
        assertThat(FinraSymbolNormalizer.normalize("BF/B")).isEqualTo("BF.B");
    }

    @Test
    @DisplayName("구분자 없는 평문 티커는 그대로 둔다")
    void plainTickerUnchanged() {
        assertThat(FinraSymbolNormalizer.normalize("AAPL")).isEqualTo("AAPL");
        assertThat(FinraSymbolNormalizer.normalize("MSFT")).isEqualTo("MSFT");
    }

    @Test
    @DisplayName("null/blank는 그대로 반환한다(방어적)")
    void nullOrBlank() {
        assertThat(FinraSymbolNormalizer.normalize(null)).isNull();
        assertThat(FinraSymbolNormalizer.normalize("")).isEmpty();
        assertThat(FinraSymbolNormalizer.normalize("   ")).isEqualTo("   ");
    }
}
