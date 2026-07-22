package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitCollectionService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.rights.OverseasSplitCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GROUP_B 첫 probe 구간 → 실데이터 발견 → 정상 walk-back 통합 시나리오 (SPEC-COLLECTOR-BACKFILL-013 T6, AC-7/AC-9).
 *
 * <p>실제 KIS 네트워크 호출 없이(수집 서비스는 mock), {@link BackfillTerminationPolicy}·{@link
 * BackfillWindowAdvancer}는 실제 구현을 사용해 resolveAnchor → decideGroupB → persistLegacy 전체 경로를 여러 윈도우에
 * 걸쳐 재현한다. 리셋된 상폐 종목이 재실행 시 0행 probe를 거쳐 실제 데이터 구간에 도달함을 검증한다 — delisted_at 채움 여부(AC-1 vs AC-7)와
 * 무관하게 성립함을 두 시나리오로 확인한다.
 *
 * <p>실제 KIS 라이브 검증(010620 상장일 방향 실측)은 이 세션 범위 밖 — 배포 후 별도 운영 검증 대상(§ acceptance.md AC-9 note).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GROUP_B probe → 실데이터 발견 통합 시나리오 (SPEC-COLLECTOR-BACKFILL-013 AC-7/AC-9)")
class GroupBProbeToWalkBackIntegrationTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @Mock private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @Mock private ShortSaleCollectionService shortSaleService;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private CreditBalanceCollectionService creditBalanceService;
    @Mock private RevSplitCollectionService revSplitService;
    @Mock private DividendScheduleCollectionService dividendService;
    @Mock private OverseasSplitCollectionService overseasSplitService;
    @Mock private BackfillMetrics backfillMetrics;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private LeaseSession session;

    private BackfillWindowExecutor executor;

    @BeforeEach
    void setUp() {
        // 실제 판정 로직 사용(순수 로직, mock 아님) — GROUP_B probe→walk-back 전이를 진짜로 재현한다.
        BackfillTerminationPolicy terminationPolicy = new BackfillTerminationPolicy(3);
        BackfillWindowAdvancer windowAdvancer =
                new BackfillWindowAdvancer(LocalDate.of(1950, 1, 1), 10);
        executor =
                new BackfillWindowExecutor(
                        backfillStatusRepository,
                        domesticOhlcvService,
                        overseasOhlcvService,
                        shortSaleService,
                        investorTrendService,
                        creditBalanceService,
                        revSplitService,
                        dividendService,
                        overseasSplitService,
                        terminationPolicy,
                        windowAdvancer,
                        backfillMetrics,
                        transactionTemplate,
                        new BackfillProperties());
    }

    private void executeWindow(BackfillStatus status, Stock stock) throws InterruptedException {
        FetchEnvelope envelope = executor.fetchWindow(status, stock, session);
        executor.persistWindow(status, stock, envelope);
    }

    @Test
    @DisplayName(
            "AC-7/AC-9: delistedAt=null(거래정지·미sync) — 2회 0행 probe 후 실데이터 발견, last_row_count·"
                    + "last_collected_date 비-NULL로 전환")
    void probeContinuesAcrossWindows_thenTransitionsToRealData() throws InterruptedException {
        // Arrange — 실제 데이터가 없는 종목(상장일보다 훨씬 이후 구간)이지만 delistedAt은 아직 NULL
        Stock stock =
                Stock.builder()
                        .symbol("010620")
                        .nameKo("HD현대미포")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(false)
                        .listedDate(LocalDate.of(1983, 12, 20))
                        .build();
        BackfillStatus status =
                BackfillStatus.builder()
                        .targetType("STOCK")
                        .targetCode("010620")
                        .dataTable("investor_trend")
                        .status(BackfillStatusType.PENDING)
                        .build();
        when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));

        LocalDate realOldest = LocalDate.of(2025, 5, 1);
        when(investorTrendService.fetchWindow(any(), eq(stock), eq(session)))
                .thenReturn(new InvestorTrendFetch(List.of(), null, 0)) // window 1: 0행
                .thenReturn(new InvestorTrendFetch(List.of(), null, 0)) // window 2: 0행
                .thenReturn(new InvestorTrendFetch(List.of(), realOldest, 12)); // window 3: 실데이터
        when(investorTrendService.persistWindow(eq(stock), any()))
                .thenReturn(BackfillWindowResult.EMPTY)
                .thenReturn(BackfillWindowResult.EMPTY)
                .thenReturn(new BackfillWindowResult(realOldest, 12));

        // Act — 3개 윈도우 순차 실행 (같은 status/managed 인스턴스가 advance()로 누적 상태 유지)
        executeWindow(status, stock); // window 1: probe continue — AC-4(아직 수집 이력 없음, COMPLETED 아님)
        LocalDate anchorAfterWindow1 = status.getLastCollectedDate();
        assertThat(status.getStatus())
                .as("첫 probe 구간 0행은 즉시 COMPLETED 아님(전결손 위장 해소)")
                .isEqualTo(BackfillStatusType.IN_PROGRESS);

        executeWindow(status, stock); // window 2: probe continue (재개 — nextAnchor 재적용 안 함)
        assertThat(status.getLastCollectedDate())
                .as("probe 커서가 last_collected_date에 겸용되어 stride만큼 계속 과거로 전진")
                .isBefore(anchorAfterWindow1);

        executeWindow(status, stock); // window 3: 실데이터 발견 → 정상 walk-back 진입

        // Assert — AC-9: 전결손 위장 → 실제 데이터 수집으로 전환
        assertThat(status.getLastRowCount()).as("실데이터 발견 후 last_row_count 비-NULL").isPositive();
        assertThat(status.getLastCollectedDate())
                .as("실데이터 발견 후 last_collected_date가 실제 oldest로 전진")
                .isEqualTo(realOldest);
    }

    @Test
    @DisplayName("AC-1/AC-9: delistedAt 확정 종목 — 첫 창부터 delistedAt anchor로 즉시 실데이터 발견(probe 불필요)")
    void delistedStock_firstWindowFindsDataImmediately() throws InterruptedException {
        LocalDate delistedAt = LocalDate.of(2025, 12, 1);
        Stock stock =
                Stock.builder()
                        .symbol("010620")
                        .nameKo("HD현대미포")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(false)
                        .listedDate(LocalDate.of(1983, 12, 20))
                        .delistedAt(delistedAt)
                        .build();
        BackfillStatus status =
                BackfillStatus.builder()
                        .targetType("STOCK")
                        .targetCode("010620")
                        .dataTable("investor_trend")
                        .status(BackfillStatusType.PENDING)
                        .build();
        when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));

        LocalDate realOldest = delistedAt.minusDays(10);
        when(investorTrendService.fetchWindow(eq(delistedAt), eq(stock), eq(session)))
                .thenReturn(new InvestorTrendFetch(List.of(), realOldest, 8));
        when(investorTrendService.persistWindow(eq(stock), any()))
                .thenReturn(new BackfillWindowResult(realOldest, 8));

        executeWindow(status, stock);

        assertThat(status.getLastRowCount()).isNotNull().isPositive();
        assertThat(status.getLastCollectedDate()).isEqualTo(realOldest);
    }
}
