package com.aaa.collector.market.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시장 지표 백필 오케스트레이터 (SPEC-COLLECTOR-MARKETIND-001, REQ-040~047).
 *
 * <p>cron 시 {@code target_type='MARKET_INDICATOR'} 미완료 항목을 처리한다. VIX: 전체 이력 단일 호출 후 COMPLETED.
 * USDKRW: staleWeekdayCount 기반 날짜 루프(REQ-044). 지표 단위 예외 격리(REQ-045).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorBackfillOrchestrator {

    private static final String TARGET_TYPE = "MARKET_INDICATOR";
    private static final String DATA_TABLE = "market_indicators";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** USDKRW 백필 기본 종료 임계: 연속 N회 평일 빈 결과 (REQ-044). */
    @Value("${aaa.market-indicator.backfill.usdkrw.stale-weekday-threshold:7}")
    private int staleWeekdayThreshold;

    /** DB updateProgress 중간 배칭 단위 — 매 N일마다 IN_PROGRESS 갱신 (W-3, MA-01). */
    private static final int PROGRESS_BATCH_SIZE = 10;

    private final BackfillStatusRepository backfillStatusRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final VixCollectionService vixCollectionService;
    private final UsdkrwCollectionService usdkrwCollectionService;
    private final TransactionTemplate transactionTemplate;
    private final CoveredRangeService coveredRangeService;

    /**
     * 백필 시딩: USDKRW, VIX 2행 {@code insertIgnoreSeed} (REQ-050~052).
     *
     * <p>기존 행 보존(INSERT IGNORE), 누락 행만 생성한다(REQ-051).
     */
    public void seed() {
        backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, "USDKRW", DATA_TABLE);
        backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, "VIX", DATA_TABLE);
        log.info("[market-ind-backfill] 시딩 완료 — USDKRW, VIX");
    }

    /**
     * 백필 실행: 미완료 항목 처리 (REQ-040~047) + USDKRW 정방향 갭 walk (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011,
     * -070).
     *
     * <p>{@code STOCK} 타입은 미처리(REQ-047). 지표 단위 예외 격리(REQ-045). 갭 walk는 backward walk의
     * PENDING/IN_PROGRESS 필터와 무관하게(REQ-CVR-011 — 커버-추적 대상인 모든 항목) 매 회차 항상 시도한다 — backward walk가
     * COMPLETED에 도달해 {@code targets}에서 빠진 뒤에도 상단 라이브 갭은 이 회차가 계속 책임진다(§1.1 근본원인).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 지표 단위 예외 격리 (REQ-045)
    public void runBackfill() {
        List<BackfillStatus> targets =
                backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                        List.of(BackfillStatusType.PENDING, BackfillStatusType.IN_PROGRESS),
                        TARGET_TYPE);

        if (targets.isEmpty()) {
            log.info("[market-ind-backfill] 처리 대상 없음");
        } else {
            for (BackfillStatus target : targets) {
                try {
                    processTarget(target);
                } catch (Exception e) {
                    log.error(
                            "[market-ind-backfill] 지표 예외 — code={}, 다음 회차 재개",
                            target.getTargetCode(),
                            e);
                    final String errMsg = truncate(e.getMessage(), 512);
                    transactionTemplate.executeWithoutResult(
                            tx -> {
                                BackfillStatus managed =
                                        backfillStatusRepository
                                                .findById(target.getId())
                                                .orElseThrow();
                                managed.fail(BackfillStatusType.IN_PROGRESS, errMsg);
                            });
                }
            }
        }

        runUsdkrwCoveredGapWalk();
    }

    /**
     * USDKRW 기존 행을 재사용해 정방향 갭 walk를 수행한다 (REQ-CVR-070 — 신규 행 생성 금지, VIX는 {@link
     * com.aaa.collector.backfill.CoveredTrackingEligibility}에서 이미 제외되므로 이 메서드는 애초에 VIX를 조회하지 않는다).
     *
     * <p>행이 아직 시딩 전({@code seed()} 미실행)이면 조용히 skip한다 — 다음 회차에 재시도(seed는 매 회차 {@code seed()}가 별도로
     * 보장). 갭 walk 예외는 이 메서드 안에서 격리해(REQ-045 정신 재사용) 백필 다른 처리에 영향을 주지 않는다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 갭 walk 예외 격리 — 백필 사이클 전체를 막지 않음
    private void runUsdkrwCoveredGapWalk() {
        try {
            Optional<BackfillStatus> maybeUsdkrw =
                    backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            TARGET_TYPE, "USDKRW", DATA_TABLE);
            if (maybeUsdkrw.isEmpty()) {
                log.debug("[market-ind-backfill] USDKRW 행 미시딩 — 갭 walk skip");
                return;
            }
            BackfillStatus status = maybeUsdkrw.get();
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);
            coveredRangeService.walkGapForward(status, filler, LocalDate.now(KST));
        } catch (Exception e) {
            log.error("[market-ind-backfill] USDKRW 갭 walk 예외 — 다음 회차 재개", e);
        }
    }

    private void processTarget(BackfillStatus target) {
        String code = target.getTargetCode();
        log.info("[market-ind-backfill] 처리 시작 — code={}, status={}", code, target.getStatus());

        switch (code) {
            case "VIX" -> processVix(target);
            case "USDKRW" -> processUsdkrw(target);
            default -> log.warn("[market-ind-backfill] 알 수 없는 지표 코드 — skip: {}", code);
        }
    }

    /**
     * VIX 백필: 전체 이력 단일 호출 후 COMPLETED (REQ-041~043).
     *
     * <p>CBOE/FRED/Yahoo는 단일 호출 지원 소스이므로 1회 수신으로 완료한다. anchor(last_collected_date)는 DB에 저장된
     * MIN(trade_date) 로 결정 — REQ-042 준수 (W-4, MA-02). 수집 데이터 없으면 today fallback.
     */
    private void processVix(BackfillStatus target) {
        int saved = vixCollectionService.collectHistory();
        LocalDate anchor =
                marketIndicatorRepository
                        .findMinTradeDateByIndicatorCode(IndicatorCode.VIX)
                        .orElseGet(() -> LocalDate.now(KST)); // 수집 데이터 없으면 today fallback
        transactionTemplate.executeWithoutResult(
                tx -> {
                    BackfillStatus managed =
                            backfillStatusRepository.findById(target.getId()).orElseThrow();
                    managed.advance(BackfillStatusType.COMPLETED, anchor, 0, saved);
                });
        log.info("[market-ind-backfill] VIX 백필 완료 — saved={}, anchor={}", saved, anchor);
    }

    /**
     * USDKRW 백필: staleWeekdayCount 기반 날짜 루프 (REQ-044).
     *
     * <p>평일 빈 배열 시 staleWeekdayCount++, 데이터 수신 시 0 리셋. N≥staleWeekdayThreshold → COMPLETED.
     * last_collected_date = 최과거 저장 거래일(anchor). IN_PROGRESS DB 갱신은 PROGRESS_BATCH_SIZE(10)일마다 배칭하여
     * 불필요한 UPDATE를 줄인다(W-3, MA-01). 루프 종료 후 반드시 최종 updateProgress 호출.
     */
    private void processUsdkrw(BackfillStatus target) {
        LocalDate cursor = LocalDate.now(KST);
        LocalDate anchor = null;
        int staleWeekdayCount = 0;
        int totalSaved = 0;
        int daysSinceLastUpdate = 0;

        while (staleWeekdayCount < staleWeekdayThreshold) {
            // 주말 skip
            if (isWeekend(cursor)) {
                cursor = cursor.minusDays(1);
                continue;
            }

            int saved = usdkrwCollectionService.collectDailyForBackfill(cursor);
            if (saved > 0) {
                totalSaved += saved;
                staleWeekdayCount = 0;
                anchor = (anchor == null || cursor.isBefore(anchor)) ? cursor : anchor;
                daysSinceLastUpdate++;
                // 매 PROGRESS_BATCH_SIZE일마다 중간 IN_PROGRESS 저장
                if (daysSinceLastUpdate >= PROGRESS_BATCH_SIZE) {
                    LocalDate snapAnchor = anchor;
                    int snapTotal = totalSaved;
                    transactionTemplate.executeWithoutResult(
                            tx -> {
                                BackfillStatus managed =
                                        backfillStatusRepository
                                                .findById(target.getId())
                                                .orElseThrow();
                                managed.advance(
                                        BackfillStatusType.IN_PROGRESS, snapAnchor, 0, snapTotal);
                            });
                    daysSinceLastUpdate = 0;
                }
                log.debug("[usdkrw-backfill] 수집 완료 — date={}, saved={}", cursor, saved);
            } else {
                staleWeekdayCount++;
                log.debug(
                        "[usdkrw-backfill] 빈 결과 — date={}, stale={}/{}",
                        cursor,
                        staleWeekdayCount,
                        staleWeekdayThreshold);
            }
            cursor = cursor.minusDays(1);
        }

        // 루프 종료 후 반드시 최종 갱신
        LocalDate lastCollected = anchor != null ? anchor : LocalDate.now(KST);
        int finalTotalSaved = totalSaved;
        transactionTemplate.executeWithoutResult(
                tx -> {
                    BackfillStatus managed =
                            backfillStatusRepository.findById(target.getId()).orElseThrow();
                    managed.advance(
                            BackfillStatusType.COMPLETED, lastCollected, 0, finalTotalSaved);
                });
        log.info(
                "[market-ind-backfill] USDKRW 백필 완료 — totalSaved={}, anchor={}",
                totalSaved,
                anchor);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private String truncate(String msg, int max) {
        if (msg == null) {
            return "";
        }
        return msg.length() > max ? msg.substring(0, max) : msg;
    }
}
