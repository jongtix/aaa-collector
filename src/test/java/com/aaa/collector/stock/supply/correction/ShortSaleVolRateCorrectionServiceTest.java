package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ShortSaleVolRateCorrectionService} 단위 테스트 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
 * REQ-SSVC-031~039, plan.md §M4).
 *
 * <p>Track 1은 실제 KIS 재조회를 대체하는 {@link ShortSaleCollectionService} mock + 실제 재계산 판정 대상인 {@link
 * AcmlVolReconciliationGuard} mock을 조합해 MATCHED/EVENT_ADJUSTED/REVISION_SUSPECTED 세 경로를 독립적으로
 * 검증한다(AC-13/-14/-15). Track 2는 가드·TR04 재조회 둘 다 호출되지 않아야 함을 {@code verifyNoInteractions}로
 * 확인한다(AC-9e).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleVolRateCorrectionService 단위 테스트")
class ShortSaleVolRateCorrectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private AcmlVolReconciliationGuard guard;
    @Mock private ShortSaleCollectionService shortSaleCollectionService;
    @Mock private HealthyKeySelector healthyKeySelector;

    private ShortSaleVolRateCorrectionService service;

    @BeforeEach
    void setUp() {
        // 실제 KeyLeaseRegistry + mock HealthyKeySelector — openSession()이 진짜 LeaseSession을 생성한다
        // (DomesticDailyOhlcvCollectionServiceTest와 동일 패턴).
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new ShortSaleVolRateCorrectionService(
                        shortSaleDomesticRepository,
                        dailyOhlcvRepository,
                        guard,
                        shortSaleCollectionService,
                        keyLeaseRegistry);
    }

    private Stock stockOf(String symbol, long id) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(stock, "id", id);
        return stock;
    }

    private ShortSaleDomestic shortSaleRow(
            Stock stock,
            LocalDate tradeDate,
            long qty,
            BigDecimal rate,
            Long acmlVol,
            LocalDateTime verifiedAt,
            long id) {
        ShortSaleDomestic row =
                ShortSaleDomestic.builder()
                        .stock(stock)
                        .tradeDate(tradeDate)
                        .shortSellQty(qty)
                        .shortSellVolRate(rate)
                        .shortSellAmt(0L)
                        .shortSellAmtRate(BigDecimal.ZERO)
                        .shortSellAccQty(0L)
                        .shortSellAccQtyRate(BigDecimal.ZERO)
                        .shortSellAccAmt(0L)
                        .shortSellAccAmtRate(BigDecimal.ZERO)
                        .acmlVol(acmlVol)
                        .volRateVerifiedAt(verifiedAt)
                        .build();
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private DailyOhlcv dailyOhlcvOf(Stock stock, LocalDate tradeDate, long volume) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .openPrice(BigDecimal.ZERO)
                .highPrice(BigDecimal.ZERO)
                .lowPrice(BigDecimal.ZERO)
                .closePrice(BigDecimal.ZERO)
                .volume(volume)
                .tradingValue(0L)
                .build();
    }

    private KisShortSaleResponse tr04Response(String date, String sstsCntgQty, String acmlVol) {
        return new KisShortSaleResponse(
                "0",
                "MCA00000",
                "조회되었습니다.",
                List.of(
                        new KisShortSaleResponse.ShortSaleRow(
                                date, sstsCntgQty, "0", "0", "0", "0", "0", "0", "0", acmlVol)));
    }

    @Nested
    @DisplayName("Track 1 — correctLegacyBacklog (REQ-SSVC-031, -032, -050~057)")
    class Track1LegacyBacklog {

        @Test
        @DisplayName("모든 키 죽음 — 배치 skip, 조회조차 발생하지 않음")
        void allKeysDead_skipsWithoutQuerying() {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog();

            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(0, 0, 0));
            verify(shortSaleDomesticRepository, never())
                    .findTrack1LegacyBacklogBatch(anyLong(), any(Pageable.class));
        }

        @Test
        @DisplayName("AC-13 — MATCHED: 재조회 acmlVol 그대로 채택, rate는 daily_ohlcv 공식으로 재계산·원자적 UPDATE")
        void matched_updatesAcmlVolAndRecomputedRate() throws Exception {
            // Arrange
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("005930", 1L);
            LocalDate date = LocalDate.of(2026, 6, 5);
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 10_000L, new BigDecimal("2.00"), null, null, 100L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("005930"), eq(date)))
                    .thenReturn(tr04Response("20260605", "10000", "500000"));
            when(guard.reconcile(new BigDecimal("2.00"), 10_000L, 500_000L, 10_000L))
                    .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(1L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

            // Act — recomputedRate = 10000*100/1000000 = 1.00
            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog();

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack1Correction(
                            eq(100L),
                            eq(500_000L),
                            eq(new BigDecimal("1.00")),
                            any(LocalDateTime.class));
        }

        @Test
        @DisplayName("AC-14 — EVENT_ADJUSTED: 역산 acmlVol 채택, rate는 동일하게 daily_ohlcv 공식 재계산")
        void eventAdjusted_updatesReconciledAcmlVol() throws Exception {
            // Arrange
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("007120", 2L);
            LocalDate date = LocalDate.of(2026, 7, 1);
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 2_000L, new BigDecimal("0.20"), null, null, 200L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("007120"), eq(date)))
                    .thenReturn(tr04Response("20260701", "10000", "1000000"));
            when(guard.reconcile(new BigDecimal("0.20"), 2_000L, 1_000_000L, 10_000L))
                    .thenReturn(AcmlVolReconciliationResult.eventAdjusted(1_000_000L));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(2L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 500_000L)));

            // Act — recomputedRate = 2000*100/500000 = 0.40
            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog();

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack1Correction(
                            eq(200L),
                            eq(1_000_000L),
                            eq(new BigDecimal("0.40")),
                            any(LocalDateTime.class));
        }

        @Test
        @DisplayName("AC-15 — REVISION_SUSPECTED: 정정 스킵, acml_vol·vol_rate_verified_at 미충전")
        void revisionSuspected_skipsWithoutUpdate() throws Exception {
            // Arrange
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("005930", 3L);
            LocalDate date = LocalDate.of(2026, 7, 20);
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 15_600L, new BigDecimal("1.56"), null, null, 300L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("005930"), eq(date)))
                    .thenReturn(tr04Response("20260720", "25700", "1000000"));
            when(guard.reconcile(new BigDecimal("1.56"), 15_600L, 1_000_000L, 25_700L))
                    .thenReturn(AcmlVolReconciliationResult.revisionSuspected());

            // Act
            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog();

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(0, 1, 0));
            verify(shortSaleDomesticRepository, never())
                    .updateTrack1Correction(anyLong(), anyLong(), any(), any());
            verifyNoInteractions(dailyOhlcvRepository);
        }

        @Test
        @DisplayName("배율 [0.67,1.5] 밖(REQ-SSVC-041) — WARN 로그 발생, 정정은 그대로 진행")
        void outOfBandRatio_emitsWarnButStillCorrects() throws Exception {
            Logger serviceLogger =
                    (Logger) LoggerFactory.getLogger(ShortSaleVolRateCorrectionService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            try {
                // Arrange — storedRate=5.00, recomputedRate=1.00 → ratio=5.0 (> 1.5, WARN 대상)
                when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
                Stock stock = stockOf("005930", 4L);
                LocalDate date = LocalDate.of(2026, 6, 5);
                ShortSaleDomestic row =
                        shortSaleRow(
                                stock, date, 10_000L, new BigDecimal("5.00"), null, null, 400L);
                when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                                anyLong(), any(Pageable.class)))
                        .thenReturn(List.of(row))
                        .thenReturn(List.of());
                when(shortSaleCollectionService.fetchSingleDate(
                                any(LeaseSession.class), eq("005930"), eq(date)))
                        .thenReturn(tr04Response("20260605", "10000", "200000"));
                when(guard.reconcile(new BigDecimal("5.00"), 10_000L, 200_000L, 10_000L))
                        .thenReturn(AcmlVolReconciliationResult.matched(200_000L));
                when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(4L, List.of(date)))
                        .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

                // Act
                ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog();

                // Assert
                assertThat(result.corrected()).isEqualTo(1);
                List<ILoggingEvent> warnLogs =
                        appender.list.stream()
                                .filter(e -> e.getLevel() == Level.WARN)
                                .filter(e -> e.getFormattedMessage().contains("배율 이상 관측"))
                                .toList();
                assertThat(warnLogs).hasSize(1);
            } finally {
                serviceLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("Track 1 배치 크기는 1보다 크고 유한하다(AC-8 ①)")
        void batchSizeIsFiniteAndGreaterThanOne() {
            assertThat(ShortSaleVolRateCorrectionService.TRACK1_BATCH_SIZE).isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("Track 2 — verifyRecentInserts (REQ-SSVC-034~038)")
    class Track2PendingVerification {

        @Test
        @DisplayName("AC-9c/-9d — 재계산값 동일: UPDATE 호출(no-op 값)·verified_at 기록, 가드·TR04 미호출(AC-9e)")
        void sameValue_stillMarksVerified_noGuardOrTr04Call() {
            // Arrange
            Stock stock = stockOf("005930", 5L);
            LocalDate date = LocalDate.of(2026, 6, 5);
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("1.00"), 1_000_000L, null, 500L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(5L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

            // Act — recomputedRate = 10000*100/1000000 = 1.00 (저장값과 동일)
            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts();

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack2Verification(
                            eq(500L), eq(new BigDecimal("1.00")), any(LocalDateTime.class));
            verifyNoInteractions(guard);
            verifyNoInteractions(shortSaleCollectionService);
        }

        @Test
        @DisplayName("AC-9c — 재계산값 상이(백필 왜곡 재현): UPDATE로 정정값 반영")
        void differentValue_updatesToRecomputedRate() {
            // Arrange
            Stock stock = stockOf("097230", 6L);
            LocalDate date = LocalDate.of(2026, 6, 10);
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("10.30"), 1_000_000L, null, 600L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(6L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

            // Act — recomputedRate = 10000*100/1000000 = 1.00 (저장값 10.30과 상이)
            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts();

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack2Verification(
                            eq(600L), eq(new BigDecimal("1.00")), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("EC-1 — 매칭되는 daily_ohlcv 행 없음: skip, UPDATE 미호출")
        void dailyOhlcvMissing_skipsWithoutUpdate() {
            Stock stock = stockOf("005930", 7L);
            LocalDate date = LocalDate.of(2026, 6, 5);
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("1.00"), 1_000_000L, null, 700L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(7L, List.of(date)))
                    .thenReturn(List.of());

            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts();

            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(0, 0, 1));
            verify(shortSaleDomesticRepository, never())
                    .updateTrack2Verification(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Track 2 배치 크기는 1보다 크고 유한하다")
        void batchSizeIsFiniteAndGreaterThanOne() {
            assertThat(ShortSaleVolRateCorrectionService.TRACK2_BATCH_SIZE).isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("T0R 완료 마커 게이트 (REQ-T0R-043~045, plan.md §M7)")
    class T0rCompletionMarkerGate {

        @Test
        @DisplayName(
                "Track 1 ②단계 — 게이트 active + 대상 거래일이 닫히는 창 구간이면 defer(acml_vol·"
                        + "vol_rate_verified_at 미기록, REQ-T0R-044)")
        void track1_deferredWhenGateActiveAndWithinWindow() throws Exception {
            // Arrange — MATCHED 판정이지만 거래일이 닫히는 창 [2026-06-29, 2026-08-06] 내부
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("005930", 10L);
            LocalDate date = LocalDate.of(2026, 7, 15);
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 10_000L, new BigDecimal("2.00"), null, null, 1000L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("005930"), eq(date)))
                    .thenReturn(tr04Response("20260715", "10000", "500000"));
            when(guard.reconcile(new BigDecimal("2.00"), 10_000L, 500_000L, 10_000L))
                    .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
            T0rGateState gate = new T0rGateState(true, LocalDate.of(2026, 8, 6));

            // Act
            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog(gate);

            // Assert — defer는 skipped 버킷에 집계되며, 원자적 쓰기·daily_ohlcv 조회 모두 발생하지 않는다
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(0, 0, 1));
            verify(shortSaleDomesticRepository, never())
                    .updateTrack1Correction(anyLong(), anyLong(), any(), any());
            verifyNoInteractions(dailyOhlcvRepository);
        }

        @Test
        @DisplayName("Track 1 ②단계 — 게이트 active이나 거래일이 구간 밖이면 정상 처리(defer 미적용)")
        void track1_notDeferredWhenTradeDateOutsideWindow() throws Exception {
            // Arrange — 거래일이 닫히는 창 상한(2026-08-06) 이후
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("005930", 11L);
            LocalDate date = LocalDate.of(2026, 8, 10);
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 10_000L, new BigDecimal("2.00"), null, null, 1100L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("005930"), eq(date)))
                    .thenReturn(tr04Response("20260810", "10000", "500000"));
            when(guard.reconcile(new BigDecimal("2.00"), 10_000L, 500_000L, 10_000L))
                    .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(11L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));
            T0rGateState gate = new T0rGateState(true, LocalDate.of(2026, 8, 6));

            // Act
            ShortSaleVolRateCorrectionResult result = service.correctLegacyBacklog(gate);

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack1Correction(
                            eq(1100L), eq(500_000L), eq(new BigDecimal("1.00")), any());
        }

        @Test
        @DisplayName("Track 1 — 게이트 비활성(completed_at NOT NULL)이면 구간 검사 자체를 생략(REQ-T0R-045)")
        void track1_gateInactive_skipsWindowCheckEntirely() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            Stock stock = stockOf("005930", 12L);
            LocalDate date = LocalDate.of(2026, 7, 15); // 닫히는 창 내부 날짜지만 게이트 비활성
            ShortSaleDomestic row =
                    shortSaleRow(stock, date, 10_000L, new BigDecimal("2.00"), null, null, 1200L);
            when(shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("005930"), eq(date)))
                    .thenReturn(tr04Response("20260715", "10000", "500000"));
            when(guard.reconcile(new BigDecimal("2.00"), 10_000L, 500_000L, 10_000L))
                    .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(12L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

            // Act — completed_at NOT NULL이므로 active=false
            ShortSaleVolRateCorrectionResult result =
                    service.correctLegacyBacklog(new T0rGateState(false, LocalDate.of(2026, 8, 6)));

            // Assert — defer 없이 정상 처리
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack1Correction(
                            eq(1200L), eq(500_000L), eq(new BigDecimal("1.00")), any());
        }

        @Test
        @DisplayName("Track 2 recompute 호출 직전 — 게이트 active + 구간 내부면 defer, 가드·daily_ohlcv 미호출")
        void track2_deferredWhenGateActiveAndWithinWindow() {
            // Arrange
            Stock stock = stockOf("005930", 13L);
            LocalDate date = LocalDate.of(2026, 7, 15);
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("1.00"), 1_000_000L, null, 1300L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            T0rGateState gate = new T0rGateState(true, LocalDate.of(2026, 8, 6));

            // Act
            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts(gate);

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(0, 0, 1));
            verify(shortSaleDomesticRepository, never())
                    .updateTrack2Verification(anyLong(), any(), any());
            verifyNoInteractions(dailyOhlcvRepository, guard, shortSaleCollectionService);
        }

        @Test
        @DisplayName("Track 2 — 게이트 active이나 거래일이 구간 밖이면 정상 처리(defer 미적용)")
        void track2_notDeferredWhenTradeDateOutsideWindow() {
            // Arrange
            Stock stock = stockOf("005930", 14L);
            LocalDate date = LocalDate.of(2026, 8, 10);
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("1.00"), 1_000_000L, null, 1400L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(14L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));
            T0rGateState gate = new T0rGateState(true, LocalDate.of(2026, 8, 6));

            // Act
            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts(gate);

            // Assert
            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
            verify(shortSaleDomesticRepository)
                    .updateTrack2Verification(eq(1400L), eq(new BigDecimal("1.00")), any());
        }

        @Test
        @DisplayName("no-arg 오버로드는 T0rGateState.inactive()로 위임한다(기존 M4 테스트 호환)")
        void noArgOverloads_delegateToInactiveGate() {
            Stock stock = stockOf("005930", 15L);
            LocalDate date = LocalDate.of(2026, 7, 15); // 닫히는 창 내부 날짜라도 게이트 자체가 비활성
            ShortSaleDomestic row =
                    shortSaleRow(
                            stock, date, 10_000L, new BigDecimal("1.00"), 1_000_000L, null, 1500L);
            when(shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            anyLong(), any(Pageable.class)))
                    .thenReturn(List.of(row))
                    .thenReturn(List.of());
            when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(15L, List.of(date)))
                    .thenReturn(List.of(dailyOhlcvOf(stock, date, 1_000_000L)));

            ShortSaleVolRateCorrectionResult result = service.verifyRecentInserts();

            assertThat(result).isEqualTo(new ShortSaleVolRateCorrectionResult(1, 0, 0));
        }
    }
}
