package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchRunEntry;
import com.aaa.collector.schedule.BatchRunRegistry;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 레지스트리 ↔ {@code recordCompletion} 호출자 양방향 패리티 가드 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-004,
 * AC-14).
 *
 * <p><b>존재 이유</b>: "개발자가 새 배치를 추가하며 레지스트리 등록을 잊는" 함정과 "aaa-infra 제외 정규식 갱신을 잊는" 함정을 빌드 단계에서 원천
 * 차단한다. {@code aaa_collector_batch_last_load_seconds}를 stamp하는 모든 배치 라벨은 정확히 하나의 레지스트리 엔트리를
 * 가지며(게이트-레거시 3배치 제외), 역으로 모든 레지스트리 엔트리는 정확히 하나의 stamp 경로에 대응해야 한다.
 *
 * <p><b>왜 하이브리드(ArchUnit 클래스 발견 + 명시 라벨 테이블)인가</b>: 라벨은 문자열 상수이며 {@link
 * com.aaa.collector.stock.supply.DomesticSupplyDemandCollectionService}는 {@code BATCH_LABEL_PREFIX
 * + "investor"} 식 런타임 결합이라 바이트코드 분석만으로는 라벨 <b>값</b>을 얻을 수 없다. 따라서 (1) {@code recordCompletion} 호출자
 * <b>클래스 집합</b>은 ArchUnit 바이트코드 스캔으로 자동 발견하고, (2) 각 클래스가 stamp하는 라벨 값은 명시 테이블({@link
 * #EXPECTED_STAMPS})로 선언한다. 발견된 클래스 집합과 테이블 키가 반드시 일치해야 하므로(가드 A), 테이블이 현실에서 조용히 드리프트할 수 없다 — 새
 * 호출자를 추가하면 가드 A가 깨져 테이블 갱신을 강제하고, 그로 인해 새 라벨이 유입되면 가드 B(양방향 라벨 패리티)가 깨져 레지스트리 편입(또는 제외 집합 갱신)을
 * 강제한다.
 *
 * <p>Spring 컨텍스트 불필요 — 순수 클래스파일 스캔 + POJO 레지스트리 조립.
 */
@DisplayName("BatchRunRegistry ↔ recordCompletion 호출자 양방향 패리티 (REQ-XR-004, AC-14)")
class BatchRunRegistryParityTest {

    /**
     * 게이트-레거시 3배치 — {@code recordCompletion}은 여전히 호출하지만 레지스트리에는 의도적으로 미편입(SPEC §3.2).
     *
     * <p>데이터 신선도가 워터마크로 이미 커버되어 실행 감시가 중복이므로 제거됐다(aaa-infra#78 landing, REQ-XR-020). 이 집합은 "제외 정규식
     * 갱신 망각" 함정을 막기 위해 테스트 내 명시 상수로 둔다 — 게이트-레거시 배치가 추가/제거되면 패리티가 깨져 이 집합의 재검토를 강제한다.
     */
    private static final Set<String> GATE_LEGACY_EXCLUSIONS =
            Set.of("domestic-daily", "market-indicators", "dart-disclosure");

    /**
     * {@code recordCompletion} 호출자 클래스(단순명) → 그 클래스가 stamp하는 라벨 집합.
     *
     * <p>바이트코드로 값을 얻을 수 없는 라벨(특히 {@code domestic-supply-*} prefix 결합)을 명시 선언한다. 이 테이블의 키 집합은 {@link
     * #discoverStampingClasses()}가 발견한 실제 호출자 집합과 정확히 일치해야 한다(가드 A).
     */
    private static final Map<String, Set<String>> EXPECTED_STAMPS =
            Map.ofEntries(
                    Map.entry("DartDisclosureBackfillScheduler", Set.of("dart-backfill")),
                    Map.entry("DartDisclosurePollingService", Set.of("dart-disclosure")),
                    Map.entry("CorpCodeUpdateScheduler", Set.of("corp-code")),
                    Map.entry("MacroExternalScheduler", Set.of("macro-external")),
                    Map.entry("NewsScheduler", Set.of("domestic-news")),
                    Map.entry("OverseasNewsScheduler", Set.of("overseas-news")),
                    Map.entry("MarketBatchScheduler", Set.of("market-indicators", "market-usdkrw")),
                    Map.entry(
                            "ShortSaleOverseasDailyCollectionService",
                            Set.of("overseas-shortsale-daily")),
                    Map.entry(
                            "ShortSaleOverseasInterestCollectionService",
                            Set.of("overseas-shortsale-interest")),
                    Map.entry(
                            "ExtendedHoursScheduler",
                            Set.of("extended-hours-pre", "extended-hours-after")),
                    Map.entry("DomesticDailyOhlcvScheduler", Set.of("domestic-daily")),
                    Map.entry("OverseasDailyOhlcvScheduler", Set.of("overseas-daily")),
                    Map.entry("FinancialRatioScheduler", Set.of("domestic-financial-ratio")),
                    Map.entry(
                            "DomesticSupplyDemandCollectionService",
                            Set.of(
                                    "domestic-supply-investor",
                                    "domestic-supply-short-sale",
                                    "domestic-supply-credit-balance")),
                    Map.entry("InvestOpinionScheduler", Set.of("domestic-invest-opinion")),
                    Map.entry("OverseasSplitScheduler", Set.of("overseas-split")),
                    Map.entry("OverseasRightsScheduler", Set.of("overseas-rights")),
                    Map.entry("EtfRepresentativeScheduler", Set.of("domestic-etf-representative")),
                    Map.entry(
                            "WatchlistSyncScheduler",
                            Set.of("watchlist-sync-krx", "watchlist-sync-us")));

    /** {@code BatchMetrics.recordCompletion} 직접 호출을 메서드명·소유 타입으로 매칭하는 술어. */
    private static final DescribedPredicate<JavaMethodCall> CALL_TO_RECORD_COMPLETION =
            DescribedPredicate.describe(
                    "BatchMetrics.recordCompletion 직접 호출",
                    call ->
                            "recordCompletion".equals(call.getTarget().getName())
                                    && call.getTarget()
                                            .getOwner()
                                            .isAssignableTo(BatchMetrics.class));

    @Nested
    @DisplayName("가드 A — recordCompletion 호출자 클래스 집합이 명시 테이블과 일치해야 한다")
    class CallerClassParity {

        @Test
        @DisplayName("바이트코드로 발견한 호출자 클래스 집합 == EXPECTED_STAMPS 키 집합")
        void discoveredCallers_matchExpectedTableKeys() {
            // Act — 프로덕션 클래스만 스캔(BatchMetrics 자기 자신은 정의처이므로 제외)
            Set<String> discovered = discoverStampingClasses();

            // Assert — 신규 호출자 추가 시 이 단언이 깨져 EXPECTED_STAMPS 갱신을 강제한다(트랩 1 클래스 단위 차단)
            assertThat(discovered)
                    .as("recordCompletion 호출자 클래스 집합이 명시 라벨 테이블 키와 일치해야 한다")
                    .isEqualTo(EXPECTED_STAMPS.keySet());
        }
    }

    @Nested
    @DisplayName("가드 B — 레지스트리와 stamp 라벨 집합의 양방향 패리티")
    class LabelParity {

        @Test
        @DisplayName("GREEN — 현재 코드베이스는 정합한다 (제외 집합 반영, 레지스트리 21 == stamp 24 - 제외 3)")
        void currentCodebase_isInParity() {
            // Arrange
            Set<String> stampLabels = allStampLabels();
            Set<String> registryLabels = registryLabels();

            // Act
            List<String> violations =
                    parityViolations(stampLabels, registryLabels, GATE_LEGACY_EXCLUSIONS);

            // Assert
            assertThat(violations)
                    .as("레지스트리 ↔ stamp 라벨 양방향 패리티 위반이 없어야 한다: %s", violations)
                    .isEmpty();
        }

        @Test
        @DisplayName("최종 라벨 집합 크기 확정 — stamp 24, 제외 3, 레지스트리 21 (§3.3 실측, market-usdkrw 편입)")
        void labelSetCardinalities_areExact() {
            assertThat(allStampLabels()).as("stamp 라벨 총수").hasSize(24);
            assertThat(GATE_LEGACY_EXCLUSIONS).as("게이트-레거시 제외 수").hasSize(3);
            assertThat(registryLabels()).as("레지스트리 엔트리 수").hasSize(21);
        }

        @Test
        @DisplayName("모든 레지스트리 라벨은 stamp 경로를 가진다 (registryLabels ⊆ stampLabels)")
        void everyRegistryLabel_hasStampPath() {
            assertThat(allStampLabels()).containsAll(registryLabels());
        }
    }

    @Nested
    @DisplayName("가드 로직 비-공허성 입증 (RED) — 검증 로직이 실제 위반을 잡아낸다")
    class DetectionSanity {

        @Test
        @DisplayName("레지스트리에서 라벨 하나를 빠뜨리면 패리티 위반으로 탐지된다")
        void missingRegistryEntry_isDetected() {
            // Arrange — 실제 stamp 집합은 그대로 두고 레지스트리에서 overseas-split을 제거한 손상 상태를 구성
            Set<String> stampLabels = allStampLabels();
            Set<String> brokenRegistry = new TreeSet<>(registryLabels());
            brokenRegistry.remove("overseas-split");

            // Act
            List<String> violations =
                    parityViolations(stampLabels, brokenRegistry, GATE_LEGACY_EXCLUSIONS);

            // Assert — stamp되지만 미등록인 라벨로 보고되어야 한다
            assertThat(violations).anyMatch(v -> v.contains("overseas-split") && v.contains("미등록"));
        }

        @Test
        @DisplayName("제외 집합에서 게이트-레거시 배치를 빼면 패리티 위반으로 탐지된다")
        void emptiedExclusion_isDetected() {
            // Arrange — 제외 집합을 비우면 게이트-레거시 3배치가 잉여 stamp로 노출되어야 한다
            List<String> violations =
                    parityViolations(allStampLabels(), registryLabels(), Set.of());

            // Assert
            assertThat(violations).anyMatch(v -> v.contains("domestic-daily"));
        }

        @Test
        @DisplayName("실제로 stamp되지 않는 라벨을 제외 집합에 넣으면 stale-exclusion으로 탐지된다")
        void staleExclusion_isDetected() {
            // Arrange — 존재하지 않는 라벨을 제외 집합에 추가
            Set<String> staleExclusions = new TreeSet<>(GATE_LEGACY_EXCLUSIONS);
            staleExclusions.add("nonexistent-batch");

            // Act
            List<String> violations =
                    parityViolations(allStampLabels(), registryLabels(), staleExclusions);

            // Assert
            assertThat(violations)
                    .anyMatch(v -> v.contains("nonexistent-batch") && v.contains("stale"));
        }
    }

    /**
     * stamp 라벨 집합(제외 반영 전 전수) − 제외 집합 == 레지스트리 라벨, 그리고 제외 집합의 모든 라벨은 실제 stamp되어야 한다. 위반을 사람이 읽을 수
     * 있는 메시지 목록으로 반환한다(비면 정합).
     */
    private static List<String> parityViolations(
            Set<String> stampLabels, Set<String> registryLabels, Set<String> exclusions) {
        Set<String> residual = new TreeSet<>(stampLabels);
        residual.removeAll(exclusions);

        Set<String> stampedButUnregistered = new TreeSet<>(residual);
        stampedButUnregistered.removeAll(registryLabels);

        Set<String> registeredButUnstamped = new TreeSet<>(registryLabels);
        registeredButUnstamped.removeAll(residual);

        Set<String> staleExclusions = new TreeSet<>(exclusions);
        staleExclusions.removeAll(stampLabels);

        List<String> violations = new ArrayList<>();
        for (String label : stampedButUnregistered) {
            violations.add("stamp되지만 레지스트리 미등록: " + label);
        }
        for (String label : registeredButUnstamped) {
            violations.add("레지스트리에 있으나 stamp 경로 없음: " + label);
        }
        for (String label : staleExclusions) {
            violations.add("stale 제외(실제 stamp 안 됨): " + label);
        }
        return violations;
    }

    /** {@link #EXPECTED_STAMPS} 값 전체의 합집합 = 전 stamp 라벨. */
    private static Set<String> allStampLabels() {
        Set<String> labels = new TreeSet<>();
        EXPECTED_STAMPS.values().forEach(labels::addAll);
        return labels;
    }

    /** 레지스트리 빈이 선언하는 라벨 집합(POJO 조립, Spring 컨텍스트 불요). */
    private static Set<String> registryLabels() {
        Set<String> labels = new TreeSet<>();
        for (BatchRunEntry entry : new BatchRunRegistry(new MockEnvironment()).entries()) {
            labels.add(entry.label());
        }
        return labels;
    }

    /** 프로덕션 코드에서 {@code recordCompletion}을 호출하는 클래스 단순명 집합을 바이트코드로 발견한다. */
    private static Set<String> discoverStampingClasses() {
        Set<String> callers = new TreeSet<>();
        new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.aaa.collector")
                .forEach(
                        javaClass ->
                                javaClass.getMethodCallsFromSelf().stream()
                                        .filter(CALL_TO_RECORD_COMPLETION)
                                        .findAny()
                                        .ifPresent(
                                                call ->
                                                        callers.add(
                                                                topLevelSimpleName(
                                                                        javaClass.getName()))));
        return callers;
    }

    /** 중첩/합성 클래스명({@code Outer$Inner})을 최상위 클래스 단순명으로 정규화한다. */
    private static String topLevelSimpleName(String fullyQualifiedName) {
        String topLevel = fullyQualifiedName.split("\\$", 2)[0];
        int lastDot = topLevel.lastIndexOf('.');
        return lastDot < 0 ? topLevel : topLevel.substring(lastDot + 1);
    }
}
