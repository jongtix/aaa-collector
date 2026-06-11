package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MacroIndicator builder 검증")
class MacroIndicatorTest {

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("KIS 소스의 MacroIndicator를 builder로 생성하면 필드가 일치한다")
        void macroIndicator_kisSourceBuilderFieldsMatch() {
            LocalDate tradeDate = LocalDate.of(2026, 6, 11);

            MacroIndicator indicator =
                    MacroIndicator.builder()
                            .indicatorCode("KR_BASE_RATE")
                            .source(MacroSource.KIS)
                            .tradeDate(tradeDate)
                            .value(new BigDecimal("3.50000000"))
                            .build();

            assertThat(indicator.getIndicatorCode()).isEqualTo("KR_BASE_RATE");
            assertThat(indicator.getSource()).isEqualTo(MacroSource.KIS);
            assertThat(indicator.getTradeDate()).isEqualTo(tradeDate);
            assertThat(indicator.getValue()).isEqualByComparingTo("3.50000000");
        }

        @Test
        @DisplayName("FRED 소스의 MacroIndicator를 builder로 생성하면 source가 FRED이다")
        void macroIndicator_fredSourceSet() {
            MacroIndicator indicator =
                    MacroIndicator.builder()
                            .indicatorCode("US_FED_RATE")
                            .source(MacroSource.FRED)
                            .tradeDate(LocalDate.of(2026, 5, 1))
                            .value(new BigDecimal("5.25000000"))
                            .build();

            assertThat(indicator.getSource()).isEqualTo(MacroSource.FRED);
            assertThat(indicator.getValue()).isEqualByComparingTo("5.25000000");
        }

        @Test
        @DisplayName("ECOS 소스의 MacroIndicator를 builder로 생성하면 source가 ECOS이다")
        void macroIndicator_ecosSourceSet() {
            MacroIndicator indicator =
                    MacroIndicator.builder()
                            .indicatorCode("KR_CPI")
                            .source(MacroSource.ECOS)
                            .tradeDate(LocalDate.of(2026, 4, 1))
                            .value(new BigDecimal("2.30000000"))
                            .build();

            assertThat(indicator.getSource()).isEqualTo(MacroSource.ECOS);
        }
    }
}
