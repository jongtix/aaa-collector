package com.aaa.collector.market.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximQuotaExhaustedException;
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

    /**
     * USDKRW 백필 회차당 KOREAEXIM 호출 상한 (SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-021, DP-B).
     *
     * <p>기본값 500 = KOREAEXIM 문서상 일일 호출 한도(1,000)의 절반. 실측 최저 소진(111회)이 이 캡보다 먼저 발생할 수 있으나(SPEC
     * §Risks R1), 그 경우 쿼터 예외 백스톱(REQ-023)이 잔여 방어를 흡수하므로 캡은 예산(soft budget), 쿼터 예외는 백스톱(hard stop)으로
     * 이중 방어한다.
     */
    // @MX:NOTE: [AUTO] 기본값 500 근거 — 문서 한도 1,000 절반. 실측 최저 소진 111과의 관계는 SPEC R1(캡 미달 소진 시
    // 쿼터 예외 백스톱이 흡수, 데이터 오염은 어떤 경우에도 발생하지 않음).
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-021
    @Value("${aaa.market-indicator.backfill.usdkrw.max-calls-per-run:500}")
    private int maxCallsPerRunUsdkrw;

    /** DB updateProgress 중간 배칭 단위 — 매 N일마다 IN_PROGRESS 갱신 (W-3, MA-01). */
    private static final int PROGRESS_BATCH_SIZE = 10;

    private final BackfillStatusRepository backfillStatusRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final VixCollectionService vixCollectionService;
    private final UsdkrwCollectionService usdkrwCollectionService;
    private final TransactionTemplate transactionTemplate;
    private final CoveredRangeService coveredRangeService;
    private final KoreaeximExchangeRateClient koreaeximExchangeRateClient;

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
     *
     * <p><b>run-scoped 쿼터 사망 상태(SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-028)</b>: 이번 회차에서
     * USDKRW가 회차 캡(REQ-022) 또는 쿼터 예외(REQ-023)로 종료됐으면, 말미의 정방향 갭 walk({@link
     * #runUsdkrwCoveredGapWalk()})를 그 밤 실행하지 않는다. 갭 walk는 체인 경유(Yahoo 폴백 가능)라 쿼터가 죽은 채로 02:00 KST에
     * 실행되면 미확정 D−1 Yahoo 부분바를 저장할 위험이 있기 때문이다. 이 제어는 오케스트레이터 흐름 수준에서만 이뤄지며 {@link
     * CoveredRangeService#walkGapForward}·{@code UsdkrwCoveredGapFiller} 등 갭 walk 내부 코드는
     * 무수정이다(REQ-MARKETIND4-031, D6 불변식).
     */
    // @MX:NOTE: [AUTO] run-scoped 쿼터 사망 상태가 갭 walk 호출 여부만 제어 —
    // walkGapForward/UsdkrwCoveredGapFiller
    // 내부 로직은 이 회차 제어와 무관하며 절대 수정하지 않는다(diff 0 불변식).
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-028, REQ-MARKETIND4-031
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 지표 단위 예외 격리 (REQ-045)
    public void runBackfill() {
        List<BackfillStatus> targets =
                backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                        List.of(BackfillStatusType.PENDING, BackfillStatusType.IN_PROGRESS),
                        TARGET_TYPE);

        boolean usdkrwQuotaDeadThisRun = false;

        if (targets.isEmpty()) {
            log.info("[market-ind-backfill] 처리 대상 없음");
        } else {
            for (BackfillStatus target : targets) {
                try {
                    if (processTarget(target)) {
                        usdkrwQuotaDeadThisRun = true;
                    }
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

        if (usdkrwQuotaDeadThisRun) {
            log.info("[market-ind-backfill] USDKRW 쿼터 사망(캡/쿼터 예외) — 이번 회차 갭 walk skip (REQ-028)");
        } else {
            runUsdkrwCoveredGapWalk();
        }
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

    /**
     * @return USDKRW 처리가 이번 회차 회차 캡(REQ-022) 또는 쿼터 예외(REQ-023)로 종료됐으면 {@code true}(run-scoped 쿼터 사망
     *     — REQ-028 갭 walk skip 신호). VIX·미지 코드는 항상 {@code false}.
     */
    private boolean processTarget(BackfillStatus target) {
        String code = target.getTargetCode();
        log.info("[market-ind-backfill] 처리 시작 — code={}, status={}", code, target.getStatus());

        return switch (code) {
            case "VIX" -> {
                processVix(target);
                yield false;
            }
            case "USDKRW" -> processUsdkrw(target);
            default -> {
                log.warn("[market-ind-backfill] 알 수 없는 지표 코드 — skip: {}", code);
                yield false;
            }
        };
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
     * USDKRW 백필: 앵커 재개 + 회차 캡 + 쿼터 예외 백스톱 (SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-020~027).
     *
     * <p>커서는 {@code IN_PROGRESS}이고 앵커(last_collected_date)가 있으면 앵커−1일부터, 그 외(PENDING 또는 앵커 미확정)엔
     * 오늘부터 시작한다(REQ-025, REQ-026). 각 날짜는 KOREAEXIM 백필 전용 메서드({@link
     * KoreaeximExchangeRateClient#fetchDailyForBackfill})로 직접 조회한다 — 체인·Yahoo를 경유하지 않는다(REQ-020).
     * 회차 캡(REQ-021)에 도달하거나 쿼터 예외(REQ-023)가 발생하면 그 시점 진행점을 즉시 {@code IN_PROGRESS}로 저장하고 backward
     * walk를 중단한다. 정상 빈 결과({@code []})는 stale 카운트로만 처리하고 예외로 취급하지 않는다(REQ-024). stale이 임계에 도달하면
     * {@code COMPLETED}로 종료한다(REQ-027).
     *
     * @return 이번 회차가 캡 또는 쿼터 예외로 종료됐으면 {@code true}(run-scoped 쿼터 사망 — REQ-028 갭 walk skip 신호),
     *     stale 임계 도달로 정상 종료됐으면 {@code false}
     */
    // @MX:WARN: [AUTO] 앵커 재개/회차 캡/쿼터 예외 3분기가 서로 다른 시점에 진행점을 저장해야 하는 복잡도 — market_indicators는
    // Tier-1(INSERT IGNORE 전용, ADR-026)이라 실패 시 폴백 저장이 불가능하므로, 분기 병합보다 각 종료 경로의 명시적
    // IN_PROGRESS 저장이 안전을 우선한다. 하루치 처리는 {@link #processUsdkrwDay}로 위임해 이 메서드는 루프 제어(주말
    // skip·stale 임계·최종 COMPLETED 전환)에만 집중한다.
    // @MX:REASON: [AUTO] 캡 도달(REQ-022)·쿼터 예외(REQ-023)·stale 임계(REQ-027) 3가지 종료 조건이 서로 다른 진행점
    // 저장·갭 walk skip 신호(REQ-028)를 요구 — 병합 시 그 밤 갭 walk가 미확정 Yahoo 부분바를 저장할 회귀 위험 > 복잡도 비용.
    // @MX:SPEC: SPEC-COLLECTOR-MARKETIND-004 REQ-MARKETIND4-020~028
    private boolean processUsdkrw(BackfillStatus target) {
        LocalDate cursor = determineUsdkrwStartCursor(target);
        UsdkrwBackfillState state = new UsdkrwBackfillState(target.getLastCollectedDate());

        while (state.staleWeekdayCount < staleWeekdayThreshold) {
            if (!isWeekend(cursor) && processUsdkrwDay(target, cursor, state)) {
                // 회차 캡(REQ-022) 또는 쿼터 예외(REQ-023) — 진행점은 processUsdkrwDay 안에서 이미 저장됨
                return true;
            }
            cursor = cursor.minusDays(1);
        }

        // stale 임계 도달 — 정상 종료
        LocalDate lastCollected = state.anchor != null ? state.anchor : LocalDate.now(KST);
        updateUsdkrwProgress(target, BackfillStatusType.COMPLETED, lastCollected, state.totalSaved);
        log.info(
                "[market-ind-backfill] USDKRW 백필 완료 — totalSaved={}, anchor={}",
                state.totalSaved,
                state.anchor);
        return false;
    }

    /**
     * USDKRW backward walk 하루치를 처리한다 — KOREAEXIM 백필 전용 메서드 조회(REQ-020) → 저장/stale 카운트(REQ-024) → 배치
     * 중간 저장(W-3)/회차 캡 확인(REQ-021, REQ-022) 순으로 진행한다.
     *
     * @return 쿼터 예외(REQ-023) 또는 회차 캡(REQ-022)으로 이번 회차 backward walk를 즉시 종료해야 하면 {@code true}(진행점은 이
     *     메서드 안에서 이미 IN_PROGRESS로 저장됨)
     */
    private boolean processUsdkrwDay(
            BackfillStatus target, LocalDate cursor, UsdkrwBackfillState state) {
        List<MarketIndicatorRow> rows;
        try {
            rows = koreaeximExchangeRateClient.fetchDailyForBackfill(cursor);
        } catch (KoreaeximQuotaExhaustedException e) {
            log.warn(
                    "[usdkrw-backfill] 쿼터 예외 — 진행점 IN_PROGRESS 저장 후 backward walk 중단: date={}",
                    cursor,
                    e);
            updateUsdkrwProgress(
                    target, BackfillStatusType.IN_PROGRESS, state.anchor, state.totalSaved);
            return true;
        }
        state.callsThisRun++;

        if (rows.isEmpty()) {
            state.recordStale();
            log.debug(
                    "[usdkrw-backfill] 빈 결과 — date={}, stale={}/{}",
                    cursor,
                    state.staleWeekdayCount,
                    staleWeekdayThreshold);
        } else {
            int saved = usdkrwCollectionService.saveBackfillRows(rows);
            state.recordSaved(cursor, saved);
            // 매 PROGRESS_BATCH_SIZE일마다 중간 IN_PROGRESS 저장
            if (state.daysSinceLastUpdate >= PROGRESS_BATCH_SIZE) {
                updateUsdkrwProgress(
                        target, BackfillStatusType.IN_PROGRESS, state.anchor, state.totalSaved);
                state.daysSinceLastUpdate = 0;
            }
            log.debug("[usdkrw-backfill] 수집 완료 — date={}, saved={}", cursor, saved);
        }

        if (state.callsThisRun >= maxCallsPerRunUsdkrw) {
            log.warn(
                    "[usdkrw-backfill] 회차 캡({}) 도달 — 진행점 IN_PROGRESS 저장 후 종료",
                    maxCallsPerRunUsdkrw);
            updateUsdkrwProgress(
                    target, BackfillStatusType.IN_PROGRESS, state.anchor, state.totalSaved);
            return true;
        }
        return false;
    }

    /** USDKRW backward walk 진행 상태(회차 범위 지역 상태) — processUsdkrw/processUsdkrwDay 간 공유. */
    private static final class UsdkrwBackfillState {
        private LocalDate anchor;
        private int staleWeekdayCount;
        private int totalSaved;
        private int daysSinceLastUpdate;
        private int callsThisRun;

        private UsdkrwBackfillState(LocalDate anchor) {
            this.anchor = anchor;
        }

        private void recordStale() {
            staleWeekdayCount++;
        }

        private void recordSaved(LocalDate cursor, int saved) {
            totalSaved += saved;
            staleWeekdayCount = 0;
            anchor = (anchor == null || cursor.isBefore(anchor)) ? cursor : anchor;
            daysSinceLastUpdate++;
        }
    }

    /**
     * 다음 회차 backward walk 커서 시작점을 결정한다 (REQ-MARKETIND4-025, REQ-MARKETIND4-026).
     *
     * <p>{@code IN_PROGRESS}이고 앵커(last_collected_date)가 확정돼 있으면 앵커−1일부터 재개한다(오늘부터 재주행 금지). 그
     * 외(PENDING이거나, IN_PROGRESS이지만 앵커가 아직 NULL — 첫 저장 전 중단으로 앵커 미확정)엔 오늘(KST)부터 시작하는 안전 폴백을 적용한다.
     */
    private LocalDate determineUsdkrwStartCursor(BackfillStatus target) {
        if (target.getStatus() == BackfillStatusType.IN_PROGRESS
                && target.getLastCollectedDate() != null) {
            return target.getLastCollectedDate().minusDays(1);
        }
        return LocalDate.now(KST);
    }

    private void updateUsdkrwProgress(
            BackfillStatus target, BackfillStatusType status, LocalDate anchor, int totalSaved) {
        transactionTemplate.executeWithoutResult(
                tx -> {
                    BackfillStatus managed =
                            backfillStatusRepository.findById(target.getId()).orElseThrow();
                    managed.advance(status, anchor, 0, totalSaved);
                });
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
