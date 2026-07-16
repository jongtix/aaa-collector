package com.aaa.collector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 과거 데이터 밀도 게이지 계측 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155/-156, Axis 3).
 *
 * <p>{@link CoverageRefresher}가 시장별 일1회 cron에서 계산한 두 값을 노출한다:
 *
 * <ul>
 *   <li>{@value #BELOW_FLOOR_NAME}(게이지 A) — 신뢰 가능한 하한(trusted floor) 존재 종목 중 {@code MIN(trade_date)
 *       > floor}인 하한 미달 종목 수(REQ-154). 신뢰 하한이 없는 미검증 레거시 국내 종목은 모집단에서 제외돼 영구 오탐이 없다.
 *   <li>{@value #INTERNAL_GAP_NAME}(게이지 B) — 활성 유니버스 거래일 캘린더 합집합 대비 보유 구간 내부에 부재 거래일이 1개 이상인 종목
 *       수(REQ-155). 레거시 포함 전 COMPLETED를 감시한다(신뢰 하한 불필요).
 * </ul>
 *
 * <p>부팅 직후 스크랩 공백을 없애기 위해 두 시장(KRX/US) 라벨을 0으로 pre-register한다({@link
 * com.aaa.collector.warmstart.BatchMetricsWarmStarter} 패턴과 동일한 목적, REQ-156) — 실제 cron이 값을 갱신하기 전까지
 * 0 시계열이 노출된다.
 *
 * <p>일1회 held 게이지라 재시작 시 실제 값을 잃고 0으로 리셋되면 vmalert가 조건 해소로 오판한다. 이를 막기 위해 set* 시 계산값을 Redis에
 * best-effort로 영속화하고({@link BackfillDensityRepository}), 부팅 시 {@code
 * BackfillDensityMetricsWarmStarter}가 {@code warm*}으로 실제 값을
 * 복원한다(SPEC-COLLECTOR-WARMSTART-REDIS-004).
 */
// @MX:NOTE: [AUTO] 밀도 게이지 A(하한 미달)·B(내부 구멍) 계측 진입점 — CoverageRefresher 일1회 계산에서 호출, Redis
// write-through
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155/-156,
// SPEC-COLLECTOR-WARMSTART-REDIS-004
// REQ-WSR4-002
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillDensityMetrics {

    static final String BELOW_FLOOR_NAME = "aaa_collector_backfill_below_floor_stocks";
    static final String INTERNAL_GAP_NAME = "aaa_collector_backfill_internal_gap_stocks";

    private final MeterRegistry registry;
    private final BackfillDensityRepository backfillDensityRepository;

    private final Map<WatermarkSeries, AtomicLong> belowFloorHolders = new ConcurrentHashMap<>();
    private final Map<WatermarkSeries, AtomicLong> internalGapHolders = new ConcurrentHashMap<>();

    /** 부팅 직후 KRX/US 두 시장 라벨을 0으로 pre-register한다(REQ-156, 스크랩 공백 방지). */
    @PostConstruct
    void initGauges() {
        holder(belowFloorHolders, BELOW_FLOOR_NAME, WatermarkSeries.DAILY_OHLCV_KRX);
        holder(belowFloorHolders, BELOW_FLOOR_NAME, WatermarkSeries.DAILY_OHLCV_US);
        holder(internalGapHolders, INTERNAL_GAP_NAME, WatermarkSeries.DAILY_OHLCV_KRX);
        holder(internalGapHolders, INTERNAL_GAP_NAME, WatermarkSeries.DAILY_OHLCV_US);
    }

    /**
     * 게이지 A(하한 미달 종목 수)를 설정한다(REQ-154).
     *
     * <p>게이지 설정과 함께 계산값을 Redis에 best-effort로 영속화한다 — 재시작 시 {@code
     * BackfillDensityMetricsWarmStarter}가 이 값을 복원한다(REQ-WSR4-002).
     *
     * @param series 시장 시리즈(daily-ohlcv-krx/us)
     * @param count 하한 미달 종목 수
     */
    public void setBelowFloorCount(WatermarkSeries series, long count) {
        holder(belowFloorHolders, BELOW_FLOOR_NAME, series).set(count);
        persistBelowFloor(series, count);
    }

    /**
     * 게이지 B(내부 구멍 보유 종목 수)를 설정한다(REQ-155).
     *
     * <p>게이지 설정과 함께 계산값을 Redis에 best-effort로 영속화한다(REQ-WSR4-002).
     *
     * @param series 시장 시리즈(daily-ohlcv-krx/us)
     * @param count 내부 구멍 보유 종목 수
     */
    public void setInternalGapCount(WatermarkSeries series, long count) {
        holder(internalGapHolders, INTERNAL_GAP_NAME, series).set(count);
        persistInternalGap(series, count);
    }

    /**
     * 부팅 시 Redis에 영속된 값으로 게이지 A(하한 미달)를 초기화한다 (REQ-WSR4-002, 웜스타터 전용).
     *
     * <p>{@link #setBelowFloorCount}와 달리 Redis에 다시 쓰지 않는다 — 복원 값 재기록 낭비를 피한다.
     *
     * @param series 시장 시리즈
     * @param count Redis에서 조회한 하한 미달 종목 수
     */
    // @MX:NOTE: [AUTO] setBelowFloorCount(write)의 read-side 미러 — BackfillDensityMetricsWarmStarter가
    // 호출
    // @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-002
    public void warmBelowFloorCount(WatermarkSeries series, long count) {
        holder(belowFloorHolders, BELOW_FLOOR_NAME, series).set(count);
    }

    /**
     * 부팅 시 Redis에 영속된 값으로 게이지 B(내부 구멍)를 초기화한다 (REQ-WSR4-002, 웜스타터 전용).
     *
     * <p>{@link #setInternalGapCount}와 달리 Redis에 다시 쓰지 않는다.
     *
     * @param series 시장 시리즈
     * @param count Redis에서 조회한 내부 구멍 보유 종목 수
     */
    // @MX:NOTE: [AUTO] setInternalGapCount(write)의 read-side 미러 —
    // BackfillDensityMetricsWarmStarter가 호출
    // @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-002
    public void warmInternalGapCount(WatermarkSeries series, long count) {
        holder(internalGapHolders, INTERNAL_GAP_NAME, series).set(count);
    }

    private void persistBelowFloor(WatermarkSeries series, long count) {
        try {
            backfillDensityRepository.saveBelowFloor(series.seriesLabel(), count);
        } catch (DataAccessException e) {
            log.warn(
                    "[density-metrics] below_floor Redis 기록 실패 — series={}, 무시하고 계속 진행. error={}",
                    series.seriesLabel(),
                    e.getMessage());
        }
    }

    private void persistInternalGap(WatermarkSeries series, long count) {
        try {
            backfillDensityRepository.saveInternalGap(series.seriesLabel(), count);
        } catch (DataAccessException e) {
            log.warn(
                    "[density-metrics] internal_gap Redis 기록 실패 — series={}, 무시하고 계속 진행. error={}",
                    series.seriesLabel(),
                    e.getMessage());
        }
    }

    private AtomicLong holder(
            Map<WatermarkSeries, AtomicLong> holders, String name, WatermarkSeries series) {
        return holders.computeIfAbsent(
                series,
                s -> {
                    AtomicLong value = new AtomicLong(0L);
                    registry.gauge(
                            name, Tags.of("series", s.seriesLabel(), "market", s.market()), value);
                    return value;
                });
    }
}
