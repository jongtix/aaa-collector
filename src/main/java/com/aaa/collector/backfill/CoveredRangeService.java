package com.aaa.collector.backfill;

import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정방향 갭 walk 증분 primitive + 라이브 배치 조건부 스탬프 (SPEC-COLLECTOR-BACKFILL-011 결정 1/3/4).
 *
 * <p>이 클래스가 SPEC의 핵심 불변식 — {@code [last_collected_date, covered_until_date]} 구간이 빈틈없이 연속
 * 커버됨(REQ-CVR-003) — 을 실제로 지키는 지점이다. {@link #executeStep(BackfillStatus, CoveredGapFiller,
 * LocalDate)}는 데이터 저장({@link CoveredGapFiller#persistStep(LocalDate)})과 {@code covered_until_date}
 * 전진을 같은 트랜잭션에서 원자적으로 커밋한다 — 저장은 성공했는데 전진이 유실되거나, 반대로 저장 없이 전진만 커밋되는 상태는 발생하지 않는다.
 */
// @MX:ANCHOR: [AUTO] 정방향 갭 walk 증분 커밋 + 조건부 스탬프 — TASK-005~008 소스별 필러가 공통 재사용하는 단일 진입점
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-003/012/020/021/030/031 — 데이터
// 저장·covered_until_date
// 전진 원자성이 이 클래스 한 곳에만 있어야 한다(중복 구현 시 불변식 위반 위험). fan_in>=3 예상(FINRA/USDKRW/STOCK 필러).
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
@Slf4j
@Component
@RequiredArgsConstructor
public class CoveredRangeService {

    private final TransactionTemplate transactionTemplate;
    private final BackfillStatusRepository backfillStatusRepository;
    private final BackfillMetrics backfillMetrics;
    private final MarketOpenGate marketOpenGate;
    private final UsMarketOpenGate usMarketOpenGate;

    /**
     * 정방향 갭 walk 1스텝을 원자적으로 실행한다 (REQ-CVR-012, -030, -031).
     *
     * <p>트랜잭션 경계 안에서 {@code filler.persistStep(cursor)}를 호출해 데이터를 저장하고, 결과의 kept 값으로 {@code
     * covered_until_date} 전진 여부를 게이트한다:
     *
     * <ul>
     *   <li>{@code kept > 0} — 검증 통과 저장 확인됨. {@code covered_until_date}를 {@code
     *       result.filledUntil()}로 전진시키고 같은 트랜잭션에서 커밋한다(REQ-CVR-012/030). {@code
     *       filler.persistStep}이 던진 예외는 이 메서드 밖으로 전파되며, 트랜잭션 전체(데이터 저장 + 전진)가 롤백된다(결정 1 원자성). 추가로
     *       {@code result.oldest() > cursor}(앞단 미도달)이면 {@link
     *       BackfillMetrics#recordCoveredWalkAnomaly(CoveredWalkAnomalyKind)}({@link
     *       CoveredWalkAnomalyKind#FRONT_GAP})로 anomaly 경보 신호를 발생시킨다(REQ-CVR-076, 심층 방어) — 단 전진은
     *       억제하지 않는다(동일 anchor 재호출 라이브락 방지, 앞단 hole을 경보로 관측 가능하게 남긴다). GROUP_A 전용 {@link
     *       BackfillMetrics#recordAnomalyFailed()}와는 카운터가 분리되어 있다(SPEC-COLLECTOR-BACKFILL-011
     *       TASK-014).
     *   <li>{@code raw > 0 && kept == 0} — 원본 응답은 있으나 검증을 전량 통과하지 못한 이상(#77류 침묵 skip). 전진하지 않고
     *       {@link BackfillMetrics#recordCoveredWalkAnomaly(CoveredWalkAnomalyKind)}({@link
     *       CoveredWalkAnomalyKind#ALL_REJECTED})로 anomaly 경보 신호를 발생시킨다(REQ-CVR-031) — 저장 실패가
     *       "커버됨"으로 오판되는 것을 차단한다.
     *   <li>{@code raw == 0 && kept == 0} — 원본 응답조차 없는 정상 빈 응답. REQ-CVR-030 원문("검증 통과 행수가 확인된 경우에만
     *       전진을 수행한다")은 kept 확인을 전진의 필요조건으로 명시하므로, 이 분기도 kept==0인 이상 전진하지 않는다. anomaly도 아니므로 신호를
     *       발생시키지 않는다(원본 응답 자체가 없어 검증 실패로 볼 근거가 없다) — 조용한 no-op.
     * </ul>
     *
     * @param status 처리 대상 {@code backfill_status} 행(전진 시 id로 재조회해 관리 상태로 갱신한다)
     * @param filler 소스별 저장 실행체(TASK-005~008)
     * @param cursor 이번 스텝의 시작 지점({@code covered_until_date} 다음 날짜)
     * @return 이번 스텝의 kept/raw/filledUntil/oldest (호출자가 갭 walk 루프 지속 여부를 판단하는 데 사용)
     */
    public CoveredFillResult executeStep(
            BackfillStatus status, CoveredGapFiller filler, LocalDate cursor) {
        return transactionTemplate.execute(
                tx -> {
                    CoveredFillResult result = filler.persistStep(cursor);
                    if (result.kept() > 0) {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.advanceCoveredUntil(result.filledUntil());
                        if (result.oldest() != null && result.oldest().isAfter(cursor)) {
                            log.warn(
                                    "[covered-range] 앞단 미도달 이상(REQ-CVR-076) — cursor={}, oldest={},"
                                            + " filledUntil={} (전진은 비억제)",
                                    cursor,
                                    result.oldest(),
                                    result.filledUntil());
                            recordCoveredWalkAnomalySafely(CoveredWalkAnomalyKind.FRONT_GAP);
                        }
                    } else if (result.raw() > 0) {
                        log.warn(
                                "[covered-range] 검증 전량 실패 이상 — cursor={}, raw={}, kept=0",
                                cursor,
                                result.raw());
                        recordCoveredWalkAnomalySafely(CoveredWalkAnomalyKind.ALL_REJECTED);
                    }
                    // raw == 0 && kept == 0: 정상 빈 응답 — 전진도 anomaly도 없음(REQ-CVR-030 kept 확인 필요조건)
                    return result;
                });
    }

    /**
     * 라이브 수집 배치 성공 후 조건부 스탬프를 평가한다 (REQ-CVR-020, -021).
     *
     * <p>수집 윈도우 {@code [wStart, today]}가 기존 커버 구간과 이어져 있으면({@code covered_until_date >= wStart -
     * 1}) {@code covered_until_date}를 오늘로 즉시 전진시킨다. 이어지지 않으면(갭 존재) 아무것도 하지 않는다 — 무조건 스탬프하면 갭이 커버 구간
     * 내부로 편입되어 영원히 관측 불가능해지므로(REQ-CVR-021 근거), 갭은 이후 정방향 갭 walk가 {@code covered_until_date}를 통과하며
     * 처리하도록 남긴다.
     *
     * <p>단일 날짜형 라이브 배치는 {@code wStart == today}로 호출한다 — 연속 조건이 {@code covered_until_date >= today -
     * 1}로 자연히 축소되어 별도 분기 없이 처리된다.
     *
     * @param status 처리 대상 {@code backfill_status} 행
     * @param wStart 이번 수집 윈도우의 시작일
     * @param today 이번 수집 윈도우의 종료일(오늘)
     */
    public void advanceIfContinuous(BackfillStatus status, LocalDate wStart, LocalDate today) {
        LocalDate coveredUntil = status.getCoveredUntilDate();
        boolean continuous = coveredUntil != null && !coveredUntil.isBefore(wStart.minusDays(1));
        if (!continuous) {
            log.debug(
                    "[covered-range] 갭 존재 — 스탬프 억제. coveredUntil={}, wStart={}",
                    coveredUntil,
                    wStart);
            return;
        }
        transactionTemplate.executeWithoutResult(
                tx -> {
                    BackfillStatus managed =
                            backfillStatusRepository.findById(status.getId()).orElseThrow();
                    managed.advanceCoveredUntil(today);
                });
    }

    /**
     * 정방향 갭 walk를 오늘에 도달하거나 이번 회차 진행이 멈출 때까지 수행한다 (REQ-CVR-011, -012, -013, -041, -060).
     *
     * <p>추적 대상이 아니면(REQ-CVR-052/060, {@link CoveredTrackingEligibility}) 즉시 반환한다(AC-14) — {@code
     * filler}는 호출되지 않고 {@code covered_until_date}도 갱신되지 않는다.
     *
     * <p>시작 지점은 이 메서드가 매번 fresh 재조회하는 {@code covered_until_date}+1(NULL이면 {@code
     * last_collected_date}+1, REQ-CVR-041)이다 — 호출자가 넘긴 {@code status} 인자의 값을 신뢰하지 않는다. 별도 진행 커서 컬럼을
     * 두지 않고 {@code covered_until_date} 자체를 커서로 사용하므로(REQ-CVR-012), 중단 후 다음 호출은 항상 마지막 커밋 지점부터
     * 재개한다(REQ-CVR-013, 재-walk 낭비 없음).
     *
     * <p>{@link CoveredTrackingEligibility.Mode#SINGLE_DATE}(USDKRW/FINRA Daily)는 하루씩 {@link
     * #executeStep}을 호출하되, 호출 전 기존 시장 캘린더 게이트({@link MarketOpenGate}/{@link UsMarketOpenGate})로
     * 비거래일을 사전 skip한다(신규 캘린더 로직 없음) — skip은 인메모리 커서 전진일 뿐 커밋을 유발하지 않는다. {@link
     * CoveredTrackingEligibility.Mode#RANGE}(STOCK 4종)는 캘린더 게이트를 거치지 않는다 — 기존 윈도우 메커니즘이 비거래일을 내재적으로
     * 처리하므로(REQ-CVR-050), 게이트로 윈도우 시작일을 선판정하면 윈도우 내부의 유효 거래일까지 오판 skip될 수 있다.
     *
     * <p>{@code kept == 0}(정상 빈 응답 또는 anomaly, TASK-003 {@link #executeStep} 게이트)이면 이번 회차 진행을 멈춘다 —
     * 캡이나 프로세스 종료로 이번 회차가 끝나도, 이미 커밋된 만큼 다음 회차가 이어받으므로 라이브락이 없다(REQ-CVR-013, -042).
     *
     * @param status 처리 대상 {@code backfill_status} 행(추적 대상 판별·id 조회에만 사용, 최신 커서는 내부에서 재조회한다)
     * @param filler 소스별 저장 실행체(TASK-005~008)
     * @param today 갭 walk 목표 상한(오늘)
     */
    // @MX:ANCHOR: [AUTO] 정방향 갭 walk 진입점 — 실측 fan_in=3(FinraCdnCoveredGapWalkRunner,
    // MarketIndicatorBackfillOrchestrator, StockCoveredGapWalkRunner)
    // @MX:REASON: 클래스 레벨 태그가 이 메서드의 원자성 근거를 이미 서술함(SPEC-COLLECTOR-BACKFILL-011) — 여기서는
    // 실제 fan_in 호출처 3곳만 정확히 고정한다(executeStep은 이 메서드 내부에서만 호출되어 외부 fan_in 없음).
    public void walkGapForward(BackfillStatus status, CoveredGapFiller filler, LocalDate today) {
        if (!CoveredTrackingEligibility.isTracked(
                status.getTargetType(), status.getTargetCode(), status.getDataTable())) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[covered-range] 추적 대상 아님 — walk skip. targetType={}, targetCode={},"
                                + " dataTable={}",
                        status.getTargetType(),
                        status.getTargetCode(),
                        status.getDataTable());
            }
            return;
        }

        CoveredTrackingEligibility.Mode mode =
                CoveredTrackingEligibility.modeOf(
                        status.getTargetType(), status.getTargetCode(), status.getDataTable());
        BackfillStatus fresh = backfillStatusRepository.findById(status.getId()).orElseThrow();
        LocalDate cursor = startCursor(fresh);

        while (!cursor.isAfter(today)) {
            if (mode == CoveredTrackingEligibility.Mode.SINGLE_DATE
                    && !isOpenDay(status.getTargetType(), cursor)) {
                cursor = cursor.plusDays(1);
                continue;
            }
            CoveredFillResult result = executeStep(fresh, filler, cursor);
            if (result.kept() == 0) {
                break; // 이번 회차 종료 — 다음 회차가 covered_until_date+1부터 재개(REQ-CVR-013, 라이브락 없음)
            }
            cursor = result.filledUntil().plusDays(1);
        }
    }

    /**
     * {@link BackfillMetrics#recordCoveredWalkAnomaly(CoveredWalkAnomalyKind)}를 호출하되, 예외가 트랜잭션 밖으로
     * 전파되지 않도록 방어한다(SPEC-COLLECTOR-BACKFILL-011 TASK-014 — GROUP_A {@code recordAnomalyFailed()}와
     * 분리된 카운터로 전환).
     *
     * <p>이 메서드는 {@code executeStep}의 트랜잭션 람다 내부에서만 호출된다 — 메트릭 카운터 증가는 관측 신호일 뿐이므로, 여기서 예외가 나더라도 이미
     * 적용된 {@code covered_until_date} 전진(또는 데이터 저장)까지 롤백시켜서는 안 된다(REQ-CVR-076 설계 의도: anomaly는 전진을
     * 억제하지 않는다).
     *
     * @param kind anomaly 종류(앞단 미도달 또는 검증 전량 실패)
     */
    @SuppressWarnings(
            "PMD.AvoidCatchingGenericException") // 메트릭 실패가 트랜잭션 롤백을 유발하면 안 됨 — REQ-CVR-076
    private void recordCoveredWalkAnomalySafely(CoveredWalkAnomalyKind kind) {
        try {
            backfillMetrics.recordCoveredWalkAnomaly(kind);
        } catch (RuntimeException e) {
            log.error("[covered-range] anomaly 메트릭 기록 실패(트랜잭션에는 영향 없음)", e);
        }
    }

    /** {@code covered_until_date}+1(NULL이면 {@code last_collected_date}+1) — REQ-CVR-041. */
    private LocalDate startCursor(BackfillStatus fresh) {
        LocalDate coveredUntil = fresh.getCoveredUntilDate();
        return (coveredUntil != null ? coveredUntil : fresh.getLastCollectedDate()).plusDays(1);
    }

    /** 단일 날짜형 대상의 기존 시장 캘린더 게이트를 소스별로 선택한다(신규 캘린더 로직 없음). */
    private boolean isOpenDay(String targetType, LocalDate date) {
        return "OVERSEAS_SHORTSALE".equals(targetType)
                ? usMarketOpenGate.isOpenDay(date)
                : marketOpenGate.isOpenDay(date);
    }
}
