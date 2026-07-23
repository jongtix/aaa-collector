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
import com.aaa.collector.stock.supply.CreditBalanceFetch;
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

    @Test
    @DisplayName(
            "AC-6: credit_balance 전용 — listedDate 부재 + 실데이터는 2007-07-12부터만 존재하는 종목이 다회(약 100회+)"
                    + " 빈 probe 윈도우를 거쳐 GROUP_B 전역 플로어(1985-01-04) 도달 전에 실데이터 구간으로 전환됨(단일 전역 플로어"
                    + " 트레이드오프 회귀 방지, §5.5)")
    @SuppressWarnings(
            "PMD.UnitTestContainsTooManyAsserts") // 반복 루프 매 회차 불변식(null 유지·IN_PROGRESS 유지·
    // floor 미도달·과거 전진) + 종료 후 전환 검증(a)(d)을 한 테스트에서 수행 — SPEC-COLLECTOR-BACKFILL-013 AC-6
    void creditBalance_manyEmptyProbeWindows_transitionsToRealDataBeforeGlobalFloor()
            throws InterruptedException {
        // Arrange — listedDate 부재(전역 플로어 1985-01-04 적용 대상), delistedAt도 부재(WLSYNC 미sync 시나리오,
        // AC-7과 동일 전제). 실데이터는 2007-07-12까지만 존재(그 이전 구간은 진짜로 데이터 없음) — probe anchor가
        // 이 날짜보다 미래(이후)인 동안은 0행, 이 날짜 이하로 내려가면 실데이터를 발견한다.
        LocalDate dataStart = LocalDate.of(2007, 7, 12);
        LocalDate globalFloor =
                LocalDate.of(1985, 1, 4); // GROUP_B_GLOBAL_FLOOR 값 미러링(값 자체는 비공개 상수)
        Stock stock =
                Stock.builder()
                        .symbol("091810")
                        .nameKo("테스트신용잔고종목")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(false)
                        .listedDate(null)
                        .delistedAt(null)
                        .build();

        // probe 커서가 이미 실데이터 구간보다 한참 미래에서 재개 중인 상태(REQ-BACKFILL-165 커서 재개 분기,
        // last_collected_date 있음·last_row_count 아직 null)를 시뮬레이션 — 182줄 언롤 대신 Answer 기반 loop로
        // "anchor > dataStart면 0행, 그 이하면 실데이터" 규칙만 표현한다.
        LocalDate probeResumeAnchor = LocalDate.of(2025, 1, 1);
        BackfillStatus status =
                BackfillStatus.builder()
                        .targetType("STOCK")
                        .targetCode("091810")
                        .dataTable("credit_balance")
                        .status(BackfillStatusType.IN_PROGRESS)
                        .lastCollectedDate(probeResumeAnchor)
                        .lastRowCount(null)
                        .staleCount(0)
                        .build();
        when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(status));

        when(creditBalanceService.fetchWindow(any(), eq(stock), eq(session)))
                .thenAnswer(
                        invocation -> {
                            BackfillStatus resolved = invocation.getArgument(0);
                            LocalDate anchor = resolved.getLastCollectedDate();
                            return anchor.isAfter(dataStart)
                                    ? new CreditBalanceFetch(List.of(), null, 0)
                                    : new CreditBalanceFetch(List.of(), dataStart, 12);
                        });
        when(creditBalanceService.persistWindow(eq(status), eq(stock), any()))
                .thenAnswer(
                        invocation -> {
                            CreditBalanceFetch fetch = invocation.getArgument(2);
                            return fetch.rowCount() == 0
                                    ? BackfillWindowResult.EMPTY
                                    : new BackfillWindowResult(
                                            fetch.oldestTradeDate(), fetch.rowCount());
                        });

        // Act — 실데이터를 발견할 때까지 loop로 윈도우를 반복 실행(안전상한 300회, 예상 반복 수는 훨씬 적음)
        int windows = 0;
        int safetyLimit = 300;
        while (status.getLastRowCount() == null && windows < safetyLimit) {
            LocalDate anchorBefore = status.getLastCollectedDate();

            // (b) 실데이터 발견 전까지 last_row_count는 계속 null 유지
            assertThat(status.getLastRowCount()).as("실데이터 발견 전 last_row_count는 null 유지").isNull();
            // status는 floor 도달 전까지 IN_PROGRESS 유지(COMPLETED로 오종료되지 않음)
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            // (d) floor(1985-01-04)에 도달하기 전에 실데이터를 찾는다는 전제 — 루프 중 매 anchor가 floor보다 미래
            assertThat(anchorBefore).as("floor 도달 전 상태").isAfter(globalFloor);

            executeWindow(status, stock);
            windows++;

            if (status.getLastRowCount() == null) {
                // (c) last_collected_date가 매 회 과거로 전진
                assertThat(status.getLastCollectedDate())
                        .as("probe 커서가 매 윈도우 과거로 전진")
                        .isBefore(anchorBefore);
            }
        }

        // Assert — 안전 상한이 아니라 실데이터 발견으로 loop가 정상 종료됐음을 확인
        assertThat(windows)
                .as("다회(수십~백회대) 반복 후 종료 — 안전 상한 도달로 종료된 것이 아님")
                .isGreaterThan(1)
                .isLessThan(safetyLimit);
        // (a) 실데이터 도달 시 last_row_count가 non-null로 전환
        assertThat(status.getLastRowCount())
                .as("실데이터 발견 후 last_row_count 비-NULL 전환")
                .isNotNull()
                .isPositive();
        // 실데이터 구간(dataStart) 도달 확인 — floor(1985-01-04)보다 훨씬 이후 지점
        assertThat(status.getLastCollectedDate())
                .as("실데이터 발견 지점이 floor보다 미래")
                .isEqualTo(dataStart)
                .isAfter(globalFloor);
    }
}
