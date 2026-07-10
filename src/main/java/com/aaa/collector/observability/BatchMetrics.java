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
// @MX:ANCHOR: [AUTO] 배치 집계 계측 진입점 — 일봉·수급 배치 완료 지점 + 파싱 거부 계측에서 호출
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-020/021/022/023 +
// SPEC-COLLECTOR-SHORTSALE-DECIMAL-001
// REQ-SSD-009 — 일봉 1 + 수급 3종 + 침묵 드롭 + 파싱 거부(Daily·Interest·CDN 백필)에서 fan_in >= 4
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
    static final String LAST_DATA_NAME = "aaa_collector_batch_last_data_seconds";
    static final String SILENT_DROP_NAME = "aaa_collector_batch_silent_drops_total";
    static final String PARSE_REJECT_NAME = "aaa_collector_batch_parse_rejections_total";

    /** 모든 배치 시계열이 공유하는 라벨 키 (카디널리티: batch 값만 사용). */
    private static final String BATCH_TAG = "batch";

    private final MeterRegistry registry;
    private final Clock clock;

    /** batch → 충족률(성공/대상) gauge가 지연 조회하는 가변 상태. */
    private final Map<String, DoubleAdder> completeness = new ConcurrentHashMap<>();

    /** batch → 마지막 성공 적재 epoch 초 gauge 상태. */
    private final Map<String, AtomicLong> lastLoad = new ConcurrentHashMap<>();

    /** batch → 마지막 실데이터 도착 epoch 초 gauge 상태 (DP-5, 현재 interest 한 배치만). */
    private final Map<String, AtomicLong> lastData = new ConcurrentHashMap<>();

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
     * 실데이터 도착 시각을 {@code last_data} gauge에 stamp한다 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-016,
     * DP-5).
     *
     * <p>실행/도착 분리: {@code last_load}(빈 응답도 stamp, "폴러가 돌았는가")와 달리, 이 gauge는 실제로 {@code success>0}
     * 행이 도착한 경우에만 호출된다("실데이터가 도착했는가"). 현재 {@code overseas-shortsale-interest} 한 배치만 사용한다(DP-5,
     * YAGNI). {@code last_load}·카운터는 건드리지 않는다.
     *
     * @param batch 배치 라벨(현재 {@code overseas-shortsale-interest})
     */
    public void recordDataArrival(String batch) {
        lastDataHolder(batch).set(clock.instant().getEpochSecond());
    }

    /**
     * 부팅 시 DB max 타임스탬프로 {@code last_data} gauge를 초기화한다 (REQ-XR-017, {@code warmLastLoad} 미러).
     *
     * <p>{@code BatchMetricsWarmStarter}가 기존 {@code findMaxInterestCollectedAt} 쿼리 결과로 seed한다. 멱등 —
     * 재호출 시 새 값으로 덮어쓴다.
     *
     * @param batch 배치 라벨(현재 {@code overseas-shortsale-interest})
     * @param lastData DB에서 조회한 마지막 실데이터 적재 시각 (UTC Instant)
     */
    public void warmDataArrival(String batch, Instant lastData) {
        lastDataHolder(batch).set(lastData.getEpochSecond());
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

    /**
     * 파싱 거부(음수·scale 초과·형식 오류) 건수를 배치 라벨별 카운터로 누적한다 (REQ-SSD-009).
     *
     * <p>기존 {@code last_load} gauge·{@code skip_total}와 독립적인 시계열로, 특히 종전 Micrometer 계측이 전무하던 CDN 백필
     * 경로의 파싱 거부를 관측 가능하게 한다. {@code rejections=0}으로 호출하면 0 값으로 시계열을 등록해 "계측 연결됨"을 노출한다(멱등).
     *
     * @param batch 배치 라벨(예: {@code overseas-shortsale-backfill}, {@code overseas-shortsale-daily})
     * @param rejections 이번 배치에서 관측된 파싱 거부 건수(음수는 0으로 취급)
     */
    public void recordParseRejections(String batch, long rejections) {
        counter(PARSE_REJECT_NAME, batch).increment(Math.max(0L, rejections));
    }

    private Counter counter(String name, String batch) {
        return Counter.builder(name).tag(BATCH_TAG, batch).register(registry);
    }

    private DoubleAdder completenessHolder(String batch) {
        return completeness.computeIfAbsent(
                batch,
                b -> {
                    DoubleAdder adder = new DoubleAdder();
                    registry.gauge(
                            COMPLETENESS_NAME,
                            Tags.of(BATCH_TAG, b),
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
                                Tags.of(BATCH_TAG, b),
                                new AtomicLong(),
                                AtomicLong::doubleValue));
    }

    private AtomicLong lastDataHolder(String batch) {
        return lastData.computeIfAbsent(
                batch,
                b ->
                        registry.gauge(
                                LAST_DATA_NAME,
                                Tags.of(BATCH_TAG, b),
                                new AtomicLong(),
                                AtomicLong::doubleValue));
    }
}
