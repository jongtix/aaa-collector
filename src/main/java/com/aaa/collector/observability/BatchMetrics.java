package com.aaa.collector.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * per-stock 핵심 배치 집계 계측 (REQ-OBSV-020/021/022/023).
 *
 * <p>배치 단위(label: {@code batch})로 다음 시계열을 노출한다 — per-stock 종목 라벨은 사용하지 않는다(REQ-OBSV-022, 카디널리티 폭증
 * 방지).
 *
 * <ul>
 *   <li>{@value #TARGET_NAME}/{@value #SUCCESS_NAME}/{@value #FAIL_NAME}/{@value #SKIP_NAME} —
 *       대상/성공/실패/스킵 누적 카운터
 *   <li>{@value #COMPLETENESS_NAME} — 충족률 gauge(성공/대상, 대상 0이면 0)
 *   <li>{@value #LAST_LOAD_NAME} — 마지막 성공 적재 시각 gauge(KST epoch 초, ADR-009)
 * </ul>
 *
 * <p>침묵 드롭(REQ-OBSV-023, DP4 단일 카운터): {@value #SILENT_DROP_NAME}는 사유별 라벨 없이 INSERT IGNORE 중복 외 사유로
 * 침묵 드롭된 행 수를 단일 카운터로 누적한다.
 */
// @MX:ANCHOR: [AUTO] 배치 집계 계측 진입점 — 일봉·수급 배치 완료 지점에서 호출
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-020/021/022/023 — 일봉 1 + 수급 3종 + 침묵 드롭에서 fan_in >= 4
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
@Component
@RequiredArgsConstructor
public class BatchMetrics {

    static final String TARGET_NAME = "aaa_collector_batch_target_total";
    static final String SUCCESS_NAME = "aaa_collector_batch_success_total";
    static final String FAIL_NAME = "aaa_collector_batch_fail_total";
    static final String SKIP_NAME = "aaa_collector_batch_skip_total";
    static final String COMPLETENESS_NAME = "aaa_collector_batch_completeness_ratio";
    static final String LAST_LOAD_NAME = "aaa_collector_batch_last_load_seconds";
    static final String SILENT_DROP_NAME = "aaa_collector_batch_silent_drops_total";

    private final MeterRegistry registry;
    private final Clock clock;

    /** batch → 충족률(성공/대상) gauge가 지연 조회하는 가변 상태. */
    private final Map<String, DoubleAdder> completeness = new ConcurrentHashMap<>();

    /** batch → 마지막 성공 적재 epoch 초 gauge 상태. */
    private final Map<String, AtomicLong> lastLoad = new ConcurrentHashMap<>();

    /** 침묵 드롭 카운터를 0으로 사전 등록한다 (드롭이 한 번도 없어도 0 시계열 노출). */
    @PostConstruct
    void initSilentDropCounter() {
        Counter.builder(SILENT_DROP_NAME).register(registry);
    }

    /**
     * 배치 한 회차의 집계 결과를 기록한다 (REQ-OBSV-020/021).
     *
     * <p>대상/성공/실패/스킵 카운터를 증가시키고, 충족률 gauge(성공/대상)와 마지막 적재 시각 gauge(현재 KST epoch 초)를 갱신한다.
     *
     * @param batch 배치 라벨(예: {@code domestic-daily}, {@code domestic-supply-investor})
     * @param target 대상 종목 수(분모)
     * @param success 성공 종목 수
     * @param fail 실패 종목 수
     * @param skip 스킵 종목 수
     */
    public void recordCompletion(String batch, long target, long success, long fail, long skip) {
        counter(TARGET_NAME, batch).increment(target);
        counter(SUCCESS_NAME, batch).increment(success);
        counter(FAIL_NAME, batch).increment(fail);
        counter(SKIP_NAME, batch).increment(skip);

        double ratio = (target > 0) ? (double) success / target : 0.0;
        completenessHolder(batch).reset();
        completenessHolder(batch).add(ratio);

        lastLoadHolder(batch).set(clock.instant().getEpochSecond());
    }

    /**
     * 부팅 시 DB max 타임스탬프로 last-load gauge를 초기화한다 (SPEC-OBSV-WARMSTART-001 REQ-001/002/003).
     *
     * <p>카운터(target/success/fail/skip_total), completeness gauge는 건드리지 않는다. 멱등 — 동일 배치에 재호출 시 새 값으로
     * 덮어쓴다.
     *
     * @param batch 배치 라벨(예: {@code domestic-daily})
     * @param lastLoad DB에서 조회한 마지막 적재 시각 (UTC Instant)
     */
    public void warmLastLoad(String batch, Instant lastLoad) {
        lastLoadHolder(batch).set(lastLoad.getEpochSecond());
    }

    /**
     * 침묵 드롭(INSERT IGNORE 중복 외 사유) 건수를 단일 카운터로 누적한다 (REQ-OBSV-023, DP4 단일 카운터).
     *
     * @param drops 이번 배치에서 관측된 침묵 드롭 건수(0이면 증가하지 않음)
     */
    public void recordSilentDrops(long drops) {
        // Micrometer 등록은 멱등 — 동일 이름은 동일 카운터 반환(Spring·직접 생성 양쪽 안전, 드롭 0이어도 0 시계열 보장).
        Counter counter = Counter.builder(SILENT_DROP_NAME).register(registry);
        if (drops <= 0) {
            return;
        }
        counter.increment(drops);
    }

    private Counter counter(String name, String batch) {
        return Counter.builder(name).tag("batch", batch).register(registry);
    }

    private DoubleAdder completenessHolder(String batch) {
        return completeness.computeIfAbsent(
                batch,
                b -> {
                    DoubleAdder adder = new DoubleAdder();
                    registry.gauge(
                            COMPLETENESS_NAME,
                            Tags.of("batch", b),
                            adder,
                            DoubleAdder::doubleValue);
                    return adder;
                });
    }

    private AtomicLong lastLoadHolder(String batch) {
        return lastLoad.computeIfAbsent(
                batch,
                b ->
                        registry.gauge(
                                LAST_LOAD_NAME,
                                Tags.of("batch", b),
                                new AtomicLong(),
                                AtomicLong::doubleValue));
    }
}
