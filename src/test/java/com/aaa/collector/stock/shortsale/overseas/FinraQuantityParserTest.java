package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FinraQuantityParser — 비음수 무손실 long 변환")
class FinraQuantityParserTest {

    @Test
    @DisplayName("정상 정수 BigDecimal은 long으로 변환한다")
    void convertsValidInteger() {
        List<String> reasons = new ArrayList<>();
        Long result =
                FinraQuantityParser.toNonNegativeLong(
                        BigDecimal.valueOf(134_422_787L), "qty", reasons);

        assertThat(result).isEqualTo(134_422_787L);
        assertThat(reasons).isEmpty();
    }

    @Test
    @DisplayName("null 값은 null 반환하고 reasons에 사유를 누적한다")
    void returnsNullOnNull() {
        List<String> reasons = new ArrayList<>();
        Long result = FinraQuantityParser.toNonNegativeLong(null, "qty", reasons);

        assertThat(result).isNull();
        assertThat(reasons).containsExactly("qty=null");
    }

    @Test
    @DisplayName("음수 값은 null 반환하고 reasons에 사유를 누적한다")
    void returnsNullOnNegative() {
        List<String> reasons = new ArrayList<>();
        Long result = FinraQuantityParser.toNonNegativeLong(BigDecimal.valueOf(-1), "qty", reasons);

        assertThat(result).isNull();
        assertThat(reasons).containsExactly("qty<0(-1)");
    }

    @Test
    @DisplayName("소수부가 있는 값은 null 반환하고 reasons에 사유를 누적한다")
    void returnsNullOnFractional() {
        List<String> reasons = new ArrayList<>();
        Long result = FinraQuantityParser.toNonNegativeLong(new BigDecimal("10.5"), "qty", reasons);

        assertThat(result).isNull();
        assertThat(reasons).containsExactly("qty 소수부 존재(10.5)");
    }
}
