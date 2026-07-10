package com.aaa.collector.schedule;

import com.aaa.collector.schedule.catchup.ExpectedFireCalculator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 편입 배치별 {@code aaa_collector_batch_expected_run_seconds{batch}} 게이지를 노출한다
 * (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-001/003/005).
 *
 * <p>값은 배치 cron·zone 기준 "현재 시각 이전 마지막 예상 발화 시각"(epoch 초)이며, 값-함수(value-function) 게이지로 등록되어 저장 없이 매
 * scrape 시 {@link ExpectedFireCalculator}로 재계산된다(REQ-XR-001 stateless). {@code MON-FRI} cron은 주말 동안
 * 앵커가 전진하지 않아 금요일 슬롯이 잔류한다(REQ-XR-005) — 이는 계산기 계약(now 이전 마지막 발화 반환)에서 자동 성립한다.
 *
 * <p><b>패키지 배치 근거</b>: 이 게이지는 {@link BatchRunRegistry}(cron/zone 단일 소스)와 {@link
 * ExpectedFireCalculator}를 소비한다 — 둘 다 {@code schedule} 슬라이스에 있다. {@code
 * observability}(BatchMetrics)에 두면 {@code observability → schedule → stock →
 * observability}(CatchUpRunner가 stock 수집기를 재호출) 순환이 생겨 아키텍처 규칙 {@code
 * MdcArchitectureTest.noCircularDependenciesBetweenFeaturePackages}를 위반한다. 레지스트리와 같은 슬라이스에 두어
 * {@code observability}를 sink로 유지한다.
 */
@Component
@RequiredArgsConstructor
public class ExpectedRunGaugeBinder {

    static final String EXPECTED_RUN_NAME = "aaa_collector_batch_expected_run_seconds";

    private final MeterRegistry registry;
    private final Clock clock;
    private final BatchRunRegistry runRegistry;
    private final ExpectedFireCalculator expectedFireCalculator;

    /** 레지스트리 전 엔트리에 대해 expected_run 값-함수 게이지를 등록한다. */
    @PostConstruct
    void registerExpectedRunGauges() {
        for (BatchRunEntry entry : runRegistry.entries()) {
            Gauge.builder(EXPECTED_RUN_NAME, entry, this::expectedRunEpoch)
                    .tag("batch", entry.label())
                    .strongReference(true)
                    .register(registry);
        }
    }

    /**
     * 엔트리의 cron·zone과 현재 시각으로 직전 발화 epoch 초를 산출한다.
     *
     * <p>계산기가 빈 값을 반환하면(8일 lookback 내 슬롯 없음 — 주1회 이상 cadence인 편입 배치에서는 실질적으로 발생하지 않는 방어 경로) {@link
     * Double#NaN}을 노출한다. 값-함수 게이지는 시계열을 1회 등록 후 매 scrape 폴링하므로 scrape별 미노출이 불가능하다 — 따라서 유효 epoch와
     * 구분되는 {@code NaN}으로 "앵커 산출 불가"를 관측 가능하게 남긴다(REQ-XR-023/024 데드맨의 부재/NaN 감지 여지).
     */
    private double expectedRunEpoch(BatchRunEntry entry) {
        return expectedFireCalculator
                .calculate(entry.cron(), entry.zone(), clock.instant())
                .map(Instant::getEpochSecond)
                .map(Long::doubleValue)
                .orElse(Double.NaN);
    }
}
