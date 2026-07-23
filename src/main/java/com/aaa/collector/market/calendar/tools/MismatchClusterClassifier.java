package com.aaa.collector.market.calendar.tools;

import com.aaa.collector.market.calendar.CalendarCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KIS/알고리즘 값과 {@code DERIVED}({@code daily_ohlcv} 존재 여부) 값 사이의 불일치를 군집/산발로 판정한다
 * (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-043~046, DP-1, plan.md §4.5).
 *
 * <p>판정 단위는 {@code (calendarCode, cal_date.getDayOfWeek(), 불일치 방향)}이다 — 특정 연도를 하드코딩하지 않는다(§5
 * Exclusions). 동일 그룹 내 불일치가 {@value #CLUSTER_THRESHOLD}건 이상이면 정책성 오류로 신뢰할 수 있는 군집으로, 미만이면 개별 데이터
 * 결함일 가능성이 높은 산발적 불일치로 분류한다.
 *
 * <p>순수 로직 컴포넌트 — I/O 없음, 상태 없음.
 */
public final class MismatchClusterClassifier {

    /** 군집 판정 임계치(잠정값, plan.md §9 — 첫 시딩 실행 후 재검토 대상). */
    public static final int CLUSTER_THRESHOLD = 20;

    /** 불일치 분류 결과. */
    public enum Classification {
        CLUSTERED,
        ISOLATED
    }

    /**
     * 단일 불일치 레코드.
     *
     * @param calendarCode 캘린더 도메인
     * @param date 대상 날짜
     * @param sourceOpen 원본 소스(KIS_API 또는 ALGORITHM) 값
     * @param derivedOpen {@code DERIVED}(daily_ohlcv 존재 여부) 값
     */
    public record Mismatch(
            CalendarCode calendarCode, LocalDate date, boolean sourceOpen, boolean derivedOpen) {}

    /** 판정 그룹 키 — (캘린더, 요일, 불일치 방향). */
    public record GroupKey(
            CalendarCode calendarCode,
            DayOfWeek dayOfWeek,
            boolean sourceOpen,
            boolean derivedOpen) {}

    /** 개별 불일치 + 그 불일치가 속한 그룹의 분류 결과. */
    public record ClassifiedMismatch(Mismatch mismatch, Classification classification) {}

    /** 그룹별 요약(요일·방향·건수·분류) — 시딩 도구 실행 로그 출력용(plan.md §4.5). */
    public record GroupSummary(GroupKey key, int count, Classification classification) {}

    /**
     * 각 불일치를 자신이 속한 그룹의 분류 결과와 함께 반환한다.
     *
     * @param mismatches 불일치 목록(순서 보존)
     * @return 입력과 동일한 순서의 분류 결과 목록
     */
    public List<ClassifiedMismatch> classify(List<Mismatch> mismatches) {
        Map<GroupKey, Integer> counts = countByGroup(mismatches);
        List<ClassifiedMismatch> result = new ArrayList<>(mismatches.size());
        for (Mismatch mismatch : mismatches) {
            Classification classification = classificationOf(counts.get(keyOf(mismatch)));
            result.add(new ClassifiedMismatch(mismatch, classification));
        }
        return result;
    }

    /**
     * 그룹별 요약을 (calendarCode, dayOfWeek, sourceOpen, derivedOpen) 순으로 정렬해 반환한다 — 운영자가 사후 검토할 로그
     * 출력용(plan.md §4.5 "그룹별 (요일, 건수, 판정) 요약").
     *
     * @param mismatches 불일치 목록
     * @return 그룹별 요약 목록(결정론적 정렬)
     */
    public List<GroupSummary> summarize(List<Mismatch> mismatches) {
        Map<GroupKey, Integer> counts = countByGroup(mismatches);
        List<GroupSummary> summaries = new ArrayList<>(counts.size());
        for (Map.Entry<GroupKey, Integer> entry : counts.entrySet()) {
            summaries.add(
                    new GroupSummary(
                            entry.getKey(), entry.getValue(), classificationOf(entry.getValue())));
        }
        summaries.sort(
                Comparator.<GroupSummary, String>comparing(s -> s.key().calendarCode().name())
                        .thenComparing(s -> s.key().dayOfWeek())
                        .thenComparing(s -> s.key().sourceOpen())
                        .thenComparing(s -> s.key().derivedOpen()));
        return summaries;
    }

    private static Classification classificationOf(Integer groupCount) {
        int count = groupCount == null ? 0 : groupCount;
        return count >= CLUSTER_THRESHOLD ? Classification.CLUSTERED : Classification.ISOLATED;
    }

    private static Map<GroupKey, Integer> countByGroup(List<Mismatch> mismatches) {
        Map<GroupKey, Integer> counts = new HashMap<>();
        for (Mismatch mismatch : mismatches) {
            counts.merge(keyOf(mismatch), 1, Integer::sum);
        }
        return counts;
    }

    private static GroupKey keyOf(Mismatch mismatch) {
        return new GroupKey(
                mismatch.calendarCode(),
                mismatch.date().getDayOfWeek(),
                mismatch.sourceOpen(),
                mismatch.derivedOpen());
    }
}
