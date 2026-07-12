package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchRunEntry;
import com.aaa.collector.schedule.BatchRunRegistry;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.warmstart.BatchMetricsWarmStarter;
import com.aaa.collector.warmstart.ExtendedHoursWarmSource;
import com.aaa.collector.warmstart.MarketWarmSource;
import com.aaa.collector.warmstart.WatchlistSyncWarmSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * 레지스트리 편입 배치 전수가 warm-start read-side(Redis 조회) 경로를 갖는지 강제하는 커버리지 가드
 * (SPEC-COLLECTOR-WARMSTART-REDIS-001 REQ-WSR-008/009, AC-9).
 *
 * <p><b>존재 이유</b>: {@link BatchRunRegistry} 편입 배치가 {@code BatchMetricsWarmStarter}의 Redis 우선 조회
 * 경로에서 누락되면, 그 배치는 재시작 시 여전히 DB 프록시로만 복원되어 오탐 웨이브(§1.2)가 남는다. 새 배치를 레지스트리에 추가하며 warm 배선을 잊는 함정을 빌드
 * 단계에서 차단한다 — {@link BatchRunRegistryParityTest} 스타일 미러(순수 로직 함수 + 비-공허성 입증).
 *
 * <p>Spring 컨텍스트 불필요 — POJO 조립 + Mockito로 {@code run()}을 실행해 {@link BatchLastLoadRepository#find}
 * 조회 라벨을 캡처한다.
 */
@DisplayName("BatchLastLoad Redis 커버리지 가드 — 레지스트리 편입 전수가 조회 경로 보유 (REQ-WSR-009, AC-9)")
class BatchLastLoadRedisCoverageTest {

    @Nested
    @DisplayName("가드 — 레지스트리 편입 배치 전수가 warm-start 시 Redis find() 조회된다")
    class RegistryCoverage {

        @Test
        @DisplayName("GREEN — 현재 코드베이스는 정합한다 (레지스트리 20종 ⊆ find() 조회 라벨)")
        void everyRegistryLabel_isQueriedFromRedis() {
            Set<String> queried = runAndCaptureQueriedLabels();

            List<String> violations = coverageViolations(registryLabels(), queried);

            assertThat(violations)
                    .as("레지스트리 편입 배치 전수가 Redis 조회 경로를 가져야 한다: %s", violations)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("가드 로직 비-공허성 입증 (RED) — 검증 로직이 실제 누락을 잡아낸다")
    class DetectionSanity {

        @Test
        @DisplayName("레지스트리 라벨 하나의 Redis 조회를 스킵시키면 커버리지 위반으로 탐지된다")
        void skippedRedisQuery_isDetected() {
            // Arrange — 실제 조회된 라벨 집합에서 레지스트리 라벨 하나를 제거한 손상 상태 구성
            Set<String> registry = registryLabels();
            String dropped = registry.iterator().next();
            Set<String> brokenQueried = new TreeSet<>(runAndCaptureQueriedLabels());
            brokenQueried.remove(dropped);

            // Act
            List<String> violations = coverageViolations(registry, brokenQueried);

            // Assert — 스킵된 라벨이 위반으로 보고되어야 한다
            assertThat(violations).anyMatch(v -> v.contains(dropped));
        }
    }

    /** 레지스트리 편입 라벨 − find() 조회 라벨 == 위반. 위반을 사람이 읽는 메시지 목록으로 반환한다(비면 정합). */
    private static List<String> coverageViolations(
            Set<String> registryLabels, Set<String> queriedLabels) {
        Set<String> missing = new TreeSet<>(registryLabels);
        missing.removeAll(queriedLabels);

        List<String> violations = new ArrayList<>();
        for (String label : missing) {
            violations.add("레지스트리 편입인데 Redis 조회(find) 경로 없음: " + label);
        }
        return violations;
    }

    /** 레지스트리 빈이 선언하는 라벨 집합(POJO 조립, Spring 컨텍스트 불요). */
    private static Set<String> registryLabels() {
        Set<String> labels = new TreeSet<>();
        for (BatchRunEntry entry : new BatchRunRegistry(new MockEnvironment()).entries()) {
            labels.add(entry.label());
        }
        return labels;
    }

    /**
     * 전 mock 의존으로 {@code BatchMetricsWarmStarter.run()}을 실행하고, {@link
     * BatchLastLoadRepository#find}에 전달된 배치 라벨 전수를 캡처한다. mock 기본값(Optional.empty())으로 Redis·프록시 모두
     * 비어 seed는 일어나지 않지만, warm 경로 진입 시 find()는 반드시 호출되므로 read-side 커버리지를 관측할 수 있다.
     */
    private static Set<String> runAndCaptureQueriedLabels() {
        BatchLastLoadRepository lastLoadRepository = mock(BatchLastLoadRepository.class);
        BatchMetricsWarmStarter warmStarter =
                new BatchMetricsWarmStarter(
                        mock(BatchMetrics.class),
                        lastLoadRepository,
                        mock(InvestorTrendRepository.class),
                        mock(CreditBalanceRepository.class),
                        mock(ShortSaleDomesticRepository.class),
                        mock(ShortSaleOverseasRepository.class),
                        mock(AnalystEstimateRepository.class),
                        mock(FinancialRepository.class),
                        mock(MacroIndicatorRepository.class),
                        mock(MarketIndicatorRepository.class),
                        mock(EtfRepresentativeHistoryRepository.class),
                        mock(DisclosureRepository.class),
                        mock(CorpCodeMappingRepository.class),
                        mock(DomesticNewsHeadlineRepository.class),
                        mock(OverseasNewsHeadlineRepository.class),
                        mock(ExtendedHoursWarmSource.class),
                        mock(WatchlistSyncWarmSource.class),
                        mock(MarketWarmSource.class));

        warmStarter.run(null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(lastLoadRepository, atLeastOnce()).find(captor.capture());
        return new TreeSet<>(captor.getAllValues());
    }
}
