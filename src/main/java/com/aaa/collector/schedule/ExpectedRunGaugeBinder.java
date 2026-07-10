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
 * 편입 배치별 {@code expected_run}·{@code run_margin} 게이지와 무라벨 {@code enrolled_total} 게이지를 노출한다
 * (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-001/003/005/006/007/031).
 *
 * <p>{@code expected_run}은 배치 cron·zone 기준 "현재 시각 이전 마지막 예상 발화 시각"(epoch 초)이며, 값-함수(value-function)
 * 게이지로 등록되어 저장 없이 매 scrape 시 {@link ExpectedFireCalculator}로 재계산된다(REQ-XR-001 stateless). {@code
 * MON-FRI} cron은 주말 동안 앵커가 전진하지 않아 금요일 슬롯이 잔류한다(REQ-XR-005) — 이는 계산기 계약(now 이전 마지막 발화 반환)에서 자동
 * 성립한다. {@code run_margin}은 엔트리의 고정 마진(초)을(REQ-XR-006), {@code enrolled_total}은 레지스트리 엔트리
 * 개수를(REQ-XR-007) 노출한다.
 *
 * <p><b>등록 정책 — eager 전수(REQ-XR-031)</b>: 세 종류의 게이지 시계열을 모두 {@link #registerGauges()}
 * ({@code @PostConstruct})에서 부팅 시 전수 등록한다 — 어떤 배치 cron이 아직 한 번도 발화하지 않았더라도 metrics 엔드포인트가 ready가 되기
 * 전에 시계열이 존재하도록 한다. 이는 {@code BatchMetrics.lastLoadHolder}가 쓰는 <b>lazy</b> {@code
 * computeIfAbsent}(완료-시-최초-touch)와 <b>의도적으로 다른</b> 정책이다. lazy로 두면 주1회 cadence 배치 (예: {@code
 * domestic-financial-ratio} 토요일)가 재배포 후 며칠간 시계열 부재가 되어 부분-소실 데드맨(REQ-XR-024)의 {@code for:10m}를 배포마다
 * 확정 오발시킨다 — eager 전수 등록이 그 debounce의 안전 전제조건이다.
 *
 * <p><b>패키지 배치 근거</b>: 이 게이지들은 {@link BatchRunRegistry}(cron/zone/margin 단일 소스)와 {@link
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
    static final String RUN_MARGIN_NAME = "aaa_collector_batch_run_margin_seconds";
    static final String ENROLLED_TOTAL_NAME = "aaa_collector_batch_enrolled_total";

    private final MeterRegistry registry;
    private final Clock clock;
    private final BatchRunRegistry runRegistry;
    private final ExpectedFireCalculator expectedFireCalculator;

    /**
     * 부팅 시 전 게이지 시계열을 eager 전수 등록한다 (REQ-XR-031).
     *
     * <p>{@code expected_run}·{@code run_margin} 각 N개와 무라벨 {@code enrolled_total} 1개를 metrics 엔드포인트
     * ready 이전에 등록한다 — cron 발화·완료 이벤트를 기다리는 lazy 패턴과 달리 부팅 직후부터 시계열이 존재한다.
     */
    @PostConstruct
    void registerGauges() {
        registerExpectedRunGauges();
        registerRunMarginGauges();
        registerEnrolledTotalGauge();
    }

    /** 레지스트리 전 엔트리에 대해 expected_run 값-함수 게이지를 등록한다. */
    void registerExpectedRunGauges() {
        for (BatchRunEntry entry : runRegistry.entries()) {
            Gauge.builder(EXPECTED_RUN_NAME, entry, this::expectedRunEpoch)
                    .tag("batch", entry.label())
                    .strongReference(true)
                    .register(registry);
        }
    }

    /**
     * 레지스트리 전 엔트리에 대해 run_margin 게이지를 등록한다 (REQ-XR-006).
     *
     * <p>마진은 엔트리에 고정된 상수라 재계산이 무의미하지만, 레지스트리를 유일 소스로 유지하려고 하드코딩 상수 대신 엔트리 값을 그대로 노출한다. 값-함수 형태는
     * expected_run과 동일한 등록 패턴을 재사용해 배선 단순성을 얻는다.
     */
    void registerRunMarginGauges() {
        for (BatchRunEntry entry : runRegistry.entries()) {
            Gauge.builder(RUN_MARGIN_NAME, entry, BatchRunEntry::marginSeconds)
                    .tag("batch", entry.label())
                    .strongReference(true)
                    .register(registry);
        }
    }

    /**
     * 무라벨 enrolled_total 게이지를 레지스트리 크기로 등록한다 (REQ-XR-007).
     *
     * <p>값을 레지스트리에서 읽어 하드코딩 상수 동기화를 없앤다 — 배치 편입/제거 시 aaa-infra 룰이 수동 개입 없이 부분-소실을 탐지할 수 있다(DP-6
     * REQ-XR-024 소비).
     */
    void registerEnrolledTotalGauge() {
        Gauge.builder(ENROLLED_TOTAL_NAME, runRegistry, r -> r.entries().size())
                .strongReference(true)
                .register(registry);
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
