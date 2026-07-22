package com.aaa.collector.market.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.backfill.CoveredWalkAnomalyKind;
import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link UsdkrwCoveredGapFiller} 단위 테스트 (SPEC-COLLECTOR-BACKFILL-011 AC-12, 부분 AC-8/-9).
 *
 * <p>순수 Mockito — 실제 {@link UsdkrwCollectionService}를 mock 체인/인서터로 감싸 {@link
 * UsdkrwCollectionService#collectDailyForBackfill(LocalDate)}(기존 백필 경로)만 재사용함을 검증한다(REQ-CVR-051, 신규
 * fetch 메서드 없음).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UsdkrwCoveredGapFiller — 단일 날짜형 갭 채우기 (SPEC-COLLECTOR-BACKFILL-011)")
class UsdkrwCoveredGapFillerTest {

    @Mock private MarketIndicatorSourceChain usdkrwChain;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private MarketIndicatorInserter marketIndicatorInserter;
    @Captor private ArgumentCaptor<List<MarketIndicator>> batchCaptor;

    private UsdkrwCollectionService usdkrwCollectionService;

    @BeforeEach
    void setUp() {
        usdkrwCollectionService =
                new UsdkrwCollectionService(
                        usdkrwChain, marketIndicatorRepository, marketIndicatorInserter);
    }

    private static MarketIndicatorRow validRow(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW,
                date,
                null,
                null,
                null,
                new BigDecimal("1380.0000"),
                "KOREAEXIM");
    }

    private static MarketIndicatorRow invalidRow(LocalDate date) {
        return new MarketIndicatorRow(
                IndicatorCode.USDKRW, date, null, null, null, null, "KOREAEXIM");
    }

    @Nested
    @DisplayName("persistStep — 기존 collectDailyForBackfill 재사용, kept/raw 매핑")
    class PersistStep {

        @Test
        @DisplayName("① 유효 행 전부 매칭 — kept=raw=행수, filledUntil=cursor")
        void allValidRows_keptEqualsRaw() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            when(usdkrwChain.fetchDaily(cursor)).thenReturn(List.of(validRow(cursor)));
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.kept()).isEqualTo(1);
            assertThat(result.raw()).isEqualTo(1);
            assertThat(result.filledUntil()).isEqualTo(cursor);
            verify(marketIndicatorInserter, times(1)).insertBatch(batchCaptor.capture());
            assertThat(batchCaptor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("검증 실패 행 포함 — raw>kept(REQ-CVR-031 anomaly 입력 조건 재현 가능)")
        void invalidRowIncluded_rawExceedsKept() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            when(usdkrwChain.fetchDaily(cursor))
                    .thenReturn(List.of(validRow(cursor), invalidRow(cursor)));
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.kept()).isEqualTo(1);
            assertThat(result.raw()).isEqualTo(2);
        }

        @Test
        @DisplayName("빈 응답(휴장일 등) — kept=raw=0, insertBatch 미호출")
        void emptyResponse_keptRawZero() {
            LocalDate cursor = LocalDate.of(2026, 7, 11); // 토요일
            when(usdkrwChain.fetchDaily(cursor)).thenReturn(List.of());
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            CoveredFillResult result = filler.persistStep(cursor);

            assertThat(result.kept()).isZero();
            assertThat(result.raw()).isZero();
            verify(marketIndicatorInserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("CoveredRangeService.executeStep 통합 — kept/raw 게이트 (TASK-003 조합)")
    class ExecuteStepIntegration {

        @Mock private BackfillMetrics backfillMetrics;
        @Mock private MarketOpenGate marketOpenGate;
        @Mock private UsMarketOpenGate usMarketOpenGate;
        @Mock private BackfillStatusRepository backfillStatusRepository;
        @Mock private TransactionTemplate transactionTemplate;
        @Mock private TransactionStatus transactionStatus;

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
            when(transactionTemplate.execute(any()))
                    .thenAnswer(
                            invocation -> {
                                TransactionCallback<?> callback = invocation.getArgument(0);
                                return callback.doInTransaction(transactionStatus);
                            });
        }

        @Test
        @DisplayName("④ kept>0 — covered_until_date가 filledUntil로 전진")
        void keptPositive_advancesCoveredUntil() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("MARKET_INDICATOR")
                            .targetCode("USDKRW")
                            .dataTable("market_indicators")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .build();
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));
            when(usdkrwChain.fetchDaily(cursor)).thenReturn(List.of(validRow(cursor)));
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            coveredRangeService.executeStep(status, filler, cursor);

            assertThat(status.getCoveredUntilDate()).isEqualTo(cursor);
        }

        @Test
        @DisplayName("④ raw>0&&kept==0 — 미전진 + anomaly 경보")
        void rawPositiveKeptZero_blocksAdvanceAndRaisesAnomaly() {
            LocalDate cursor = LocalDate.of(2026, 7, 10);
            BackfillStatus status =
                    BackfillStatus.builder()
                            .targetType("MARKET_INDICATOR")
                            .targetCode("USDKRW")
                            .dataTable("market_indicators")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .build();
            when(usdkrwChain.fetchDaily(cursor)).thenReturn(List.of(invalidRow(cursor)));
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            coveredRangeService.executeStep(status, filler, cursor);

            assertThat(status.getCoveredUntilDate()).isNull();
            verify(backfillMetrics, never()).recordAnomalyFailed();
            verify(backfillMetrics, times(1))
                    .recordCoveredWalkAnomaly(CoveredWalkAnomalyKind.ALL_REJECTED);
        }
    }
}
