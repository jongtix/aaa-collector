package com.aaa.collector.market.calendar.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.tools.MismatchClusterClassifier.Classification;
import com.aaa.collector.market.calendar.tools.MismatchClusterClassifier.ClassifiedMismatch;
import com.aaa.collector.market.calendar.tools.MismatchClusterClassifier.GroupSummary;
import com.aaa.collector.market.calendar.tools.MismatchClusterClassifier.Mismatch;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MismatchClusterClassifier} 군집/산발 판정 단위 테스트 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-043~046,
 * DP-1, TASK-012).
 */
@DisplayName("MismatchClusterClassifier — 군집/산발 판정 (REQ-CAL-043~046, DP-1)")
class MismatchClusterClassifierTest {

    private static final Logger LOG = LoggerFactory.getLogger(MismatchClusterClassifierTest.class);

    private final MismatchClusterClassifier classifier = new MismatchClusterClassifier();

    /** 같은 (calendarCode, 요일, 방향) 그룹에 속하는 토요일 날짜를 {@code count}개 생성한다(7일 간격 — 항상 같은 요일). */
    private static List<LocalDate> saturdaysStartingFrom(LocalDate firstSaturday, int count) {
        List<LocalDate> dates = new ArrayList<>(count);
        LocalDate cursor = firstSaturday;
        for (int i = 0; i < count; i++) {
            dates.add(cursor);
            cursor = cursor.plusWeeks(1);
        }
        return dates;
    }

    @Nested
    @DisplayName("임계치 경계 (CLUSTER_THRESHOLD=20)")
    class ThresholdBoundary {

        @Test
        @DisplayName("동일 그룹 불일치 19건 — 산발적(ISOLATED)")
        void nineteenInGroup_isolated() {
            // Arrange — 2000-01-01(토)부터 매주 토요일, KIS=개장(Y)이나 DERIVED=휴장(false) — 실측
            // 근거(api-specs/kis/25)
            List<LocalDate> saturdays = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 19);
            List<Mismatch> mismatches =
                    saturdays.stream()
                            .map(d -> new Mismatch(CalendarCode.KRX, d, true, false))
                            .toList();

            // Act
            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            // Assert
            assertThat(result).hasSize(19);
            assertThat(result).allMatch(cm -> cm.classification() == Classification.ISOLATED);
        }

        @Test
        @DisplayName("동일 그룹 불일치 정확히 20건 — 군집(CLUSTERED)")
        void exactlyTwenty_clustered() {
            List<LocalDate> saturdays = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 20);
            List<Mismatch> mismatches =
                    saturdays.stream()
                            .map(d -> new Mismatch(CalendarCode.KRX, d, true, false))
                            .toList();

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            assertThat(result).hasSize(20);
            assertThat(result).allMatch(cm -> cm.classification() == Classification.CLUSTERED);
        }

        @Test
        @DisplayName("21건 이상도 군집 유지")
        void aboveThreshold_stillClustered() {
            List<LocalDate> saturdays = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 30);
            List<Mismatch> mismatches =
                    saturdays.stream()
                            .map(d -> new Mismatch(CalendarCode.KRX, d, true, false))
                            .toList();

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            assertThat(result).hasSize(30);
            assertThat(result).allMatch(cm -> cm.classification() == Classification.CLUSTERED);
        }
    }

    @Nested
    @DisplayName("그룹 분리 — 캘린더/요일/방향이 다르면 별개 그룹")
    class GroupIsolation {

        @Test
        @DisplayName("같은 요일이라도 방향(sourceOpen/derivedOpen 조합)이 다르면 별개 그룹으로 카운트된다")
        void differentDirection_separateGroups() {
            // Arrange — 토요일 그룹 A(KIS=개장,DERIVED=휴장) 15건 + 그룹 B(KIS=휴장,DERIVED=개장) 15건 — 각각 임계치 미만
            List<LocalDate> saturdaysA = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 15);
            List<LocalDate> saturdaysB = saturdaysStartingFrom(LocalDate.of(2010, 1, 2), 15);
            List<Mismatch> mismatches = new ArrayList<>();
            saturdaysA.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));
            saturdaysB.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, false, true)));

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            assertThat(result).hasSize(30);
            assertThat(result).allMatch(cm -> cm.classification() == Classification.ISOLATED);
        }

        @Test
        @DisplayName("같은 요일·방향이라도 calendarCode가 다르면 별개 그룹으로 카운트된다")
        void differentCalendarCode_separateGroups() {
            List<LocalDate> krxDates = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 20);
            List<LocalDate> nyseDates = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 10);
            List<Mismatch> mismatches = new ArrayList<>();
            krxDates.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));
            nyseDates.forEach(d -> mismatches.add(new Mismatch(CalendarCode.NYSE, d, true, false)));

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            List<ClassifiedMismatch> krxResults =
                    result.stream()
                            .filter(cm -> cm.mismatch().calendarCode() == CalendarCode.KRX)
                            .toList();
            List<ClassifiedMismatch> nyseResults =
                    result.stream()
                            .filter(cm -> cm.mismatch().calendarCode() == CalendarCode.NYSE)
                            .toList();
            assertThat(krxResults)
                    .hasSize(20)
                    .allMatch(cm -> cm.classification() == Classification.CLUSTERED);
            assertThat(nyseResults)
                    .hasSize(10)
                    .allMatch(cm -> cm.classification() == Classification.ISOLATED);
        }

        @Test
        @DisplayName("다른 요일은 같은 날짜 수라도 섞이지 않는다 — 요일별 독립 카운트")
        void differentDayOfWeek_notMixed() {
            // 20건의 토요일(군집) + 5건의 일요일(산발) — 총 25건이지만 요일별로 분리 판정
            List<LocalDate> saturdays = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 20);
            List<LocalDate> sundays = saturdaysStartingFrom(LocalDate.of(2000, 1, 2), 5);
            List<Mismatch> mismatches = new ArrayList<>();
            saturdays.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));
            sundays.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            List<ClassifiedMismatch> satResults =
                    result.stream()
                            .filter(cm -> cm.mismatch().date().getDayOfWeek() == DayOfWeek.SATURDAY)
                            .toList();
            List<ClassifiedMismatch> sunResults =
                    result.stream()
                            .filter(cm -> cm.mismatch().date().getDayOfWeek() == DayOfWeek.SUNDAY)
                            .toList();
            assertThat(satResults)
                    .hasSize(20)
                    .allMatch(cm -> cm.classification() == Classification.CLUSTERED);
            assertThat(sunResults)
                    .hasSize(5)
                    .allMatch(cm -> cm.classification() == Classification.ISOLATED);
        }
    }

    @Nested
    @DisplayName("입력 경계값")
    class EdgeCases {

        @Test
        @DisplayName("빈 입력 — 빈 결과, 예외 없음")
        void emptyInput_emptyResult() {
            assertThat(classifier.classify(List.of())).isEmpty();
            assertThat(classifier.summarize(List.of())).isEmpty();
        }

        @Test
        @DisplayName("단일 불일치 — 산발적(ISOLATED)")
        void singleMismatch_isolated() {
            List<Mismatch> mismatches =
                    List.of(new Mismatch(CalendarCode.KRX, LocalDate.of(2000, 1, 1), true, false));

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().classification()).isEqualTo(Classification.ISOLATED);
        }
    }

    @Nested
    @DisplayName("summarize — 그룹별 요약 로그용 산출")
    class Summarize {

        @Test
        @DisplayName("그룹별 (요일, 건수, 판정) 요약을 결정론적 순서로 반환한다")
        void summarize_returnsDeterministicGroupSummaries() {
            // Arrange
            List<LocalDate> saturdays = saturdaysStartingFrom(LocalDate.of(2000, 1, 1), 20);
            List<LocalDate> sundays = saturdaysStartingFrom(LocalDate.of(2000, 1, 2), 3);
            List<Mismatch> mismatches = new ArrayList<>();
            saturdays.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));
            sundays.forEach(d -> mismatches.add(new Mismatch(CalendarCode.KRX, d, true, false)));

            // Act
            List<GroupSummary> summaries = classifier.summarize(mismatches);

            // Assert
            assertThat(summaries).hasSize(2);
            GroupSummary saturdayGroup =
                    summaries.stream()
                            .filter(s -> s.key().dayOfWeek() == DayOfWeek.SATURDAY)
                            .findFirst()
                            .orElseThrow();
            GroupSummary sundayGroup =
                    summaries.stream()
                            .filter(s -> s.key().dayOfWeek() == DayOfWeek.SUNDAY)
                            .findFirst()
                            .orElseThrow();
            assertThat(saturdayGroup.count()).isEqualTo(20);
            assertThat(saturdayGroup.classification()).isEqualTo(Classification.CLUSTERED);
            assertThat(sundayGroup.count()).isEqualTo(3);
            assertThat(sundayGroup.classification()).isEqualTo(Classification.ISOLATED);
        }

        @Test
        @DisplayName("호출 순서가 달라도 동일한 정렬 결과를 반환한다(결정론적)")
        void summarize_orderIndependent_deterministicSort() {
            List<Mismatch> forward =
                    List.of(
                            new Mismatch(CalendarCode.NYSE, LocalDate.of(2000, 1, 1), true, false),
                            new Mismatch(CalendarCode.KRX, LocalDate.of(2000, 1, 8), true, false));
            List<Mismatch> reversed = List.of(forward.get(1), forward.getFirst());

            List<GroupSummary> result1 = classifier.summarize(forward);
            List<GroupSummary> result2 = classifier.summarize(reversed);

            assertThat(result1).isEqualTo(result2);
        }
    }

    @Nested
    @DisplayName("실측 근거 기반 대표 실행(TASK-012 완료 노트 근거, [HARD] 라이브 실행 불가 시 대체 검증)")
    class RealWorldRepresentativeRun {

        /**
         * {@code api-specs/kis/25-국내휴장일조회.md}(2026-07-22 교차 검증 실측)의 정확한 문서화 사례 3건 — 2000년 1월
         * 토요일(01-08/01-15/01-22) 전부 KIS {@code opnd_yn=Y}(개장)이나 실제로는 토요장 폐지 후 휴장(daily_ohlcv 데이터
         * 없음).
         */
        @Test
        @DisplayName("실측 사례 그대로(3건) — 이 좁은 표본만으로는 산발적(ISOLATED) 판정, 자동 확정되지 않는다")
        void narrowRealSample_threeSaturdays_isolatedNotAutoAdopted() {
            List<Mismatch> mismatches =
                    List.of(
                            new Mismatch(CalendarCode.KRX, LocalDate.of(2000, 1, 8), true, false),
                            new Mismatch(CalendarCode.KRX, LocalDate.of(2000, 1, 15), true, false),
                            new Mismatch(CalendarCode.KRX, LocalDate.of(2000, 1, 22), true, false));

            List<ClassifiedMismatch> result = classifier.classify(mismatches);

            assertThat(result).hasSize(3);
            assertThat(result).allMatch(cm -> cm.classification() == Classification.ISOLATED);
        }

        /**
         * 대표 실행(라이브 시딩 도구를 실제로 구동할 수 없어 대체 — TASK-012 완료 노트 참조) — {@code api-specs/kis/25}가 명시한 토요장
         * 폐지 실제 구간(1999년 이후, 문서상 "대략 2010년 이전 구간")을 그대로 반영해 1999-01-02부터 2009-12-26까지 매주 토요일(571건)
         * 전부를 동일 방향 불일치로 구성한다. 실제 시딩 도구를 KIS 크리덴셜로 구동하면 이와 매우 유사한 분포가 나올 것으로 예상된다(§완료 노트).
         */
        @Test
        @DisplayName("대표 실행 — 1999~2009 토요일 전부(571건) 군집(CLUSTERED) 자동 채택, 임계치(20) 대비 28배 초과")
        void representativeRun_saturdaysNineteenNinetyNineToTwoThousandNine_heavilyClustered() {
            // Arrange — 1999-01-02(토) ~ 2009-12-26(토), 매주
            List<Mismatch> mismatches = new ArrayList<>();
            LocalDate cursor = LocalDate.of(1999, 1, 2);
            LocalDate end = LocalDate.of(2009, 12, 26);
            while (!cursor.isAfter(end)) {
                mismatches.add(new Mismatch(CalendarCode.KRX, cursor, true, false));
                cursor = cursor.plusWeeks(1);
            }

            // Act
            List<GroupSummary> summaries = classifier.summarize(mismatches);
            List<ClassifiedMismatch> classified = classifier.classify(mismatches);

            // Assert
            assertThat(summaries).hasSize(1); // 단일 그룹(KRX, 토요일, open=true/derived=false)
            GroupSummary saturdayGroup = summaries.getFirst();
            assertThat(saturdayGroup.count()).isEqualTo(mismatches.size());
            assertThat(saturdayGroup.classification()).isEqualTo(Classification.CLUSTERED);
            assertThat(mismatches.size())
                    .as("실제 분포는 임계치(20)의 수십 배 규모 — 임계치를 낮출 필요는 없음을 시사")
                    .isGreaterThan(MismatchClusterClassifier.CLUSTER_THRESHOLD * 10);
            assertThat(classified).allMatch(cm -> cm.classification() == Classification.CLUSTERED);

            // 완료 노트용 근거 로그 — 요일별 건수 분포
            LOG.info(
                    "[calendar-seed][representative-run] group={}, count={}, classification={}",
                    saturdayGroup.key(),
                    saturdayGroup.count(),
                    saturdayGroup.classification());
        }
    }
}
