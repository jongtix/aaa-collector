package com.aaa.collector.backfill;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 백필 슬라이딩 윈도우 종료 판정기 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>순수 로직 — KIS/Spring 비의존. 한 윈도우의 결과({@link BackfillWindowOutcome})를 받아 그룹별 종료 규칙을 적용해 {@link
 * TerminationDecision}을 반환한다.
 *
 * <ul>
 *   <li><b>그룹 A</b>({@code daily_ohlcv}): 0건(REQ-012) 또는 100건 미만(REQ-013, KIS 단일 호출 상한 미충족 = 상장 초기
 *       도달) → COMPLETED. [CR-02] {@code short_sale_domestic}에는 100건-미만 규칙을 적용하지 않는다.
 *   <li><b>그룹 B</b>(공매도·investor·credit): 0건(REQ-012) 또는 연속 N회 무전진(REQ-014, 기본 N=3) → COMPLETED. 전진
 *       시 무전진 카운터 0 리셋. 종료 직전 연속 윈도우가 동일 oldest + 동일 행수면 클램프 의심 플래그를 세운다(REQ-014a).
 * </ul>
 *
 * <p>임계값 N({@code stale-window-threshold})은 생성자 주입 — T7 {@code BackfillProperties}가 주입하기 전까지 호출자가
 * 직접 전달한다.
 */
public final class BackfillTerminationPolicy {

    /** KIS 단일 호출 응답 상한(거래일). 그룹 A는 이 값 미만이면 상장 초기 도달로 종료한다. */
    private static final int SINGLE_CALL_ROW_CAP = 100;

    /** 그룹 B 연속 무전진 종료 임계(REQ-014). */
    private final int staleWindowThreshold;

    /**
     * @param staleWindowThreshold 그룹 B 연속 무전진 종료 임계(기본 3)
     */
    public BackfillTerminationPolicy(int staleWindowThreshold) {
        this.staleWindowThreshold = staleWindowThreshold;
    }

    /**
     * 한 윈도우 결과로 종료 여부를 판정한다.
     *
     * @param outcome 윈도우 수집 결과
     * @return 종료/진행 결정
     */
    public TerminationDecision decide(BackfillWindowOutcome outcome) {
        if (outcome.group() == BackfillGroup.GROUP_A) {
            return decideGroupA(outcome);
        }
        return decideGroupB(outcome);
    }

    /** 그룹 A: 0건 또는 100건 미만 → 종료. SPAN 보장은 {@link BackfillWindowAdvancer} 책임. */
    private TerminationDecision decideGroupA(BackfillWindowOutcome outcome) {
        if (outcome.rowCount() < SINGLE_CALL_ROW_CAP) {
            return TerminationDecision.completed(0, false);
        }
        return TerminationDecision.inProgress(0);
    }

    /** 그룹 B: 0건 즉시 종료 / 전진 시 리셋 / 연속 N회 무전진 종료(동일 oldest+행수면 클램프 의심). */
    private TerminationDecision decideGroupB(BackfillWindowOutcome outcome) {
        if (outcome.rowCount() == 0) {
            return TerminationDecision.completed(outcome.currentStaleCount(), false);
        }
        if (advanced(outcome)) {
            return TerminationDecision.inProgress(0);
        }
        int nextStaleCount = outcome.currentStaleCount() + 1;
        if (nextStaleCount >= staleWindowThreshold) {
            return TerminationDecision.completed(nextStaleCount, clampSuspected(outcome));
        }
        return TerminationDecision.inProgress(nextStaleCount);
    }

    /** 반환 최소 거래일이 직전 윈도우보다 과거로 전진했는지. 최초 윈도우(이전 없음)는 전진으로 본다. */
    private boolean advanced(BackfillWindowOutcome outcome) {
        LocalDate previousOldest = outcome.previousOldest();
        return previousOldest == null || outcome.oldestTradeDate().isBefore(previousOldest);
    }

    /** 클램프 의심: 종료 윈도우의 oldest·행수가 직전 윈도우와 모두 동일(REQ-014a). */
    private boolean clampSuspected(BackfillWindowOutcome outcome) {
        return Objects.equals(outcome.oldestTradeDate(), outcome.previousOldest())
                && Objects.equals(outcome.rowCount(), outcome.previousRowCount());
    }
}
