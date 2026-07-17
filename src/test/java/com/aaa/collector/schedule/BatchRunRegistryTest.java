package com.aaa.collector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link BatchRunRegistry} 단위 테스트 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-002 / T-002).
 *
 * <p>레지스트리가 §3.1의 21개 편입 배치를 {@code (label, cron, zone, marginSeconds)} 단일 소스로 선언하는지, label이 유일한지,
 * extended-hours pre/after가 스케줄러와 동일한 property placeholder를 읽는지 검증한다.
 */
class BatchRunRegistryTest {

    private static final Set<String> EXPECTED_LABELS =
            Set.of(
                    "overseas-daily",
                    "domestic-supply-investor",
                    "domestic-supply-credit-balance",
                    "domestic-supply-short-sale",
                    "overseas-shortsale-daily",
                    "overseas-shortsale-interest",
                    "domestic-invest-opinion",
                    "domestic-financial-ratio",
                    "macro-external",
                    "domestic-news",
                    "domestic-etf-representative",
                    "watchlist-sync-krx",
                    "watchlist-sync-us",
                    "overseas-news",
                    "overseas-rights",
                    "corp-code",
                    "dart-backfill",
                    "extended-hours-pre",
                    "extended-hours-after",
                    "overseas-split",
                    "market-usdkrw");

    private BatchRunEntry entry(BatchRunRegistry registry, String label) {
        return registry.entries().stream()
                .filter(e -> e.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("엔트리 없음: " + label));
    }

    @Nested
    @DisplayName("레지스트리 구성")
    class Composition {

        @Test
        @DisplayName("21개 엔트리 전수를 선언한다")
        void declaresTwentyOneEntries() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            assertThat(registry.entries()).hasSize(21);
        }

        @Test
        @DisplayName("label 집합이 §3.1 편입 배치 20종과 정확히 일치한다")
        void labelsMatchEnrolledBatches() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            Set<String> actual =
                    registry.entries().stream()
                            .map(BatchRunEntry::label)
                            .collect(Collectors.toSet());

            assertThat(actual).isEqualTo(EXPECTED_LABELS);
        }

        @Test
        @DisplayName("label은 유일하다(중복 없음)")
        void labelsAreUnique() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            List<String> labels = registry.entries().stream().map(BatchRunEntry::label).toList();

            assertThat(labels).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("게이트-레거시/편입 제외 배치는 레지스트리에 없다")
        void excludesGateLegacyBatches() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            List<String> labels = registry.entries().stream().map(BatchRunEntry::label).toList();

            assertThat(labels)
                    .doesNotContain("domestic-daily", "market-indicators", "dart-disclosure");
        }
    }

    @Nested
    @DisplayName("BatchCrons 단일 소스 참조")
    class SharedConstants {

        @Test
        @DisplayName("overseas-daily는 BatchCrons 상수의 cron/zone을 공유한다")
        void overseasDailySharesConstants() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            BatchRunEntry e = entry(registry, "overseas-daily");
            assertThat(e.cron()).isEqualTo(BatchCrons.OVERSEAS_DAILY_CRON);
            assertThat(e.zone()).isEqualTo(BatchCrons.OVERSEAS_DAILY_ZONE);
        }

        @Test
        @DisplayName("국내 일봉 체인 3라벨은 DOMESTIC_DAILY_CHAIN cron/zone을 공유한다")
        void domesticSupplyChainSharesChainConstant() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            for (String label :
                    List.of(
                            "domestic-supply-investor",
                            "domestic-supply-credit-balance",
                            "domestic-supply-short-sale")) {
                BatchRunEntry e = entry(registry, label);
                assertThat(e.cron()).as(label).isEqualTo(BatchCrons.DOMESTIC_DAILY_CHAIN_CRON);
                assertThat(e.zone()).as(label).isEqualTo(BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE);
            }
        }

        @Test
        @DisplayName("overseas-shortsale daily/interest는 OVERSEAS_SHORTSALE cron을 공유한다")
        void overseasShortsaleSharesConstant() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            for (String label :
                    List.of("overseas-shortsale-daily", "overseas-shortsale-interest")) {
                BatchRunEntry e = entry(registry, label);
                assertThat(e.cron()).as(label).isEqualTo(BatchCrons.OVERSEAS_SHORTSALE_CRON);
                assertThat(e.zone()).as(label).isEqualTo(BatchCrons.OVERSEAS_SHORTSALE_ZONE);
            }
        }
    }

    @Nested
    @DisplayName("마진 (§9 시작 표)")
    class Margins {

        @Test
        @DisplayName("국내 배치 시작 마진(초)이 §9 표와 일치한다")
        void domesticMarginsMatchStartTable() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            assertThat(entry(registry, "domestic-supply-investor").marginSeconds())
                    .isEqualTo(14_400L);
            assertThat(entry(registry, "domestic-invest-opinion").marginSeconds())
                    .isEqualTo(10_800L);
            assertThat(entry(registry, "domestic-financial-ratio").marginSeconds())
                    .isEqualTo(28_800L);
            assertThat(entry(registry, "domestic-news").marginSeconds()).isEqualTo(7_200L);
        }

        @Test
        @DisplayName("해외/시간외 배치 시작 마진(초)이 §9 표와 일치한다")
        void overseasMarginsMatchStartTable() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            assertThat(entry(registry, "overseas-daily").marginSeconds()).isEqualTo(18_000L);
            assertThat(entry(registry, "overseas-shortsale-daily").marginSeconds())
                    .isEqualTo(21_600L);
            assertThat(entry(registry, "extended-hours-pre").marginSeconds()).isEqualTo(21_600L);
            assertThat(entry(registry, "extended-hours-after").marginSeconds()).isEqualTo(14_400L);
        }
    }

    @Nested
    @DisplayName("extended-hours property placeholder (REQ-XR-002)")
    class ExtendedHoursProperty {

        @Test
        @DisplayName("override 없으면 스케줄러 default 리터럴을 쓴다")
        void usesDefaultWhenNoOverride() {
            BatchRunRegistry registry = new BatchRunRegistry(new MockEnvironment());

            assertThat(entry(registry, "extended-hours-pre").cron())
                    .isEqualTo("0 0 10 * * MON-FRI");
            assertThat(entry(registry, "extended-hours-after").cron())
                    .isEqualTo("0 30 20 * * MON-FRI");
            assertThat(entry(registry, "extended-hours-pre").zone()).isEqualTo("America/New_York");
            assertThat(entry(registry, "extended-hours-after").zone())
                    .isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("Environment override 시 override cron 값을 읽는다")
        void readsEnvironmentOverride() {
            MockEnvironment env =
                    new MockEnvironment()
                            .withProperty("aaa.extended-hours.pre-cron", "0 15 11 * * MON-FRI")
                            .withProperty("aaa.extended-hours.after-cron", "0 45 21 * * MON-FRI");

            BatchRunRegistry registry = new BatchRunRegistry(env);

            assertThat(entry(registry, "extended-hours-pre").cron())
                    .isEqualTo("0 15 11 * * MON-FRI");
            assertThat(entry(registry, "extended-hours-after").cron())
                    .isEqualTo("0 45 21 * * MON-FRI");
        }
    }
}
