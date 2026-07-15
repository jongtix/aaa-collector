package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.session.MarketSessionGate;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * {@link CoveredRangeService#walkGapForward} 정방향 갭 walk 통합 테스트 (SPEC-COLLECTOR-BACKFILL-011 AC-3,
 * AC-4, AC-14).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("CoveredRangeService.walkGapForward — 정방향 갭 walk (SPEC-COLLECTOR-BACKFILL-011)")
@Tag("integration")
class CoveredRangeServiceGapWalkTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private BackfillMetrics backfillMetrics;

    /** 국내(USDKRW) 캘린더 게이트 — 신규 로직 없이 기존 게이트를 mock으로 결정론화한다. */
    @MockitoBean private MarketSessionGate marketSessionGate;

    /** 미국(FINRA) 캘린더 게이트. */
    @MockitoBean private UsMarketSessionGate usMarketSessionGate;

    @Autowired private CoveredRangeService coveredRangeService;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    @BeforeEach
    void cleanUp() throws SQLException {
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
        // 기본값: 모든 날짜 개장일(테스트별로 필요 시 재정의) — 비거래일 skip 시나리오 전용 테스트에서만 false 혼입
        when(marketSessionGate.isOpenDay(any())).thenReturn(true);
        when(usMarketSessionGate.isOpenDay(any())).thenReturn(true);
    }

    private BackfillStatus seedUsdkrw(LocalDate lastCollectedDate, LocalDate coveredUntilDate) {
        BackfillStatus saved =
                backfillStatusRepository.saveAndFlush(
                        BackfillStatus.builder()
                                .targetType("MARKET_INDICATOR")
                                .targetCode("USDKRW")
                                .dataTable("market_indicators")
                                .status(BackfillStatusType.IN_PROGRESS)
                                .lastCollectedDate(lastCollectedDate)
                                .build());
        if (coveredUntilDate != null) {
            saved.advanceCoveredUntil(coveredUntilDate);
            saved = backfillStatusRepository.saveAndFlush(saved);
        }
        return saved;
    }

    private BackfillStatus seedStockDailyOhlcv(
            LocalDate lastCollectedDate, LocalDate coveredUntilDate) {
        BackfillStatus saved =
                backfillStatusRepository.saveAndFlush(
                        BackfillStatus.builder()
                                .targetType("STOCK")
                                .targetCode("AAPL")
                                .dataTable("daily_ohlcv")
                                .status(BackfillStatusType.IN_PROGRESS)
                                .lastCollectedDate(lastCollectedDate)
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

    /** 단일 날짜형 필러 — 호출될 때마다 현재 DB의 covered_until_date를 관측·기록한 뒤 1일치 성공을 반환한다. */
    private final class RecordingSingleDateFiller implements CoveredGapFiller {
        private final Long statusId;
        private final List<LocalDate> cursorsCalled = new ArrayList<>();
        private final List<LocalDate> observedCoveredUntilBeforeStep = new ArrayList<>();

        RecordingSingleDateFiller(Long statusId) {
            this.statusId = statusId;
        }

        @Override
        public CoveredFillResult persistStep(LocalDate cursor) {
            cursorsCalled.add(cursor);
            observedCoveredUntilBeforeStep.add(
                    backfillStatusRepository
                            .findById(statusId)
                            .orElseThrow()
                            .getCoveredUntilDate());
            return new CoveredFillResult(1, 1, cursor);
        }
    }

    /** 회차당 캡을 시뮬레이션하는 단일 날짜형 필러 — maxCalls 초과 시 정상 빈 응답(캡 도달·프로세스 종료 대리)으로 전환한다. */
    private static final class CappedSingleDateFiller implements CoveredGapFiller {
        private final int maxCalls;
        private int calls;

        CappedSingleDateFiller(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        @Override
        public CoveredFillResult persistStep(LocalDate cursor) {
            calls++;
            if (calls > maxCalls) {
                return new CoveredFillResult(0, 0, cursor);
            }
            return new CoveredFillResult(1, 1, cursor);
        }
    }

    /** 범위형(윈도우) 필러 — 호출마다 windowSpanDays만큼(오늘 상한) 한 번에 채운다. */
    private static final class RangeWindowFiller implements CoveredGapFiller {
        private final int windowSpanDays;
        private final LocalDate today;
        private final List<LocalDate> cursorsCalled = new ArrayList<>();

        RangeWindowFiller(int windowSpanDays, LocalDate today) {
            this.windowSpanDays = windowSpanDays;
            this.today = today;
        }

        @Override
        public CoveredFillResult persistStep(LocalDate cursor) {
            cursorsCalled.add(cursor);
            LocalDate filledUntil = cursor.plusDays(windowSpanDays - 1);
            if (filledUntil.isAfter(today)) {
                filledUntil = today;
            }
            int span = (int) ChronoUnit.DAYS.between(cursor, filledUntil) + 1;
            return new CoveredFillResult(span, span, filledUntil);
        }
    }

    @Nested
    @DisplayName("AC-3 — 정방향 갭 walk + 매 인접 스텝 증분 커밋 (REQ-CVR-011, -012)")
    class ForwardWalkIncrementalCommit {

        @Test
        @DisplayName("① 시작점 == covered_until_date+1(오늘부터 아님), ③ 완료 후 covered_until_date == today")
        void startsFromCoveredUntilPlusOne_notToday() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 7, 1);
            LocalDate today = LocalDate.of(2026, 7, 5);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);
            RecordingSingleDateFiller filler = new RecordingSingleDateFiller(status.getId());

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert
            assertThat(filler.cursorsCalled.getFirst()).isEqualTo(coveredUntil.plusDays(1));
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("② 각 인접 지점 커밋마다 covered_until_date가 단조 전진(병합 시점 단일 커밋이 아님)")
        void eachStepCommitsIncrementally_notMergedAtEnd() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 7, 1);
            LocalDate today = LocalDate.of(2026, 7, 6);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);
            RecordingSingleDateFiller filler = new RecordingSingleDateFiller(status.getId());

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert — 각 스텝 시작 시점에 관측된 covered_until_date가 이전 스텝의 커밋을 이미 반영해 단조 증가한다
            List<LocalDate> observed = filler.observedCoveredUntilBeforeStep;
            assertThat(observed).hasSize(5); // 07-02~07-06
            for (int i = 1; i < observed.size(); i++) {
                assertThat(observed.get(i)).isAfter(observed.get(i - 1));
            }
            assertThat(observed.getFirst()).isEqualTo(coveredUntil); // 첫 스텝은 아직 미전진 상태를 관측
        }

        @Test
        @DisplayName("⑤ 범위형 다중 윈도우 — 과거→최신 순서 진행, 윈도우마다 커밋")
        void rangeTypeMultiWindow_progressesOldestToNewestWithPerWindowCommit() {
            // Arrange — 40일 갭, 윈도우 캡 15일 → 3개 윈도우로 분할되어야 함
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = coveredUntil.plusDays(40);
            BackfillStatus status = seedStockDailyOhlcv(LocalDate.of(2020, 1, 1), coveredUntil);
            RangeWindowFiller filler = new RangeWindowFiller(15, today);

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert
            assertThat(filler.cursorsCalled).hasSizeGreaterThan(1);
            for (int i = 1; i < filler.cursorsCalled.size(); i++) {
                assertThat(filler.cursorsCalled.get(i)).isAfter(filler.cursorsCalled.get(i - 1));
            }
            assertThat(filler.cursorsCalled.getFirst()).isEqualTo(coveredUntil.plusDays(1));
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("비거래일(주말) skip — 기존 시장 캘린더 게이트 재사용, 필러가 호출되지 않는다")
        void skipsNonTradingDays_viaExistingCalendarGate() {
            // Arrange — 토/일요일을 게이트가 휴장일로 판정
            LocalDate coveredUntil = LocalDate.of(2026, 7, 2); // 목요일
            LocalDate today = LocalDate.of(2026, 7, 8); // 다음 주 수요일 (금·토·일 포함)
            when(marketSessionGate.isOpenDay(any()))
                    .thenAnswer(
                            invocation -> {
                                LocalDate date = invocation.getArgument(0);
                                DayOfWeek dow = date.getDayOfWeek();
                                return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
                            });
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);
            RecordingSingleDateFiller filler = new RecordingSingleDateFiller(status.getId());

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert — 주말(07-04 토, 07-05 일)은 필러 호출 대상에서 제외되지만 covered_until_date는 today까지 도달
            assertThat(filler.cursorsCalled)
                    .noneMatch(
                            d ->
                                    d.getDayOfWeek() == DayOfWeek.SATURDAY
                                            || d.getDayOfWeek() == DayOfWeek.SUNDAY);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }
    }

    @Nested
    @DisplayName("AC-4 — 중단 후 재개 = 진행분 보존, 라이브락 부재 (REQ-CVR-013)")
    class ResumeAfterInterruption {

        @Test
        @DisplayName("30일 갭 + 캡 10일 회차를 3번 실행 → 매 회차 엄격히 전진, 최종 today 도달(라이브락 없음)")
        void deepGapAcrossCappedRuns_advancesStrictlyEachRunAndReachesToday() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = coveredUntil.plusDays(30); // 30일 갭
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);

            // Act — Run 1: 캡 10
            coveredRangeService.walkGapForward(status, new CappedSingleDateFiller(10), today);
            LocalDate afterRun1 = reload(status.getId()).getCoveredUntilDate();

            // Act — Run 2: 캡 10 (재조회한 status로 재개)
            coveredRangeService.walkGapForward(
                    reload(status.getId()), new CappedSingleDateFiller(10), today);
            LocalDate afterRun2 = reload(status.getId()).getCoveredUntilDate();

            // Act — Run 3: 캡 20(잔여 전부)
            coveredRangeService.walkGapForward(
                    reload(status.getId()), new CappedSingleDateFiller(20), today);
            LocalDate afterRun3 = reload(status.getId()).getCoveredUntilDate();

            // Assert — 회차마다 엄격히 전진(라이브락 부재), 최종 today 도달
            assertThat(afterRun1).isEqualTo(coveredUntil.plusDays(10));
            assertThat(afterRun2).isAfter(afterRun1).isEqualTo(coveredUntil.plusDays(20));
            assertThat(afterRun3).isAfter(afterRun2).isEqualTo(today);
        }

        @Test
        @DisplayName("① 재개 시작점 == 중단 시점 covered_until_date+1, ② 이미 커버된 날짜는 재호출되지 않는다")
        void resumesFromInterruptionPoint_neverRefetchesCoveredDays() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = coveredUntil.plusDays(20);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);

            // Act — Run 1: 캡 7 → covered_until_date == coveredUntil+7
            coveredRangeService.walkGapForward(status, new CappedSingleDateFiller(7), today);
            LocalDate resumePoint = reload(status.getId()).getCoveredUntilDate();
            assertThat(resumePoint).isEqualTo(coveredUntil.plusDays(7));

            // Act — Run 2: 재조회 상태로 재개, 호출된 커서 기록
            RecordingSingleDateFiller run2Filler = new RecordingSingleDateFiller(status.getId());
            coveredRangeService.walkGapForward(reload(status.getId()), run2Filler, today);

            // Assert
            assertThat(run2Filler.cursorsCalled.getFirst()).isEqualTo(resumePoint.plusDays(1));
            assertThat(run2Filler.cursorsCalled).noneMatch(d -> !d.isAfter(resumePoint));
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("④ 최종 구간 [last_collected_date, covered_until_date] 정합")
        void finalRangeIsConsistent() {
            LocalDate lastCollected = LocalDate.of(2020, 1, 1);
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = coveredUntil.plusDays(5);
            BackfillStatus status = seedUsdkrw(lastCollected, coveredUntil);

            coveredRangeService.walkGapForward(status, new CappedSingleDateFiller(5), today);

            BackfillStatus result = reload(status.getId());
            assertThat(result.getLastCollectedDate()).isEqualTo(lastCollected);
            assertThat(result.getCoveredUntilDate()).isEqualTo(today);
            assertThat(result.getLastCollectedDate())
                    .isBeforeOrEqualTo(result.getCoveredUntilDate());
        }
    }

    @Nested
    @DisplayName("AC-14 — 이벤트형·단일 호출형 제외 (REQ-CVR-052, -060)")
    class ExcludedTargets {

        @Test
        @DisplayName(
                "이벤트형(STOCK/corporate_events) — walkGapForward 호출돼도 필러 미호출·covered_until_date 미갱신")
        void eventTypeTarget_notWalked() {
            // Arrange
            BackfillStatus status =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("STOCK")
                                    .targetCode("AAPL")
                                    .dataTable("corporate_events")
                                    .status(BackfillStatusType.IN_PROGRESS)
                                    .build());
            RecordingSingleDateFiller filler = new RecordingSingleDateFiller(status.getId());

            // Act
            coveredRangeService.walkGapForward(status, filler, LocalDate.of(2026, 7, 15));

            // Assert
            assertThat(filler.cursorsCalled).isEmpty();
            assertThat(reload(status.getId()).getCoveredUntilDate()).isNull();
        }

        @Test
        @DisplayName("단일 호출형(MACRO_INDICATOR) — walkGapForward 호출돼도 필러 미호출")
        void macroIndicatorTarget_notWalked() {
            BackfillStatus status =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("MACRO_INDICATOR")
                                    .targetCode("FRED_VIXCLS")
                                    .dataTable("macro_indicators")
                                    .status(BackfillStatusType.COMPLETED)
                                    .build());
            RecordingSingleDateFiller filler = new RecordingSingleDateFiller(status.getId());

            coveredRangeService.walkGapForward(status, filler, LocalDate.of(2026, 7, 15));

            assertThat(filler.cursorsCalled).isEmpty();
            assertThat(reload(status.getId()).getCoveredUntilDate()).isNull();
            verifyNoInteractions(backfillMetrics);
        }
    }
}
