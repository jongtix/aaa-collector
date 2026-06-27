package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BackfillProperties 기본값 명세 (SPEC-COLLECTOR-BACKFILL-002 T1, SPEC-COLLECTOR-BACKFILL-004 T1).
 *
 * <p>{@code perTableCompletionCap}=10, {@code maxWindowsPerTarget}=120 기본값 검증. {@code
 * perRunCompletionCap} 리네임(REQ-BACKFILL-064a) 결과 확인(컴파일 레벨).
 */
@DisplayName("BackfillProperties 기본값 명세")
class BackfillPropertiesTest {

    private final BackfillProperties properties = new BackfillProperties();

    @Nested
    @DisplayName("BACKFILL-002 신규 필드 기본값")
    class NewFieldDefaults {

        @Test
        @DisplayName(
                "perTableCompletionCap 기본값 = 10 (REQ-BACKFILL-064, SPEC-COLLECTOR-BACKFILL-004)")
        void perTableCompletionCap_defaultIs10() {
            assertThat(properties.getPerTableCompletionCap()).isEqualTo(10);
        }

        @Test
        @DisplayName("maxWindowsPerTarget 기본값 = 120 (REQ-BACKFILL-053a, AC-5.2)")
        void maxWindowsPerTarget_defaultIs120() {
            assertThat(properties.getMaxWindowsPerTarget()).isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("BACKFILL-001 기존 필드 기본값 유지")
    class LegacyFieldDefaults {

        @Test
        @DisplayName("staleWindowThreshold 기본값 = 3")
        void staleWindowThreshold_defaultIs3() {
            assertThat(properties.getStaleWindowThreshold()).isEqualTo(3);
        }

        @Test
        @DisplayName("spanCalendarDays 기본값 = 200")
        void spanCalendarDays_defaultIs200() {
            assertThat(properties.getSpanCalendarDays()).isEqualTo(200);
        }

        @Test
        @DisplayName("anchorSkipMax 기본값 = 10")
        void anchorSkipMax_defaultIs10() {
            assertThat(properties.getAnchorSkipMax()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("getter/setter 동작")
    class GetterSetter {

        @Test
        @DisplayName("perTableCompletionCap setter 적용")
        void setPerTableCompletionCap_appliesValue() {
            properties.setPerTableCompletionCap(20);
            assertThat(properties.getPerTableCompletionCap()).isEqualTo(20);
        }

        @Test
        @DisplayName("maxWindowsPerTarget setter 적용")
        void setMaxWindowsPerTarget_appliesValue() {
            properties.setMaxWindowsPerTarget(200);
            assertThat(properties.getMaxWindowsPerTarget()).isEqualTo(200);
        }
    }
}
