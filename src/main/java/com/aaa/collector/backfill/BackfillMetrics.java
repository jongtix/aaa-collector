package com.aaa.collector.backfill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 백필 관측 메트릭 계측 (SPEC-COLLECTOR-BACKFILL-001 REQ-BACKFILL-040).
 *
 * <p>per-stock 종목 라벨을 사용하지 않는다 (카디널리티 억제, REQ-BACKFILL-040 / BatchMetrics REQ-OBSV-022 동일 원칙).
 *
 * <ul>
 *   <li>{@value #PROGRESS_NAME} — 진행률 gauge (완료/전체, 0.0~1.0)
 *   <li>{@value #WINDOW_ROWS_NAME} — 윈도우 실행 카운터 (실행된 윈도우 수 누적)
 *   <li>{@value #CLAMP_SUSPECTED_NAME} — 클램프 의심 종료 카운터 (REQ-BACKFILL-014a)
 *   <li>{@value #WINDOWS_TOTAL_NAME} — 실행된 총 윈도우 수 (운영 가시성)
 * </ul>
 */
// @MX:ANCHOR: [AUTO] 백필 진행률·윈도우 적재 건수·클램프 의심 계측 진입점
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-001 REQ-BACKFILL-040, REQ-014a — BackfillOrchestrator에서
// fan_in=1이나 관측성 단일 진입점
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-001
@Component
@RequiredArgsConstructor
public class BackfillMetrics {

    static final String PROGRESS_NAME = "aaa_collector_backfill_progress_ratio";
    static final String WINDOW_ROWS_NAME = "aaa_collector_backfill_window_rows_total";
    static final String CLAMP_SUSPECTED_NAME = "aaa_collector_backfill_clamp_suspected_total";
    static final String WINDOWS_TOTAL_NAME = "aaa_collector_backfill_windows_total";

    /** 백필 미완료(PENDING+IN_PROGRESS) 슬롯 수 게이지 이름 (SPEC-OBSV-WATERMARK-001 REQ-WM-029). */
    static final String PENDING_SLOTS_NAME = "aaa_collector_backfill_pending_slots";

    private final MeterRegistry registry;

    /** 진행률(완료/전체) gauge가 지연 조회하는 가변 상태. */
    private final DoubleAdder progressHolder = new DoubleAdder();

    /** 미완료(PENDING+IN_PROGRESS) 슬롯 수 gauge가 지연 조회하는 가변 상태(REQ-WM-029). */
    private final AtomicLong pendingSlotsHolder = new AtomicLong(0L);

    /** 카운터를 0으로 사전 등록한다 (백필 실행 전에도 0 시계열 노출). */
    @PostConstruct
    void initCounters() {
        Counter.builder(WINDOW_ROWS_NAME).register(registry);
        Counter.builder(CLAMP_SUSPECTED_NAME).register(registry);
        Counter.builder(WINDOWS_TOTAL_NAME).register(registry);
        registry.gauge(PROGRESS_NAME, progressHolder, DoubleAdder::doubleValue);
        registry.gauge(PENDING_SLOTS_NAME, pendingSlotsHolder, AtomicLong::doubleValue);
    }

    /**
     * 미완료(PENDING+IN_PROGRESS) 슬롯 수를 설정한다(REQ-WM-029). FAILED·COMPLETED는 포함하지 않는다 — FAILED 영구 잔류가
     * 상시 알림을 유발하지 않도록 한다.
     *
     * @param pendingSlots PENDING+IN_PROGRESS 슬롯 수
     */
    public void setPendingSlots(long pendingSlots) {
        pendingSlotsHolder.set(pendingSlots);
    }

    /**
     * 윈도우 1구간 수집 완료 후 호출 — 윈도우 실행 카운터 및 행 수 카운터를 증가시킨다.
     *
     * @param rowCount 이번 윈도우의 적재 행 수 (0이면 윈도우만 카운트)
     */
    public void recordWindow(int rowCount) {
        Counter.builder(WINDOWS_TOTAL_NAME).register(registry).increment();
        if (rowCount > 0) {
            Counter.builder(WINDOW_ROWS_NAME).register(registry).increment(rowCount);
        }
    }

    /** 클램프 의심 종료 시 호출 (REQ-BACKFILL-014a). */
    public void recordClampSuspected() {
        Counter.builder(CLAMP_SUSPECTED_NAME).register(registry).increment();
    }

    /**
     * 오케스트레이터 run() 완료 후 진행률 gauge를 갱신한다.
     *
     * @param completedCount 완료(COMPLETED) 항목 수
     * @param totalCount 전체 항목 수 (0이면 gauge=0)
     */
    public void recordProgress(long completedCount, long totalCount) {
        double ratio = (totalCount > 0) ? (double) completedCount / totalCount : 0.0;
        progressHolder.reset();
        progressHolder.add(ratio);
    }
}
