package com.aaa.collector.market.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximBackfillRateLimiter;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximQuotaExhaustedException;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** BackfillStatus 빌더 접근용 패키지 private 서브클래스. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MarketIndicatorBackfillOrchestrator 단위 테스트")
class MarketIndicatorBackfillOrchestratorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private VixCollectionService vixCollectionService;
    @Mock private UsdkrwCollectionService usdkrwCollectionService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CoveredRangeService coveredRangeService;
    @Mock private KoreaeximExchangeRateClient koreaeximExchangeRateClient;
    @Mock private KoreaeximBackfillRateLimiter koreaeximBackfillRateLimiter;

    private MarketIndicatorBackfillOrchestrator orchestrator;

    // mock managed entity shared across tests
    private BackfillStatus mockManaged;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator =
                new MarketIndicatorBackfillOrchestrator(
                        backfillStatusRepository,
                        marketIndicatorRepository,
                        vixCollectionService,
                        usdkrwCollectionService,
                        transactionTemplate,
                        coveredRangeService,
                        koreaeximExchangeRateClient,
                        koreaeximBackfillRateLimiter);
        // @Value 필드는 DI가 없으므로 테스트용 값 직접 주입
        ReflectionTestUtils.setField(orchestrator, "staleWeekdayThreshold", 3);
        ReflectionTestUtils.setField(orchestrator, "maxCallsPerRunUsdkrw", 500);

        // TransactionTemplate.executeWithoutResult 실제 Consumer 실행
        doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> action = inv.getArgument(0);
                            action.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        mockManaged = Mockito.mock(BackfillStatus.class);
    }

    private BackfillStatus buildStatus(Long id, String code, BackfillStatusType status) {
        BackfillStatus s =
                BackfillStatus.builder()
                        .targetType("MARKET_INDICATOR")
                        .targetCode(code)
                        .dataTable("market_indicators")
                        .status(status)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private MarketIndicatorRow sampleRow(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW, date, null, null, null, BigDecimal.ONE, "KOREAEXIM");
    }

    @Nested
    @DisplayName("seed — 백필 시딩 (REQ-050~052)")
    class SeedingTest {

        @Test
        @DisplayName("USDKRW, VIX 2건 시딩 호출 (REQ-050)")
        void seeds_usdkrwAndVix() {
            orchestrator.seed();

            verify(backfillStatusRepository)
                    .insertIgnoreSeed("MARKET_INDICATOR", "USDKRW", "market_indicators");
            verify(backfillStatusRepository)
                    .insertIgnoreSeed("MARKET_INDICATOR", "VIX", "market_indicators");
        }
    }

    @Nested
    @DisplayName("runBackfill — VIX 백필 (전체 이력 단일 호출)")
    class RunBackfillVix {

        @Test
        @DisplayName(
                "VIX PENDING — collectHistory 후 MIN(trade_date) anchor로 COMPLETED (REQ-041~043, W-4)")
        void vix_pending_collectHistoryAndComplete() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            LocalDate minDate = LocalDate.of(1990, 1, 2);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(100);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.of(minDate));
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(vixCollectionService).collectHistory();
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged)
                    .advance(eq(BackfillStatusType.COMPLETED), eq(minDate), eq(0), eq(100));
        }

        @Test
        @DisplayName("VIX 수집 0건 — anchor=today fallback, COMPLETED (W-4: DB empty → today)")
        void vix_zeroRows_anchorFallbackToday() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty()); // DB에 VIX 데이터 없음
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(mockManaged)
                    .advance(eq(BackfillStatusType.COMPLETED), any(LocalDate.class), eq(0), eq(0));
        }
    }

    @Nested
    @DisplayName("runBackfill — USDKRW 백필 (KOREAEXIM 직접 호출 날짜 루프, REQ-020~027)")
    class RunBackfillUsdkrw {

        @Test
        @DisplayName(
                "USDKRW PENDING — staleWeekdayCount 누적 후 COMPLETED, KOREAEXIM 직접 호출만·체인 미경유 (REQ-020,"
                        + " REQ-044, AC-10)")
        void usdkrw_staleThresholdReached_completed() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // stale threshold 도달 후 COMPLETED 처리
            verify(mockManaged, atLeastOnce())
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
            // AC-10: 체인 경유 collectDailyForBackfill(Yahoo 폴백 가능)은 backward walk에서 절대 호출되지 않는다
            verify(usdkrwCollectionService, never()).collectDailyForBackfill(any());
        }

        @Test
        @DisplayName("USDKRW — 데이터 수신 시 staleCount 리셋(saveBackfillRows 경유 저장)")
        void usdkrw_dataReceived_staleCountReset() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today))) // 첫 호출: 데이터
                    .thenReturn(List.of()); // 이후: stale
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 최소한 한 번은 advance 호출
            verify(mockManaged, atLeastOnce()).advance(any(), any(), anyInt(), anyInt());
            verify(usdkrwCollectionService, atLeastOnce()).saveBackfillRows(any());
        }

        @Test
        @DisplayName("USDKRW — 10일 미만 수집 시 IN_PROGRESS 갱신 없이 COMPLETED 1회만 (W-3, MA-01)")
        void usdkrw_lessThan10Days_noInProgressUpdate() {
            // staleWeekdayThreshold=3, 5일 데이터 수신 후 stale
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today))) // 5회
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of()); // 이후 stale
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 5일 < PROGRESS_BATCH_SIZE(10) → IN_PROGRESS advance 갱신 없음
            verify(mockManaged, never())
                    .advance(eq(BackfillStatusType.IN_PROGRESS), any(), anyInt(), anyInt());
            // 루프 종료 후 COMPLETED 1회
            verify(mockManaged, times(1))
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("정상 stale 종료(쿼터 사망 아님) — 그 밤 갭 walk 정상 실행 (REQ-028 반례)")
        void usdkrw_normalCompletion_gapWalkStillRuns() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(status));

            orchestrator.runBackfill();

            verify(coveredRangeService)
                    .walkGapForward(
                            eq(status), any(UsdkrwCoveredGapFiller.class), any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("processUsdkrw — 앵커 재개 커서 시작 (REQ-025, REQ-026, AC-14)")
    class UsdkrwAnchorResume {

        @Test
        @DisplayName("IN_PROGRESS + anchor 존재 — 커서 시작 = 앵커-1일, 오늘부터 재주행 안 함 (AC-14a)")
        void inProgressWithAnchor_resumesFromAnchorMinusOne() {
            LocalDate anchor = LocalDate.of(2015, 6, 10);
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.IN_PROGRESS);
            ReflectionTestUtils.setField(status, "lastCollectedDate", anchor);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(koreaeximExchangeRateClient, atLeastOnce())
                    .fetchDailyForBackfill(dateCaptor.capture());
            assertThat(dateCaptor.getAllValues().getFirst()).isEqualTo(anchor.minusDays(1));
        }

        @Test
        @DisplayName("PENDING — 커서 시작 = 오늘(KST) 근방(AC-14b)")
        void pending_startsFromToday() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(koreaeximExchangeRateClient, atLeastOnce())
                    .fetchDailyForBackfill(dateCaptor.capture());
            LocalDate today = LocalDate.now(KST);
            // 커서 초기화 직후 while문 내 주말 skip으로 최대 이틀 당겨질 수 있어 근접 범위로 검증
            assertThat(dateCaptor.getAllValues().getFirst()).isBetween(today.minusDays(2), today);
        }

        @Test
        @DisplayName("IN_PROGRESS + anchor=NULL(첫 저장 전 중단) — 오늘(KST) 폴백 (AC-14c)")
        void inProgressWithNullAnchor_startsFromTodayFallback() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.IN_PROGRESS);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(koreaeximExchangeRateClient, atLeastOnce())
                    .fetchDailyForBackfill(dateCaptor.capture());
            LocalDate today = LocalDate.now(KST);
            assertThat(dateCaptor.getAllValues().getFirst()).isBetween(today.minusDays(2), today);
        }
    }

    @Nested
    @DisplayName("processUsdkrw — 회차 캡 (REQ-021, REQ-022, AC-11)")
    class UsdkrwCapReached {

        @Test
        @DisplayName(
                "캡 도달 — IN_PROGRESS 저장 + backward walk 즉시 종료 + 그 밤 갭 walk skip (REQ-021, REQ-022, REQ-028)")
        void capReached_savesInProgressAndSkipsGapWalk() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            ReflectionTestUtils.setField(orchestrator, "maxCallsPerRunUsdkrw", 2);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            // 매 호출 데이터 수신 — stale 임계에 먼저 도달하지 않고 캡이 먼저 발동함을 검증
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today)));
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(koreaeximExchangeRateClient, times(2))
                    .fetchDailyForBackfill(any(LocalDate.class));
            verify(mockManaged)
                    .advance(
                            eq(BackfillStatusType.IN_PROGRESS),
                            any(LocalDate.class),
                            eq(0),
                            anyInt());
            verify(mockManaged, never())
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
            // REQ-028: 캡 도달 회차는 그 밤 갭 walk를 실행하지 않는다
            verify(coveredRangeService, never()).walkGapForward(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("processUsdkrw — 쿼터 예외 백스톱 (REQ-023, AC-12)")
    class UsdkrwQuotaBackstop {

        @Test
        @DisplayName(
                "쿼터 예외 발생 — 즉시 IN_PROGRESS 저장 + backward walk 중단 + 그 밤 갭 walk skip (REQ-023, REQ-028)")
        void quotaException_savesInProgressAndSkipsGapWalk() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenThrow(new KoreaeximQuotaExhaustedException("쿼터 소진(result:4)"));
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 쿼터 예외 직후 backward walk 중단 — 추가 backward 호출 없음(정확히 2회: 정상 1회 + 예외 1회)
            verify(koreaeximExchangeRateClient, times(2))
                    .fetchDailyForBackfill(any(LocalDate.class));
            verify(mockManaged)
                    .advance(
                            eq(BackfillStatusType.IN_PROGRESS),
                            any(LocalDate.class),
                            eq(0),
                            anyInt());
            verify(mockManaged, never())
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
            // REQ-028: 쿼터 예외 회차는 그 밤 갭 walk를 실행하지 않는다
            verify(coveredRangeService, never()).walkGapForward(any(), any(), any());
        }

        @Test
        @DisplayName("쿼터 예외는 REQ-045 지표 단위 예외 격리 경로(FAILED)로 새지 않는다 — IN_PROGRESS로만 저장")
        void quotaException_doesNotFallThroughToGenericFailureHandling() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenThrow(new KoreaeximQuotaExhaustedException("쿼터 소진(result:4)"));
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(mockManaged, never()).fail(any(), anyString());
            verify(mockManaged)
                    .advance(eq(BackfillStatusType.IN_PROGRESS), any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("runBackfill — 예외 격리 (REQ-045)")
    class ExceptionIsolation {

        @Test
        @DisplayName("VIX 예외 — findById 후 fail() 호출, USDKRW 계속")
        void vixException_updateError_usdkrwContinues() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            BackfillStatus usdkrwStatus = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            BackfillStatus mockManagedVix = Mockito.mock(BackfillStatus.class);
            BackfillStatus mockManagedUsdkrw = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus, usdkrwStatus));
            when(vixCollectionService.collectHistory()).thenThrow(new RuntimeException("VIX 실패"));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManagedVix));
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManagedUsdkrw));

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(mockManagedVix).fail(any(), anyString());
        }

        @Test
        @DisplayName("처리 대상 없음 — 아무것도 하지 않음 (REQ-047)")
        void noTargets_noop() {
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(vixCollectionService, never()).collectHistory();
            verify(usdkrwCollectionService, never()).collectHistory();
        }
    }

    @Nested
    @DisplayName("runBackfill — USDKRW 커버-추적 배선 (SPEC-COLLECTOR-BACKFILL-011 AC-12, AC-15)")
    class UsdkrwCoveredGapWalk {

        @Test
        @DisplayName("①②③ 기존 USDKRW 행 재사용 — walkGapForward가 그 행으로 호출됨(신규 행 생성 없음, REQ-CVR-070)")
        void reusesExistingUsdkrwRow_walksGapForward() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of()); // backward walk 대상 없음(이미 COMPLETED)
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));

            orchestrator.runBackfill();

            // 기존 행을 그대로 조회·재사용했을 뿐, 신규 backfill_status 행을 생성하는 호출은 없다(insertIgnoreSeed는
            // seed()에서만 발생 — runBackfill 경로에서 호출되지 않음이 곧 "신규 행 미생성"의 증거).
            verify(backfillStatusRepository, never()).insertIgnoreSeed(any(), any(), any());
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw),
                            any(UsdkrwCoveredGapFiller.class),
                            any(LocalDate.class));
        }

        @Test
        @DisplayName("④ 갭 채우기는 collectDailyForBackfill 재사용 — UsdkrwCoveredGapFiller가 이를 감싸 전달됨")
        void gapFillerWrapsExistingBackfillPath() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));
            LocalDate cursor = LocalDate.of(2026, 7, 1);
            when(usdkrwCollectionService.collectDailyForBackfillWithRaw(cursor))
                    .thenReturn(new UsdkrwCollectionService.SaveOutcome(1, 1));

            orchestrator.runBackfill();

            ArgumentCaptor<UsdkrwCoveredGapFiller> fillerCaptor =
                    ArgumentCaptor.forClass(UsdkrwCoveredGapFiller.class);
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw), fillerCaptor.capture(), any(LocalDate.class));
            fillerCaptor.getValue().persistStep(cursor);
            // filler가 결국 usdkrwCollectionService의 기존 백필 경로를 호출함을 확인 — 신규 fetch 메서드가 아니다.
            verify(usdkrwCollectionService).collectDailyForBackfillWithRaw(cursor);
        }

        @Test
        @DisplayName("⑤ VIX는 여전히 커버-추적에서 제외 — walkGapForward는 USDKRW 행에만 호출된다")
        void vixExcluded_walkGapForwardOnlyForUsdkrw() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));

            orchestrator.runBackfill();

            verify(coveredRangeService, times(1))
                    .walkGapForward(any(), any(), any(LocalDate.class));
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw),
                            any(UsdkrwCoveredGapFiller.class),
                            any(LocalDate.class));
        }

        @Test
        @DisplayName("USDKRW 행 미시딩 상태(seed 이전) — walkGapForward 미호출, 예외 없음")
        void usdkrwRowNotSeeded_walkGapForwardSkipped() {
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.empty());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(coveredRangeService, never()).walkGapForward(any(), any(), any());
        }

        @Test
        @DisplayName("갭 walk 예외 — runBackfill 전체를 중단시키지 않는다(REQ-045 예외 격리 정신 재사용)")
        void gapWalkException_doesNotPropagate() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));
            Mockito.doThrow(new RuntimeException("gap walk 실패"))
                    .when(coveredRangeService)
                    .walkGapForward(any(), any(), any());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName(
            "USDKRW 백필 rate limiter 통합 (SPEC-COLLECTOR-MARKETIND-007 AC-02, AC-03, AC-07, AC-10)")
    class RateLimiterIntegration {

        @Test
        @DisplayName(
                "매 발행 호출 전 consume() → fetchDailyForBackfill 순서·횟수 일치 (AC-03, REQ-005, REQ-007)")
        void backfillLoop_consumesTokenBeforeEachFetch_inOrderAndMatchingCount()
                throws InterruptedException {
            // staleWeekdayThreshold=1 — 첫 연속 1회 빈 결과로 즉시 종료해 주말 skip과 무관하게 발행 호출 수를
            // 결정론적으로 3회(data, data, empty)로 고정한다.
            ReflectionTestUtils.setField(orchestrator, "staleWeekdayThreshold", 1);
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of()); // 3회째 빈 결과 — threshold=1 즉시 종료
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 발행 호출 수(3) = consume() 호출 수(3)
            verify(koreaeximBackfillRateLimiter, times(3)).consume();
            verify(koreaeximExchangeRateClient, times(3))
                    .fetchDailyForBackfill(any(LocalDate.class));
            // 순서: 매 회 consume() 이후 fetchDailyForBackfill
            InOrder inOrder = inOrder(koreaeximBackfillRateLimiter, koreaeximExchangeRateClient);
            for (int i = 0; i < 3; i++) {
                inOrder.verify(koreaeximBackfillRateLimiter).consume();
                inOrder.verify(koreaeximExchangeRateClient)
                        .fetchDailyForBackfill(any(LocalDate.class));
            }
        }

        @Test
        @DisplayName("데이터 수신 케이스 — 결과 무관 release() 정확히 1회 finally 반환 (AC-02, REQ-006)")
        void dataReceived_releasesTokenExactlyOnce() throws InterruptedException {
            // staleWeekdayThreshold=1 — 주말 skip과 무관하게 발행 호출 수를 결정론적으로 2회로 고정.
            ReflectionTestUtils.setField(orchestrator, "staleWeekdayThreshold", 1);
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenReturn(List.of());
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(koreaeximBackfillRateLimiter, times(2)).consume();
            verify(koreaeximBackfillRateLimiter, times(2)).release();
        }

        @Test
        @DisplayName(
                "쿼터 예외 케이스 — release() 정확히 1회 finally 반환, backward walk 즉시 중단 (AC-02, REQ-006)")
        void quotaException_releasesTokenExactlyOnce() throws InterruptedException {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            LocalDate today = LocalDate.now(KST);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(koreaeximExchangeRateClient.fetchDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(List.of(sampleRow(today)))
                    .thenThrow(new KoreaeximQuotaExhaustedException("쿼터 소진(result:4)"));
            when(usdkrwCollectionService.saveBackfillRows(any())).thenReturn(1);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 정상 1회 + 예외 1회 = consume/release 각 2회
            verify(koreaeximBackfillRateLimiter, times(2)).consume();
            verify(koreaeximBackfillRateLimiter, times(2)).release();
        }

        @Test
        @DisplayName("VIX 백필 — limiter 전혀 관여하지 않음(diff 0) (AC-07, REQ-015)")
        void vixBackfill_neverTouchesUsdkrwLimiter() throws InterruptedException {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(koreaeximBackfillRateLimiter, never()).consume();
            verify(koreaeximBackfillRateLimiter, never()).release();
        }

        @Test
        @DisplayName(
                "토큰 대기 중 인터럽트 — fetchDailyForBackfill·release() 모두 미호출, IN_PROGRESS로 실패 기록 (AC-10, REQ-008)")
        void tokenWaitInterrupted_doesNotFetchOrReleaseAndMarksFailed()
                throws InterruptedException {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            Mockito.doThrow(new InterruptedException("토큰 대기 중 인터럽트"))
                    .when(koreaeximBackfillRateLimiter)
                    .consume();
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            try {
                assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

                // consume() 실패 — fetchDailyForBackfill·release() 모두 도달하지 않음
                verify(koreaeximExchangeRateClient, never()).fetchDailyForBackfill(any());
                verify(koreaeximBackfillRateLimiter, never()).release();
                // 인터럽트가 조용히 삼켜지지 않고 실패로 표면화됨(IN_PROGRESS로 기록)
                verify(mockManaged).fail(eq(BackfillStatusType.IN_PROGRESS), anyString());
            } finally {
                // 이후 테스트에 인터럽트 상태가 새지 않도록 정리
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("토큰 대기 중 인터럽트 — 스레드 인터럽트 상태 복원 (AC-10, REQ-008)")
        void tokenWaitInterrupted_restoresInterruptFlag() throws InterruptedException {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            Mockito.doThrow(new InterruptedException("토큰 대기 중 인터럽트"))
                    .when(koreaeximBackfillRateLimiter)
                    .consume();
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            try {
                orchestrator.runBackfill();

                // 인터럽트 상태 복원(REQ-008) — KisRateLimiter 계약과 동일하게 조용히 삼키지 않는다.
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // 이후 테스트에 인터럽트 상태가 새지 않도록 정리
                Thread.interrupted();
            }
        }
    }
}
