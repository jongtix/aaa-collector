package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FinraCdnShortSaleBackfillProperties 기본값 명세 (SPEC-COLLECTOR-BACKFILL-008 T1, AC-BF-24).
 *
 * <p>{@code cron}=05:00 KST, {@code floorDate}=2009-08-03, {@code perCronDateCap}=1000, {@code
 * facilityCodes}=5원소 합집합, {@code cdnBaseUrl}=CDN 무인증 베이스 URL 기본값 검증. KIS 유산 {@code
 * max-windows-per-target}은 이 클래스에 존재하지 않는다(REQ-BACKFILL-122a — 전용 프로퍼티 분리).
 */
@DisplayName("FinraCdnShortSaleBackfillProperties 기본값 명세")
class FinraCdnShortSaleBackfillPropertiesTest {

    private final FinraCdnShortSaleBackfillProperties properties =
            new FinraCdnShortSaleBackfillProperties();

    @Nested
    @DisplayName("기본값")
    class Defaults {

        @Test
        @DisplayName("cron 기본값 = 0 0 5 * * * (05:00 KST)")
        void cron_defaultIs5amCron() {
            assertThat(properties.getCron()).isEqualTo("0 0 5 * * *");
        }

        @Test
        @DisplayName("zone 기본값 = Asia/Seoul")
        void zone_defaultIsAsiaSeoul() {
            assertThat(properties.getZone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("floorDate 기본값 = 2009-08-03 (FINRA CDN 데이터 하한)")
        void floorDate_defaultIs20090803() {
            assertThat(properties.getFloorDate()).isEqualTo(LocalDate.of(2009, 8, 3));
        }

        @Test
        @DisplayName("perCronDateCap 기본값 = 1000 (calendar days/cron)")
        void perCronDateCap_defaultIs1000() {
            assertThat(properties.getPerCronDateCap()).isEqualTo(1000);
        }

        @Test
        @DisplayName("facilityCodes 기본값 = [FNSQ, FNYX, FNQC, FORF, FNRA] (전 시대 합집합)")
        void facilityCodes_defaultIsUnionOfFiveFacilities() {
            assertThat(properties.getFacilityCodes())
                    .containsExactly("FNSQ", "FNYX", "FNQC", "FORF", "FNRA");
        }

        @Test
        @DisplayName("cdnBaseUrl 기본값 = https://cdn.finra.org")
        void cdnBaseUrl_defaultIsFinraCdn() {
            assertThat(properties.getCdnBaseUrl()).isEqualTo("https://cdn.finra.org");
        }
    }

    @Nested
    @DisplayName("프로퍼티 오버라이드")
    class Overrides {

        @Test
        @DisplayName("cron setter 적용")
        void setCron_appliesValue() {
            properties.setCron("0 0 6 * * *");
            assertThat(properties.getCron()).isEqualTo("0 0 6 * * *");
        }

        @Test
        @DisplayName("perCronDateCap setter 적용")
        void setPerCronDateCap_appliesValue() {
            properties.setPerCronDateCap(500);
            assertThat(properties.getPerCronDateCap()).isEqualTo(500);
        }

        @Test
        @DisplayName("facilityCodes setter 적용")
        void setFacilityCodes_appliesValue() {
            properties.setFacilityCodes(List.of("FNSQ"));
            assertThat(properties.getFacilityCodes()).containsExactly("FNSQ");
        }

        @Test
        @DisplayName("floorDate setter 적용")
        void setFloorDate_appliesValue() {
            properties.setFloorDate(LocalDate.of(2010, 1, 1));
            assertThat(properties.getFloorDate()).isEqualTo(LocalDate.of(2010, 1, 1));
        }

        @Test
        @DisplayName("cdnBaseUrl setter 적용")
        void setCdnBaseUrl_appliesValue() {
            properties.setCdnBaseUrl("https://example.org");
            assertThat(properties.getCdnBaseUrl()).isEqualTo("https://example.org");
        }

        @Test
        @DisplayName("zone setter 적용")
        void setZone_appliesValue() {
            properties.setZone("UTC");
            assertThat(properties.getZone()).isEqualTo("UTC");
        }
    }
}
