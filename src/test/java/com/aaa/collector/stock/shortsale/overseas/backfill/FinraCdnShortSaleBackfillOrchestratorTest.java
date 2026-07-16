package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FinraCdnShortSaleBackfillOrchestrator 단위 테스트 (SPEC-COLLECTOR-BACKFILL-008 T4,
 * SPEC-COLLECTOR-BACKFILL-011 AC-16/-17).
 *
 * <p>전역 앵커 시딩/로드/리셋, 날짜당 1회 다운로드, floor 유일 종료, target_type 격리(R2), 전역 앵커 커버-추적 배선을 검증한다. 시설 합산·심볼
 * 매칭·kept/raw 계산 로직은 {@link FinraCdnDailyLoaderImpl}로 추출되어 {@link FinraCdnDailyLoaderImplTest}가
 * 담당한다(코드리뷰 — PMD CouplingBetweenObjects 완화 리팩터). 이 클래스는 {@link FinraCdnDailyLoader}를 mock으로 대체해
 * 오케스트레이션 흐름(호출 여부·인자·순서)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinraCdnShortSaleBackfillOrchestrator")
class FinraCdnShortSaleBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private StockRepository stockRepository;
    @Mock private FinraCdnDailyFileClient client;
    @Mock private FinraCdnDailyLoader dailyLoader;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private BatchMetrics batchMetrics;
    @Mock private FinraCdnCoveredGapWalkRunner coveredGapWalkRunner;

    private FinraCdnShortSaleBackfillProperties properties;
    private FinraCdnShortSaleBackfillOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new FinraCdnShortSaleBackfillProperties();
        orchestrator =
                new FinraCdnShortSaleBackfillOrchestrator(
                        backfillStatusRepository,
                        stockRepository,
                        client,
                        dailyLoader,
                        properties,
                        transactionTemplate,
                        batchMetrics,
                        coveredGapWalkRunner);

        doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> action = inv.getArgument(0);
                            action.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    private static Stock stock(long id, String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .build();
        ReflectionTestUtils.setField(stock, "id", id);
        return stock;
    }

    private static BackfillStatus anchor(
            Long id, BackfillStatusType status, LocalDate lastCollectedDate, Integer lastRowCount) {
        BackfillStatus mock = Mockito.mock(BackfillStatus.class);
        when(mock.getId()).thenReturn(id);
        when(mock.getStatus()).thenReturn(status);
        when(mock.getLastCollectedDate()).thenReturn(lastCollectedDate);
        when(mock.getLastRowCount()).thenReturn(lastRowCount);
        return mock;
    }

    private void stubAnchorLookup(BackfillStatus status) {
        when(backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                        any(), eq("OVERSEAS_SHORTSALE"), eq("short_sale_overseas")))
                .thenReturn(List.of(status));
        when(backfillStatusRepository.findById(status.getId())).thenReturn(Optional.of(status));
    }

    // ────────────────────────────────────────────────────────────────────
    // 전역 앵커 1행 + target_type 격리 (AC-BF-13, R2 회귀)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("전역 앵커 시딩·격리 (AC-BF-13, R2)")
    class GlobalAnchorIsolation {

        @Test
        @DisplayName("insertIgnoreSeed는 sentinel 값으로 정확히 1회 호출된다(종목별 상태 행 미생성)")
        void run_seedsGlobalAnchorOnceWithSentinelValues() {
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.COMPLETED, LocalDate.of(2009, 8, 3), 0);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            orchestrator.run();

            verify(backfillStatusRepository, times(1))
                    .insertIgnoreSeed("OVERSEAS_SHORTSALE", "__GLOBAL__", "short_sale_overseas");
        }

        @Test
        @DisplayName(
                "[R2] STOCK/MACRO_INDICATOR 조회 메서드(findByStatusInAndTargetTypeOrderById)는 호출하지 않는다")
        void run_neverUsesTargetTypeOnlyQuery() {
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.COMPLETED, LocalDate.of(2009, 8, 3), 0);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            orchestrator.run();

            verify(backfillStatusRepository, never())
                    .findByStatusInAndTargetTypeOrderById(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 날짜당 1회 다운로드 + 앵커 단조 전진 (AC-BF-14/-15)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("날짜당 1회 다운로드 + 앵커 단조 전진 (AC-BF-14/-15)")
    class SingleDownloadPerDate {

        @Test
        @DisplayName("여러 종목이 매칭돼도 날짜별 CDN 다운로드는 정확히 1회씩만 발생한다")
        void perCronCycle_downloadsExactlyOncePerDate() {
            stubThreeDayCycle();

            orchestrator.run();

            verify(client, times(1)).fetch(LocalDate.of(2013, 1, 4));
            verify(client, times(1)).fetch(LocalDate.of(2013, 1, 3));
            verify(client, times(1)).fetch(LocalDate.of(2013, 1, 2));
            verify(client, times(3)).fetch(any());
        }

        @Test
        @DisplayName("앵커는 더 과거 날짜로 단조 전진한다")
        void perCronCycle_advancesAnchorMonotonically() {
            BackfillStatus status = stubThreeDayCycle();

            orchestrator.run();

            ArgumentCaptor<BackfillStatusType> statusCaptor =
                    ArgumentCaptor.forClass(BackfillStatusType.class);
            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(status).advance(statusCaptor.capture(), dateCaptor.capture(), eq(0), any());
            assertThat(statusCaptor.getValue()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2013, 1, 2));
        }

        private BackfillStatus stubThreeDayCycle() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(3);
            LocalDate anchorDate = LocalDate.of(2013, 1, 5);
            BackfillStatus status = anchor(1L, BackfillStatusType.IN_PROGRESS, anchorDate, null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL"), stock(2L, "MSFT")));
            when(client.fetch(any()))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));
            return status;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // floor 유일 종료 + 파일 부재는 종료 신호 아님 (AC-BF-12/-17)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("floor 유일 종료 (AC-BF-12/-17)")
    class FloorTermination {

        @Test
        @DisplayName("앵커가 floor 도달 시에만 COMPLETED로 전이하고, 파일 부재(403)는 종료 신호로 해석하지 않는다")
        void reachesFloor_transitionsToCompletedOnlyThen() {
            properties.setFloorDate(LocalDate.of(2013, 1, 1));
            properties.setPerCronDateCap(100);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, LocalDate.of(2013, 1, 3), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(any()))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.FLOOR_BEFORE_403));

            orchestrator.run();

            // 2013-01-02, 2013-01-01 두 날짜만 처리하고 floor 도달로 종료(부재만으로 조기 종료하지 않음).
            verify(client, times(2)).fetch(any());
            verify(status).advance(BackfillStatusType.COMPLETED, LocalDate.of(2013, 1, 1), 0, 1);
        }

        @Test
        @DisplayName("floor 미도달이면 IN_PROGRESS로만 전진하고 COMPLETED로 전이하지 않는다")
        void notYetAtFloor_staysInProgress() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(1);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, LocalDate.of(2013, 1, 3), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(any()))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            orchestrator.run();

            verify(status)
                    .advance(
                            eq(BackfillStatusType.IN_PROGRESS), any(LocalDate.class), eq(0), any());
            verify(status, never())
                    .advance(eq(BackfillStatusType.COMPLETED), any(), any(Integer.class), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 일시적 오류(TRANSIENT_ERROR) 시 안전 종료 (코드리뷰 Fix 1)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("일시적 오류 시 사이클 안전 종료 (코드리뷰 Fix 1)")
    class TransientErrorTermination {

        @Test
        @DisplayName("일시적 오류를 만난 날짜 이전까지만 처리하고, 예외 없이 그 이전 날짜로만 앵커를 전진시킨다(실패일 재시도 보장)")
        void transientError_stopsCycleWithoutAdvancingPastFailedDate() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(3);
            LocalDate anchorDate = LocalDate.of(2013, 1, 5);
            LocalDate day1 = LocalDate.of(2013, 1, 4);
            LocalDate day2 = LocalDate.of(2013, 1, 3);
            LocalDate day3 = LocalDate.of(2013, 1, 2);
            BackfillStatus status = anchor(1L, BackfillStatusType.IN_PROGRESS, anchorDate, null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(day1))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));
            when(client.fetch(day2))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR));

            orchestrator.run();

            verify(client, times(1)).fetch(day1);
            verify(client, times(1)).fetch(day2);
            verify(client, never()).fetch(day3);
            verify(status).advance(BackfillStatusType.IN_PROGRESS, day1, 0, 1);
        }

        @Test
        @DisplayName("사이클 첫 날짜부터 일시적 오류면 진행분이 없어 앵커를 전진시키지 않는다")
        void transientErrorOnFirstDate_noProgress_doesNotAdvanceAnchor() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(3);
            LocalDate anchorDate = LocalDate.of(2013, 1, 5);
            LocalDate day1 = LocalDate.of(2013, 1, 4);
            BackfillStatus status = anchor(1L, BackfillStatusType.IN_PROGRESS, anchorDate, null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(day1))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR));

            orchestrator.run();

            verify(client, times(1)).fetch(any());
            verify(status, never()).advance(any(), any(), any(Integer.class), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 신규 종목 리셋 트리거 (AC-BF-18)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("신규 종목 편입 리셋 (AC-BF-18)")
    class ResetTrigger {

        @Test
        @DisplayName("COMPLETED + currentActiveUsCount > coveredCount → IN_PROGRESS 리셋(앵커=today)")
        void completedWithIncreasedCount_resetsToInProgress() {
            properties.setPerCronDateCap(0);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.COMPLETED, LocalDate.of(2009, 8, 3), 1);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL"), stock(2L, "MSFT")));

            orchestrator.run();

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(status)
                    .advance(
                            eq(BackfillStatusType.IN_PROGRESS),
                            dateCaptor.capture(),
                            eq(0),
                            isNull());
            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("COMPLETED + 증가 없음 → return(완료 유지, advance/다운로드 미발생)")
        void completedWithoutIncrease_returnsWithoutReset() {
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.COMPLETED, LocalDate.of(2009, 8, 3), 2);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL"), stock(2L, "MSFT")));

            orchestrator.run();

            verify(status, never()).advance(any(), any(), any(Integer.class), any());
            verify(client, never()).fetch(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 파싱 skip·매칭 실패가 있어도 예외 없이 사이클 완료 (AC-BF-25 관측성 스모크)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("관측성 카운트 — 예외 없이 사이클 완료 (AC-BF-25)")
    class ObservabilitySmoke {

        @Test
        @DisplayName("다운로드 부재·dailyLoader의 파싱 skip·매칭 실패 결과가 혼재해도 예외 없이 앵커를 전진시킨다")
        void mixedFailures_completesCycleWithoutException() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(2);
            LocalDate d1 = LocalDate.of(2013, 1, 3);
            LocalDate d0 = LocalDate.of(2013, 1, 2);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, d1.plusDays(1), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(d1)).thenReturn(new FinraCdnFetchResult.Found(List.of("BODY")));
            when(dailyLoader.loadDate(eq(d1), eq(List.of("BODY")), any()))
                    .thenReturn(new FinraCdnDailyLoadOutcome(0, 1, 3, 1));
            when(client.fetch(d0))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            orchestrator.run();

            verify(status).advance(eq(BackfillStatusType.IN_PROGRESS), eq(d0), eq(0), any());
            // REQ-SSD-009: 파싱 거부(skip=3)가 last_load 독립 카운터로 계측된다(CDN 경로 계측 연결)
            verify(batchMetrics).recordParseRejections("overseas-shortsale-backfill", 3L);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FINRA Daily 전역 앵커 커버-추적 배선 (SPEC-COLLECTOR-BACKFILL-011 AC-16/-17, DP-4)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("전역 앵커 커버-추적 배선 (SPEC-COLLECTOR-BACKFILL-011 AC-16/-17)")
    class CoveredGapWalkWiring {

        @Test
        @DisplayName(
                "①②③ backward walk가 COMPLETED 유지로 조기 반환해도 coveredGapWalkRunner는 동일 전역 앵커로 항상 호출된다")
        void gapWalkRunsEvenWhenBackwardWalkCompletesEarly() {
            // Arrange — COMPLETED 유지(증가 없음) 시나리오: 기존 코드는 여기서 return했었다(REQ-CVR-011 무시 버그).
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.COMPLETED, LocalDate.of(2009, 8, 3), 2);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL"), stock(2L, "MSFT")));

            orchestrator.run();

            // backward walk는 여전히 조기 반환(다운로드 없음) — 기존 AC-BF-18 비회귀
            verify(client, never()).fetch(any());
            // 하지만 갭 walk는 동일 전역 앵커(단 1행)·동일 dailyLoader로 위임됨 — 종목별 신규 행이 아니다(REQ-CVR-071).
            verify(coveredGapWalkRunner)
                    .runFor(eq(status), any(LocalDate.class), eq(dailyLoader), any());
        }

        @Test
        @DisplayName(
                "⑤ DP-4 — 하단 backward walk 커밋(advanceAnchor)과 상단 갭 walk 위임은 서로 다른 최상위 호출로 순차 실행된다")
        void backwardWalkCommitAndGapWalkAreIndependentTransactions() {
            // Arrange — backward walk가 실제로
            // advanceAnchor(=transactionTemplate.executeWithoutResult)를
            // 호출하는 시나리오(IN_PROGRESS, 날짜 부재로 즉시 IN_PROGRESS 재확정)를 구성한다.
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(1);
            LocalDate anchorDate = LocalDate.of(2013, 1, 5);
            BackfillStatus status = anchor(1L, BackfillStatusType.IN_PROGRESS, anchorDate, null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(any()))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            orchestrator.run();

            // 갭 walk 위임(coveredGapWalkRunner.runFor) 호출이 backward walk의 트랜잭션 콜백
            // (transactionTemplate.executeWithoutResult) 내부에 중첩되지 않고, 별도의 최상위 호출로
            // 순차 실행됨을 순서로 확인한다 — 두 갱신이 독립 트랜잭션 경계에서 커밋됨을 방증한다(실제 트랜잭션 격리는
            // FinraCdnCoveredGapWalkRunner/CoveredRangeService 내부에서 보장 —
            // FinraCdnCoveredGapWalkRunnerTest
            // 담당).
            InOrder inOrder = Mockito.inOrder(transactionTemplate, coveredGapWalkRunner);
            inOrder.verify(transactionTemplate).executeWithoutResult(any());
            inOrder.verify(coveredGapWalkRunner).runFor(any(), any(), any(), any());
        }
    }
}
