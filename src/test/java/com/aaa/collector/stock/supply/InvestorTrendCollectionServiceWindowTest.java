package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SPEC-COLLECTOR-BACKFILL-001 T4 — InvestorTrendCollectionService.collectWindow 단위 테스트.
 *
 * <p>rt_cd=2(비영업일 anchor 거부) anchor-skip 보정: 최대 10회 재시도, anchor −1 calendar day/회. 소진 시 EMPTY 반환
 * (REQ-BACKFILL-016).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestorTrendCollectionService.collectWindow 단위 테스트 (T4)")
class InvestorTrendCollectionServiceWindowTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private InvestorTrendInserter inserter;
    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private InvestorTrendCollectionService service;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        session = keyLeaseRegistry.openSession();
        service =
                new InvestorTrendCollectionService(
                        stockRepository, inserter, guardedKisExecutor, keyLeaseRegistry);
    }

    private Stock stockOf(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    /**
     * 검증 통과 행 3개짜리 응답 — acmlVol/acmlTrPbmn은 양수, 순매수 계열은 0으로 단순화.
     *
     * <p>rt_cd="0" 정상 응답.
     */
    private KisInvestorTrendResponse successResponse(List<String> dates) {
        List<KisInvestorTrendResponse.InvestorTrendRow> rows =
                dates.stream().map(this::makeRow).toList();
        return new KisInvestorTrendResponse("0", "MCA00000", "정상", rows);
    }

    private KisInvestorTrendResponse rtCd2Response() {
        return new KisInvestorTrendResponse("2", "MCA00001", "비영업일", List.of());
    }

    private KisInvestorTrendResponse.InvestorTrendRow makeRow(String date) {
        return new KisInvestorTrendResponse.InvestorTrendRow(
                date,
                "1000", // frgnNtbyQty
                "500", // orgnNtbyQty
                "-200", // prsnNtbyQty (음수 정상)
                "100", // frgnNtbyTrPbmn
                "50", // orgnNtbyTrPbmn
                "-20", // prsnNtbyTrPbmn (음수 정상)
                "10000", // acmlVol (양수)
                "5000" // acmlTrPbmn (양수)
                );
    }

    @Nested
    @DisplayName("collectWindow — 정상 응답 (rt_cd=0)")
    class NormalResponse {

        @Test
        @DisplayName("rt_cd=0, 3건 응답 — 최소 거래일과 rowCount=3 반환")
        void collectWindow_rt_cd0_returnsOldest() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260611", "20260612", "20260613")));

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2026, 6, 11));
            assertThat(result.rowCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 output2 (rt_cd=0) — EMPTY 반환")
        void collectWindow_emptyResponse_returnsEmpty() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(new KisInvestorTrendResponse("0", "MCA00000", "정상", List.of()));

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            verify(inserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collectWindow — rt_cd=2 anchor-skip 보정 (REQ-BACKFILL-016)")
    class AnchorSkipCorrection {

        @Test
        @DisplayName("첫 호출 rt_cd=2, 두 번째 rt_cd=0 — anchor -1일 후 성공, rowCount=2")
        void collectWindow_rt_cd2_skipsAnchorAndRetries() throws Exception {
            // Arrange
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response())
                    .thenReturn(successResponse(List.of("20260609", "20260610")));

            // Act
            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            // Assert
            assertThat(result.rowCount()).isEqualTo(2);
            verify(guardedKisExecutor, times(2))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class));
        }

        @Test
        @DisplayName("rt_cd=2 연속 — 소진 후 EMPTY 반환 (최초 1회 + 최대 10회 재시도 = 11회 총 호출)")
        void collectWindow_rt_cd2_exhaustedRetries_returnsEmpty() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(rtCd2Response());

            BackfillWindowResult result = service.collectWindow(stockOf("005930"), session, ANCHOR);

            assertThat(result).isEqualTo(BackfillWindowResult.EMPTY);
            // 최초 호출 1회 + 재시도 10회 = 총 11회
            verify(guardedKisExecutor, times(11))
                    .execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class));
        }
    }

    @Nested
    @DisplayName("collectWindow — StockRepository 미호출 보장")
    class NoStockRepoCall {

        @Test
        @DisplayName("collectWindow — StockRepository를 호출하지 않음 (호출자가 stock을 전달)")
        void collectWindow_doesNotCallStockRepository() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            anyString(),
                            eq(KisInvestorTrendResponse.class)))
                    .thenReturn(successResponse(List.of("20260613")));

            service.collectWindow(stockOf("005930"), session, ANCHOR);

            verify(stockRepository, never()).findAllActiveTradable();
        }
    }
}
