package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GradeConstants 단위 테스트 — 시장별 임계값 헬퍼")
class GradeConstantsTest {

    @Nested
    @DisplayName("getHighThreshold — A 등급 ADTV 임계값")
    class GetHighThreshold {

        @Test
        @DisplayName("KRX → 500억 KRW")
        void getHighThreshold_krx_returns5e10() {
            assertThat(GradeConstants.getHighThreshold("KRX")).isEqualTo(5e10);
        }

        @Test
        @DisplayName("US → $2B USD")
        void getHighThreshold_us_returns2e9() {
            assertThat(GradeConstants.getHighThreshold("US")).isEqualTo(2e9);
        }

        @Test
        @DisplayName("미지정 시장 → IllegalArgumentException 발생")
        void getHighThreshold_unknown_throwsIllegalArgument() {
            assertThatThrownBy(() -> GradeConstants.getHighThreshold("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UNKNOWN");
        }

        @Test
        @DisplayName("getLowThreshold: 미지정 시장 → IllegalArgumentException 발생")
        void getLowThreshold_unknown_throwsIllegalArgument() {
            assertThatThrownBy(() -> GradeConstants.getLowThreshold("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UNKNOWN");
        }
    }

    @Nested
    @DisplayName("getLowThreshold — B 등급 ADTV 하한 임계값")
    class GetLowThreshold {

        @Test
        @DisplayName("KRX → 100억 KRW")
        void getLowThreshold_krx_returns1e10() {
            assertThat(GradeConstants.getLowThreshold("KRX")).isEqualTo(1e10);
        }

        @Test
        @DisplayName("US → $500M USD")
        void getLowThreshold_us_returns5e8() {
            assertThat(GradeConstants.getLowThreshold("US")).isEqualTo(5e8);
        }
    }

    @Nested
    @DisplayName("상수값 검증")
    class ConstantValues {

        @Test
        @DisplayName("HOLDING_DAYS_A = 750")
        void holdingDaysA_equals750() {
            assertThat(GradeConstants.HOLDING_DAYS_A).isEqualTo(750L);
        }

        @Test
        @DisplayName("HOLDING_DAYS_B = 250")
        void holdingDaysB_equals250() {
            assertThat(GradeConstants.HOLDING_DAYS_B).isEqualTo(250L);
        }
    }
}
