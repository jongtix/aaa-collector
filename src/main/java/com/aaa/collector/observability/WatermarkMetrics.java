package com.aaa.collector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 데이터 워터마크 게이지 계측 (SPEC-OBSV-WATERMARK-001 REQ-WM-001~005).
 *
 * <p>{@value #WATERMARK_NAME}{@code {series, market}} — 시리즈별 성공 적재된 비즈니스 날짜의 최댓값을 KST 자정 epoch 초로
 * 노출한다. {@link #advance(WatermarkSeries, LocalDate)}는 forward-only(REQ-WM-001) — 백필로 더 이른 날짜가 삽입돼도
 * 후퇴하지 않는다. {@link #resync(WatermarkSeries, LocalDate)}는 부팅 warm-start(REQ-WM-003)·수동 재동기화
 * 옵션(REQ-WM-004)이 사용하는 절대값 설정 — 운영자 TRUNCATE/재구축 후 자가치유를 위해 forward-only 제약을 받지 않는다.
 *
 * <p>{@link #initGauges()}가 §3 사전의 17 시계열 전부를 0으로 사전 등록하여 부팅 직후에도 게이지가 absent가 되지 않게 한다(REQ-WM-003
 * 부분 소실 방어, MA-05).
 */
// @MX:ANCHOR: [AUTO] 데이터 워터마크 게이지 갱신 진입점 — 17 시리즈 Inserter/CollectionService에서 fan_in >= 3
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-001/003/004 — 삽입 시 forward-only 갱신 + 부팅/재동기화 절대값 설정
// @MX:SPEC: SPEC-OBSV-WATERMARK-001
@Component
@RequiredArgsConstructor
public class WatermarkMetrics {

    static final String WATERMARK_NAME = "aaa_collector_data_watermark_seconds";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MeterRegistry registry;

    private final Map<WatermarkSeries, AtomicLong> holders = new ConcurrentHashMap<>();

    /** §3 사전 17 시계열 전부를 0으로 사전 등록한다 (REQ-WM-003, absent 방지). */
    @PostConstruct
    void initGauges() {
        for (WatermarkSeries series : WatermarkSeries.values()) {
            holder(series);
        }
    }

    /**
     * 시리즈 워터마크를 forward-only로 갱신한다 (REQ-WM-001).
     *
     * <p>{@code date}가 {@code null}이면 무동작 — 갱신할 비즈니스 날짜가 없는 호출을 무해하게 흡수한다.
     *
     * @param series 갱신할 시리즈
     * @param date 성공 적재된 행의 비즈니스 날짜
     */
    public void advance(WatermarkSeries series, LocalDate date) {
        if (date == null) {
            return;
        }
        long epoch = kstMidnightEpoch(date);
        holder(series).getAndUpdate(current -> Math.max(current, epoch));
    }

    /**
     * 시리즈 워터마크를 절대값으로 설정한다 (부팅 warm-start REQ-WM-003, 수동 재동기화 REQ-WM-004).
     *
     * <p>{@link #advance(WatermarkSeries, LocalDate)}와 달리 forward-only 제약이 없다 — 운영자 TRUNCATE/재구축으로
     * DB 최댓값이 현재 게이지보다 낮아질 수 있는 상황을 반영한다.
     *
     * @param series 설정할 시리즈
     * @param date DB {@code MAX(기준 날짜 컬럼)} 조회 결과 ({@code null}이면 0으로 설정 — 빈 테이블)
     */
    public void resync(WatermarkSeries series, LocalDate date) {
        long epoch = date == null ? 0L : kstMidnightEpoch(date);
        holder(series).set(epoch);
    }

    private AtomicLong holder(WatermarkSeries series) {
        return holders.computeIfAbsent(
                series,
                s -> {
                    AtomicLong atomic = new AtomicLong(0L);
                    registry.gauge(
                            WATERMARK_NAME,
                            Tags.of("series", s.seriesLabel(), "market", s.market()),
                            atomic,
                            AtomicLong::doubleValue);
                    return atomic;
                });
    }

    private static long kstMidnightEpoch(LocalDate date) {
        return date.atStartOfDay(KST).toEpochSecond();
    }
}
