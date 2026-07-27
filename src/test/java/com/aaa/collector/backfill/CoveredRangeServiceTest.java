package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.session.MarketSessionGate;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link CoveredRangeService} 증분 primitive·조건부 스탬프·kept 게이트·원자성 통합 테스트 (SPEC-COLLECTOR-BACKFILL-011
 * AC-6~AC-9).
 *
 * <p>실제 {@code TransactionTemplate}(Testcontainers MySQL)으로 검증한다 — Mockito mock으로는 트랜잭션 롤백(결정 1
 * 원자성)을 재현할 수 없어 통합 테스트로 작성했다({@link AtomicRollback}).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("CoveredRangeService — 증분 primitive·조건부 스탬프·kept 게이트 (SPEC-COLLECTOR-BACKFILL-011)")
@Tag("integration")
class CoveredRangeServiceTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    /** 실제 카운터 대신 verify()로 REQ-CVR-031 anomaly 신호 발생을 검증한다. */
    @MockitoBean private BackfillMetrics backfillMetrics;

    /** 국내 캘린더 게이트 — TASK-018 앞단 미도달 판정용 {@code isOpenDayStrict} 결정론화. */
    @MockitoBean private MarketSessionGate marketSessionGate;

    @MockitoBean private UsMarketSessionGate usMarketSessionGate;

    @Autowired private CoveredRangeService coveredRangeService;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    @BeforeEach
    void cleanUp() throws SQLException {
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
    }

    private BackfillStatus seed(String targetCode, LocalDate coveredUntilDate) {
        BackfillStatus saved =
                backfillStatusRepository.saveAndFlush(
                        BackfillStatus.builder()
                                .targetType("STOCK")
                                .targetCode(targetCode)
                                .dataTable("daily_ohlcv")
                                .status(BackfillStatusType.IN_PROGRESS)
                                .build());
        if (coveredUntilDate != null) {
            saved.advanceCoveredUntil(coveredUntilDate);
            saved = backfillStatusRepository.saveAndFlush(saved);
        }
        return saved;
    }

    private BackfillStatus reload(Long id) {
        return backfillStatusRepository.findById(id).orElseThrow();
    }

    @Nested
    @DisplayName("executeStep — kept 기반 전진 (REQ-CVR-012, -030)")
    class ExecuteStepKeptGate {

        @Test
        @DisplayName("AC-8/kept>0 — covered_until_date가 filledUntil로 전진한다")
        void keptPositive_advancesToFilledUntil() {
            // Arrange
            BackfillStatus status = seed("KEPT1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, step); // kept>0, 성공적으로 채움

            // Act
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert
            assertThat(result.kept()).isEqualTo(5);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(cursor);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, never()).recordCoveredWalkAnomaly(any());
        }

        @Test
        @DisplayName("AC-8/raw==0&&kept==0 — 정상 빈 응답, 전진 없음 + anomaly 없음(REQ-CVR-030 kept 확인 필요조건)")
        void emptyResponse_noAdvanceNoAnomaly() {
            // Arrange — 휴장일 등 원본 응답 자체가 없는 정상 케이스
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("EMPTY1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(0, 0, step);

            // Act
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert — REQ-CVR-030 원문("kept가 확인된 경우에만 전진")에 따라 kept==0이면 raw 값과 무관하게 미전진
            assertThat(result.kept()).isZero();
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, never()).recordCoveredWalkAnomaly(any());
        }

        @Test
        @DisplayName("AC-9/raw>0&&kept==0 — 확장 중단 + anomaly 경보 발생, '커버됨' 승격 차단 (REQ-CVR-031)")
        void partialValidationFailure_blocksAdvanceAndRaisesAnomaly() {
            // Arrange — #77류: 원본 응답은 있으나 검증 전량 실패(kept=0)
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("ANOMALY1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(0, 12, step);

            // Act
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert
            assertThat(result.kept()).isZero();
            assertThat(result.raw()).isEqualTo(12);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, times(1))
                    .recordCoveredWalkAnomaly(CoveredWalkAnomalyKind.ALL_REJECTED);
        }

        @Test
        @DisplayName(
                "AC-9/raw>0&&kept==0 — recordCoveredWalkAnomaly() 예외 발생해도 executeStep은 정상 반환한다(메트릭"
                        + " 실패가 트랜잭션을 흔들지 않아야 함)")
        void recordAnomalyFailedThrows_stepStillReturnsNormally() {
            // Arrange
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("ANOMALY2", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(0, 12, step);
            doThrow(new RuntimeException("metrics registry 장애"))
                    .when(backfillMetrics)
                    .recordCoveredWalkAnomaly(any());

            // Act — 메트릭 예외가 executeStep 밖으로 전파되지 않아야 한다
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert — 기존 미전진 동작은 그대로 유지된다
            assertThat(result.kept()).isZero();
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
        }
    }

    @Nested
    @DisplayName("advanceIfContinuous — 라이브 배치 조건부 스탬프 (REQ-CVR-020, -021)")
    class AdvanceIfContinuous {

        @Test
        @DisplayName("AC-6 — 연속(covered_until_date == wStart-1) → today로 전진")
        void continuous_advancesToToday() {
            // Arrange
            LocalDate wStart = LocalDate.of(2026, 7, 10);
            LocalDate today = LocalDate.of(2026, 7, 15);
            BackfillStatus status = seed("CONT1", wStart.minusDays(1));

            // Act
            coveredRangeService.advanceIfContinuous(status, wStart, today);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName(
                "AC-6 — 단일 날짜형([wStart,today]==[today,today]) covered_until_date==today-1 → today로 전진")
        void singleDateType_wStartEqualsToday_advances() {
            // Arrange — 단일 날짜형 라이브는 wStart==today로 호출
            LocalDate today = LocalDate.of(2026, 7, 15);
            BackfillStatus status = seed("SINGLE1", today.minusDays(1));

            // Act
            coveredRangeService.advanceIfContinuous(status, today, today);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("AC-7 — 갭 존재(covered_until_date < wStart-1) → 미전진, 갭이 내부로 편입되지 않음")
        void gapExists_suppressesStamp() {
            // Arrange — 사고 시나리오: 07-04~06 갭 + 07-07 라이브 성공
            LocalDate wStart = LocalDate.of(2026, 7, 7);
            LocalDate today = LocalDate.of(2026, 7, 7);
            LocalDate staleCoveredUntil = LocalDate.of(2026, 7, 3); // wStart-1(07-06)보다 오래됨 → 갭
            BackfillStatus status = seed("GAP1", staleCoveredUntil);

            // Act
            coveredRangeService.advanceIfContinuous(status, wStart, today);

            // Assert — covered_until_date 미변경(갭이 내부로 편입되지 않아 후속 정방향 갭 walk가 여전히 인식 가능)
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(staleCoveredUntil);
        }

        @Test
        @DisplayName("covered_until_date가 NULL(미설정)이면 연속으로 간주하지 않고 미전진")
        void nullCoveredUntilDate_treatedAsGap() {
            BackfillStatus status = seed("NULLCOV", null);
            LocalDate wStart = LocalDate.of(2026, 7, 7);
            LocalDate today = LocalDate.of(2026, 7, 7);

            coveredRangeService.advanceIfContinuous(status, wStart, today);

            assertThat(reload(status.getId()).getCoveredUntilDate()).isNull();
        }
    }

    @Nested
    @DisplayName("executeStep — 앞단 도달 검증 anomaly (REQ-CVR-076, 심층 방어)")
    class FrontReachAnomaly {

        @Test
        @DisplayName(
                "AC-27② — 구간 [cursor,oldest)에 개장일 확인 → front_gap 발화 + covered_until_date 그래도 전진(라이브락"
                        + " 없음, REQ-CVR-081)")
        void oldestAfterCursor_raisesAnomalyButStillAdvances() {
            // Arrange — 스텝 폭 산정이 잘못됐거나 API 반환 특성이 변한 잔여 상황을 stub으로 모사한다
            BackfillStatus status = seed("FRONTGAP1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate filledUntil = LocalDate.of(2026, 7, 5);
            LocalDate oldest = LocalDate.of(2026, 7, 3); // cursor(07-02)보다 늦음 = 앞단 미도달
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);
            when(marketSessionGate.isOpenDayStrict(cursor)).thenReturn(Optional.of(true));

            // Act
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert — 앞단 hole이 anomaly로 관측 가능하게 남되, covered_until_date 전진은 억제되지 않는다(동일 anchor
            // 재호출 라이브락 방지)
            assertThat(result.oldest()).isEqualTo(oldest);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, times(1))
                    .recordCoveredWalkAnomaly(CoveredWalkAnomalyKind.FRONT_GAP);
            verify(backfillMetrics, never()).recordFrontGapSuppressed();
        }

        @Test
        @DisplayName("AC-27③ — oldest <= cursor(정상 도달) → 판정 자체 미수행, 캘린더 게이트 조회 0회")
        void oldestAtOrBeforeCursor_noAnomaly() {
            // Arrange — 정상 케이스(TASK-010 스텝 폭 35일 정정 후 절대 발화하지 않아야 하는 tripwire)
            BackfillStatus status = seed("FRONTOK1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate filledUntil = LocalDate.of(2026, 7, 5);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, cursor);

            // Act
            coveredRangeService.executeStep(status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, never()).recordCoveredWalkAnomaly(any());
            verify(backfillMetrics, never()).recordFrontGapSuppressed();
            verify(marketSessionGate, never()).isOpenDayStrict(any());
        }

        @Test
        @DisplayName("AC-27① — 토요일·일요일만 낀 구간(전 구간 휴장 확인) → 발화 0 + 억제 카운터 1 (REQ-CVR-081/087)")
        void wholeRangeClosed_suppressesWithoutAnomaly() {
            // Arrange — 토요일 커서, oldest=월요일(구간 [토,월) = {토,일})
            BackfillStatus status = seed("SUPPRESS1", LocalDate.of(2026, 7, 1));
            LocalDate saturday = LocalDate.of(2026, 7, 4);
            LocalDate sunday = LocalDate.of(2026, 7, 5);
            LocalDate monday = LocalDate.of(2026, 7, 6);
            LocalDate filledUntil = LocalDate.of(2026, 7, 10);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, monday);
            when(marketSessionGate.isOpenDayStrict(saturday)).thenReturn(Optional.of(false));
            when(marketSessionGate.isOpenDayStrict(sunday)).thenReturn(Optional.of(false));

            // Act
            coveredRangeService.executeStep(
                    status, filler, saturday, CoveredCalendarDomain.DOMESTIC);

            // Assert — 억제(anomaly 아님), covered_until_date는 그래도 전진
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, never()).recordCoveredWalkAnomaly(any());
            verify(backfillMetrics, times(1)).recordFrontGapSuppressed();
            verify(marketSessionGate, never()).isOpenDay(any());
        }

        @Test
        @DisplayName("AC-27④ — 구간 중 1일이라도 '모름' → calendar_unknown 발화(나머지가 전부 휴장이어도, REQ-CVR-085)")
        void unknownDateInRange_raisesCalendarUnknown() {
            // Arrange — 첫날은 휴장 확인, 둘째날은 캘린더에 행 없음("모름")에서 조기 단축
            BackfillStatus status = seed("UNKNOWN1", LocalDate.of(2026, 7, 1));
            LocalDate day1 = LocalDate.of(2026, 7, 2);
            LocalDate day2 = LocalDate.of(2026, 7, 3);
            LocalDate oldest = LocalDate.of(2026, 7, 5); // 구간 [day1, oldest) = {day1, day2, day1+2}
            LocalDate filledUntil = LocalDate.of(2026, 7, 8);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);
            when(marketSessionGate.isOpenDayStrict(day1)).thenReturn(Optional.of(false));
            when(marketSessionGate.isOpenDayStrict(day2)).thenReturn(Optional.empty());

            // Act
            coveredRangeService.executeStep(status, filler, day1, CoveredCalendarDomain.DOMESTIC);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, times(1))
                    .recordCoveredWalkAnomaly(CoveredWalkAnomalyKind.CALENDAR_UNKNOWN);
            verify(backfillMetrics, never()).recordFrontGapSuppressed();
        }

        @Test
        @DisplayName("AC-27④ — '모름' 발견 즉시 조기 단축, 그 이후 날짜는 조회하지 않는다")
        void unknownDateInRange_shortCircuitsScan() {
            // Arrange — 첫날은 휴장 확인, 둘째날은 캘린더에 행 없음("모름")에서 조기 단축
            BackfillStatus status = seed("UNKNOWN2", LocalDate.of(2026, 7, 1));
            LocalDate day1 = LocalDate.of(2026, 7, 2);
            LocalDate day2 = LocalDate.of(2026, 7, 3);
            LocalDate oldest = LocalDate.of(2026, 7, 5); // 구간 [day1, oldest) = {day1, day2, 07-04}
            LocalDate filledUntil = LocalDate.of(2026, 7, 8);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);
            when(marketSessionGate.isOpenDayStrict(day1)).thenReturn(Optional.of(false));
            when(marketSessionGate.isOpenDayStrict(day2)).thenReturn(Optional.empty());

            // Act
            coveredRangeService.executeStep(status, filler, day1, CoveredCalendarDomain.DOMESTIC);

            // Assert — 3번째 날짜(07-04)는 조회하지 않는다(조기 단축)
            verify(marketSessionGate, times(1)).isOpenDayStrict(day1);
            verify(marketSessionGate, times(1)).isOpenDayStrict(day2);
            verify(marketSessionGate, never()).isOpenDayStrict(day1.plusDays(2));
        }

        @Test
        @DisplayName("탐색 상한 상수(130) > 현재 최대 스텝 폭(90) 회귀 — 정상 스텝이 상한에 걸리지 않는다(REQ-CVR-086)")
        void searchLimitConstant_exceedsMaxStepWidth() {
            assertThat(CoveredRangeService.FRONT_GAP_SEARCH_LIMIT_CALENDAR_DAYS).isGreaterThan(90);
        }

        @Test
        @DisplayName("AC-27⑤ — 구간 길이 > 탐색 상한(130 달력일) → 스캔 없이 calendar_unknown 발화 (REQ-CVR-086)")
        void rangeExceedsSearchLimit_raisesWithoutScanning() {
            // Arrange
            BackfillStatus status = seed("MALFORMED1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate oldest =
                    cursor.plusDays(CoveredRangeService.FRONT_GAP_SEARCH_LIMIT_CALENDAR_DAYS + 1);
            LocalDate filledUntil = oldest.plusDays(3);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);

            // Act
            coveredRangeService.executeStep(status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, times(1))
                    .recordCoveredWalkAnomaly(CoveredWalkAnomalyKind.CALENDAR_UNKNOWN);
            verify(backfillMetrics, never()).recordFrontGapSuppressed();
            verify(marketSessionGate, never()).isOpenDayStrict(any());
        }

        @Test
        @DisplayName(
                "recordCoveredWalkAnomaly() 예외 발생해도 covered_until_date 전진은 롤백되지 않는다(REQ-CVR-081 관측 신호는"
                        + " 전진을 억제하지 않는다는 설계 의도가 메트릭 실패로 깨지면 안 됨)")
        void recordAnomalyFailedThrows_advanceStillCommits() {
            // Arrange — 앞단 미도달 anomaly 분기에서 메트릭 카운터 증가가 예외를 던지는 상황을 모사한다
            BackfillStatus status = seed("FRONTGAP2", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate filledUntil = LocalDate.of(2026, 7, 5);
            LocalDate oldest = LocalDate.of(2026, 7, 3); // cursor보다 늦음 = 앞단 미도달
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);
            when(marketSessionGate.isOpenDayStrict(cursor)).thenReturn(Optional.of(true));
            doThrow(new RuntimeException("metrics registry 장애"))
                    .when(backfillMetrics)
                    .recordCoveredWalkAnomaly(any());

            // Act — 메트릭 예외가 executeStep 밖으로 전파되지 않아야 한다
            CoveredFillResult result =
                    coveredRangeService.executeStep(
                            status, filler, cursor, CoveredCalendarDomain.DOMESTIC);

            // Assert — 트랜잭션이 롤백되지 않고 covered_until_date 전진이 그대로 커밋된다
            assertThat(result.filledUntil()).isEqualTo(filledUntil);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
        }
    }

    @Nested
    @DisplayName("executeStep 원자성 — 데이터 저장과 전진이 같은 트랜잭션에서 커밋/롤백된다 (결정 1)")
    class AtomicRollback {

        @Test
        @DisplayName("persistStep 내부 예외 발생 시 covered_until_date·필러가 쓴 마커 데이터 모두 롤백된다")
        void exceptionInPersistStep_rollsBackBothDataAndAdvance() {
            // Arrange — 필러가 같은 트랜잭션 내에서 관리 엔티티에 마커(lastRowCount=999)를 쓴 뒤 예외를 던진다.
            // 이 마커는 "데이터 저장"을 대표하는 관측 가능한 부수효과다 — 정상 커밋 시엔 함께 반영되고,
            // 롤백 시엔 covered_until_date 전진과 함께 사라져야 결정 1(원자성)이 증명된다.
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("ROLLBACK1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller throwingFiller =
                    step -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.advance(
                                managed.getStatus(),
                                managed.getLastCollectedDate(),
                                managed.getStaleCount(),
                                999);
                        throw new IllegalStateException("stub persistStep 실패");
                    };

            // Act & Assert — 예외가 executeStep 밖으로 전파된다
            assertThatThrownBy(
                            () ->
                                    coveredRangeService.executeStep(
                                            status,
                                            throwingFiller,
                                            cursor,
                                            CoveredCalendarDomain.DOMESTIC))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("stub persistStep 실패");

            // Assert — 마커(데이터 저장 대리)와 covered_until_date 전진 둘 다 롤백되어 호출 전 상태 그대로(seed 시점엔
            // lastRowCount 미설정=null이었으므로, 롤백 성공 시 null로 남는다 — 999로 커밋되면 안 된다)
            BackfillStatus afterRollback = reload(status.getId());
            assertThat(afterRollback.getLastRowCount()).isNull();
            assertThat(afterRollback.getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
        }
    }
}
