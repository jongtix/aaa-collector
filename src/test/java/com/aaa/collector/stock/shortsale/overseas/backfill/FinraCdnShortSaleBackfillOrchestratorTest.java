package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FinraCdnShortSaleBackfillOrchestrator 단위 테스트 (SPEC-COLLECTOR-BACKFILL-008 T4).
 *
 * <p>전역 앵커 시딩/로드/리셋, 시설 합산, 심볼 매칭(재사용), 날짜당 1회 다운로드, floor 유일 종료, target_type 격리(R2)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinraCdnShortSaleBackfillOrchestrator")
class FinraCdnShortSaleBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private StockRepository stockRepository;
    @Mock private FinraCdnDailyFileClient client;
    @Mock private FinraCdnFileParser parser;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private TransactionTemplate transactionTemplate;

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
                        parser,
                        shortSaleOverseasRepository,
                        properties,
                        transactionTemplate);

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
    // 시설 합산 (AC-BF-04)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("시설 다중 파일 합산 (AC-BF-04)")
    class FacilitySummation {

        @Test
        @DisplayName("시설 2개 파일 존재 시 종목별 short/total volume이 파일 합과 일치한다")
        void multiFacilityFiles_summedPerStock() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(1);
            LocalDate target = LocalDate.of(2013, 1, 2);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, target.plusDays(1), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));

            when(client.fetch(target))
                    .thenReturn(new FinraCdnFetchResult.Found(List.of("FNSQ-BODY", "FNYX-BODY")));
            when(parser.parse("FNSQ-BODY"))
                    .thenReturn(new ParsedFileResult(List.of(new ParsedRow("AAPL", 100, 1000)), 0));
            when(parser.parse("FNYX-BODY"))
                    .thenReturn(new ParsedFileResult(List.of(new ParsedRow("AAPL", 50, 500)), 0));

            orchestrator.run();

            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(target),
                            eq(150L),
                            eq(1500L),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 매칭 — 라이브 범위 재사용, 슬래시 정규화, 워런트 제외 (AC-BF-08/-09/-11)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("종목 매칭 — 범위·정규화·워런트 제외 (AC-BF-08/-09/-11)")
    class SymbolMatching {

        @Test
        @DisplayName("슬래시 클래스주식(BRK/B)은 정규화되어 매칭, 워런트(/WS)·범위 밖 종목은 자연 제외된다")
        void matching_normalizesSlashAndExcludesOutOfScope() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(1);
            LocalDate target = LocalDate.of(2013, 1, 2);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, target.plusDays(1), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(2L, "BRK.B")));

            when(client.fetch(target)).thenReturn(new FinraCdnFetchResult.Found(List.of("BODY")));
            when(parser.parse("BODY"))
                    .thenReturn(
                            new ParsedFileResult(
                                    List.of(
                                            new ParsedRow("BRK/B", 10, 100),
                                            new ParsedRow("AAPL/WS", 5, 50),
                                            new ParsedRow("MSFT", 20, 200)),
                                    0));

            orchestrator.run();

            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(2L),
                            eq(target),
                            eq(10L),
                            eq(100L),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            verify(shortSaleOverseasRepository, times(1))
                    .upsertDaily(
                            anyLong(),
                            eq(target),
                            any(Long.class),
                            any(Long.class),
                            any(),
                            any(),
                            any());
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
    // upsertDaily interest 파라미터 null 계약 (AC-BF-20/-21)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("upsertDaily interest 파라미터 null 계약 (AC-BF-20/-21)")
    class UpsertInterestNullContract {

        @Test
        @DisplayName("백필 적재는 shortInterest/shortInterestDate에 항상 null을 전달한다")
        void loadDate_alwaysPassesNullInterestParams() {
            properties.setFloorDate(LocalDate.of(2000, 1, 1));
            properties.setPerCronDateCap(1);
            LocalDate target = LocalDate.of(2013, 1, 2);
            BackfillStatus status =
                    anchor(1L, BackfillStatusType.IN_PROGRESS, target.plusDays(1), null);
            stubAnchorLookup(status);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(stock(1L, "AAPL")));
            when(client.fetch(target)).thenReturn(new FinraCdnFetchResult.Found(List.of("BODY")));
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(new ParsedRow("AAPL", 10, 100)), 0));

            orchestrator.run();

            ArgumentCaptor<Long> shortInterestCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<LocalDate> shortInterestDateCaptor =
                    ArgumentCaptor.forClass(LocalDate.class);
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(target),
                            eq(10L),
                            eq(100L),
                            any(LocalDateTime.class),
                            shortInterestCaptor.capture(),
                            shortInterestDateCaptor.capture());
            assertThat(shortInterestCaptor.getValue()).isNull();
            assertThat(shortInterestDateCaptor.getValue()).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 파싱 skip·매칭 실패가 있어도 예외 없이 사이클 완료 (AC-BF-25 관측성 스모크)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("관측성 카운트 — 예외 없이 사이클 완료 (AC-BF-25)")
    class ObservabilitySmoke {

        @Test
        @DisplayName("다운로드 부재·파싱 skip·매칭 실패가 혼재해도 예외 없이 앵커를 전진시킨다")
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
            when(parser.parse("BODY"))
                    .thenReturn(new ParsedFileResult(List.of(new ParsedRow("UNMATCHED", 1, 2)), 3));
            when(client.fetch(d0))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            orchestrator.run();

            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(Long.class),
                            any(Long.class),
                            any(),
                            any(),
                            any());
            verify(status).advance(eq(BackfillStatusType.IN_PROGRESS), eq(d0), eq(0), any());
        }
    }
}
