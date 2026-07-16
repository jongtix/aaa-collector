package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.shortsale.overseas.FinraShortSaleClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link FinraCdnCoveredGapFiller} 단위 테스트 (SPEC-COLLECTOR-BACKFILL-011 AC-12, 부분 AC-8/-9).
 *
 * <p>순수 Mockito — CDN 경로({@link FinraCdnDailyFileClient})만 재사용 검증하고 라이브 REST({@link
 * FinraShortSaleClient})와의 결합이 아예 없음을 증명한다(REQ-CVR-051a). {@code loadDate} 재사용은 {@link
 * FinraCdnDailyLoader} 함수형 인터페이스를 통해 검증한다 — 오케스트레이터 구체 타입은 더 이상 필요 없다(코드리뷰 — PMD
 * CouplingBetweenObjects 완화 리팩터).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinraCdnCoveredGapFiller — CDN 전용 갭 채우기 (SPEC-COLLECTOR-BACKFILL-011)")
class FinraCdnCoveredGapFillerTest {

    @Mock private FinraCdnDailyFileClient client;
    @Mock private FinraCdnDailyLoader loader;
    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private TransactionTemplate transactionTemplate;

    /** 라이브 REST 클라이언트 — Filler는 이 타입을 전혀 참조하지 않는다(구조적으로 호출 불가, ②에서 재확인). */
    @Mock private FinraShortSaleClient finraShortSaleClient;

    private static Stock stock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .market(Market.NASDAQ)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("persistStep — CDN 전용 경로, kept/raw 매핑")
    class PersistStep {

        @Test
        @DisplayName("① Found + loader 위임 — client.fetch(cursor)만 호출되고 kept/raw는 loader 결과 그대로")
        void found_delegatesToLoader() {
            // Arrange
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            Map<String, Stock> symbolMap = Map.of("AAPL", stock("AAPL"));
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, symbolMap);

            when(client.fetch(cursor)).thenReturn(new FinraCdnFetchResult.Found(List.of("body")));
            when(loader.loadDate(eq(cursor), eq(List.of("body")), eq(symbolMap)))
                    .thenReturn(new FinraCdnDailyLoadOutcome(1, 1, 0, 0));

            // Act
            CoveredFillResult result = filler.persistStep(cursor);

            // Assert
            assertThat(result.kept()).isEqualTo(1);
            assertThat(result.raw()).isEqualTo(1);
            assertThat(result.filledUntil()).isEqualTo(cursor);
            verify(client, times(1)).fetch(cursor);
        }

        @Test
        @DisplayName("② 라이브 REST(fetchRegShoDaily)는 전혀 호출되지 않는다(REQ-CVR-051a)")
        void neverCallsLiveRestClient() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, Map.of());
            when(client.fetch(cursor))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            filler.persistStep(cursor);

            verifyNoInteractions(finraShortSaleClient);
        }

        @Test
        @DisplayName("Absent(404, 정상 빈 응답) — kept=raw=0, loader 미호출")
        void absent_notGenerated_keptRawZero() {
            LocalDate cursor = LocalDate.of(2026, 7, 12); // 토요일 — 휴장
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, Map.of());
            when(client.fetch(cursor))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404));

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.kept()).isZero();
            assertThat(result.raw()).isZero();
            verify(loader, never()).loadDate(any(), any(), any());
        }

        @Test
        @DisplayName("Absent(일시적 오류) — kept=raw=0(anomaly 아님, walkGapForward가 이번 회차만 중단)")
        void absent_transientError_keptRawZero() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, Map.of());
            when(client.fetch(cursor))
                    .thenReturn(
                            new FinraCdnFetchResult.Absent(
                                    FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR));

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.kept()).isZero();
            assertThat(result.raw()).isZero();
        }

        @Test
        @DisplayName("Found + 전량 매칭 실패(unmatched) — raw>0 && kept==0 (REQ-CVR-031 anomaly 입력 조건)")
        void found_allUnmatched_rawPositiveKeptZero() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, Map.of());
            when(client.fetch(cursor)).thenReturn(new FinraCdnFetchResult.Found(List.of("body")));
            when(loader.loadDate(eq(cursor), eq(List.of("body")), eq(Map.of())))
                    .thenReturn(new FinraCdnDailyLoadOutcome(0, 1, 0, 1));

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.raw()).isEqualTo(1);
            assertThat(result.kept()).isZero();
        }
    }

    @Nested
    @DisplayName("CoveredRangeService.executeStep 통합 — kept/raw 게이트 (TASK-003 조합)")
    class ExecuteStepIntegration {

        @Mock private BackfillMetrics backfillMetrics;
        @Mock private MarketOpenGate marketOpenGate;
        @Mock private UsMarketOpenGate usMarketOpenGate;

        private CoveredRangeService coveredRangeService;

        @BeforeEach
        void setUpService() {
            coveredRangeService =
                    new CoveredRangeService(
                            transactionTemplate,
                            backfillStatusRepository,
                            backfillMetrics,
                            marketOpenGate,
                            usMarketOpenGate);
            // TransactionTemplate.execute(...)가 콜백을 즉시 동기 실행하도록 스텁(순수 Mockito 트랜잭션 우회 표준 패턴)
            when(transactionTemplate.execute(any()))
                    .thenAnswer(
                            invocation -> {
                                TransactionCallback<?> callback = invocation.getArgument(0);
                                return callback.doInTransaction(null);
                            });
        }

        @Test
        @DisplayName("④ kept>0 — covered_until_date가 filledUntil로 전진")
        void keptPositive_advancesCoveredUntil() {
            // Arrange
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("OVERSEAS_SHORTSALE")
                            .targetCode("__GLOBAL__")
                            .dataTable("short_sale_overseas")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .build();
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));

            Map<String, Stock> symbolMap = Map.of("AAPL", stock("AAPL"));
            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, symbolMap);
            when(client.fetch(cursor)).thenReturn(new FinraCdnFetchResult.Found(List.of("body")));
            when(loader.loadDate(eq(cursor), eq(List.of("body")), eq(symbolMap)))
                    .thenReturn(new FinraCdnDailyLoadOutcome(1, 1, 0, 0));

            // Act
            coveredRangeService.executeStep(status, filler, cursor);

            // Assert
            assertThat(status.getCoveredUntilDate()).isEqualTo(cursor);
            verify(backfillMetrics, never()).recordAnomalyFailed();
        }

        @Test
        @DisplayName("④ raw>0&&kept==0(전량 미매칭) — 미전진 + anomaly 경보")
        void rawPositiveKeptZero_blocksAdvanceAndRaisesAnomaly() {
            // Arrange
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("OVERSEAS_SHORTSALE")
                            .targetCode("__GLOBAL__")
                            .dataTable("short_sale_overseas")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .build();

            FinraCdnCoveredGapFiller filler =
                    new FinraCdnCoveredGapFiller(client, loader, Map.of());
            when(client.fetch(cursor)).thenReturn(new FinraCdnFetchResult.Found(List.of("body")));
            when(loader.loadDate(eq(cursor), eq(List.of("body")), eq(Map.of())))
                    .thenReturn(new FinraCdnDailyLoadOutcome(0, 1, 0, 1));

            // Act — kept==0 분기는 backfillStatusRepository.findById를 호출하지 않는다(전진 없음)
            coveredRangeService.executeStep(status, filler, cursor);

            // Assert
            assertThat(status.getCoveredUntilDate()).isNull();
            verify(backfillMetrics, times(1)).recordAnomalyFailed();
        }
    }
}
