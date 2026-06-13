package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomesticSupplyDemandCollectionService 단위 테스트 (통합 진입점)")
class DomesticSupplyDemandCollectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    @Mock private StockRepository stockRepository;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private ShortSaleCollectionService shortSaleService;
    @Mock private CreditBalanceCollectionService creditBalanceService;

    private DomesticSupplyDemandCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new DomesticSupplyDemandCollectionService(
                        stockRepository,
                        investorTrendService,
                        shortSaleService,
                        creditBalanceService);
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

    @Nested
    @DisplayName("collectAll — 고정 순서 + 활성종목 공유 (AC-1 S1-4, AC-2 S2-1)")
    class FixedOrder {

        @Test
        @DisplayName("투자자 → 공매도 → 신용잔고 순서로 호출하며 활성종목 1회 조회를 공유")
        void callsThreeKindsInFixedOrder_sharingActiveStocks() {
            // Arrange
            List<Stock> active = List.of(stockOf("005930"), stockOf("000660"));
            when(stockRepository.findAllActive()).thenReturn(active);
            when(investorTrendService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(2, 2, 0));
            when(shortSaleService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(2, 2, 0));
            when(creditBalanceService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(2, 2, 0));

            // Act
            service.collectAll(TODAY);

            // Assert — 고정 순서 + 활성종목 1회 조회 공유
            verify(stockRepository).findAllActive();
            InOrder inOrder = inOrder(investorTrendService, shortSaleService, creditBalanceService);
            inOrder.verify(investorTrendService).collect(TODAY, active);
            inOrder.verify(shortSaleService).collect(TODAY, active);
            inOrder.verify(creditBalanceService).collect(TODAY, active);
        }

        @Test
        @DisplayName("활성종목 없음 — 3종 모두 빈 목록으로 호출(또는 미호출), 예외 없음")
        void noActiveStocks_noException() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            assertThatCode(() -> service.collectAll(TODAY)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("collectAll — 종 단위 예외 격리 (REQ-005, AC-7 S7-3)")
    class KindIsolation {

        @Test
        @DisplayName("투자자 수집 예외 — 공매도·신용잔고는 계속 진행")
        void investorThrows_othersStillRun() {
            List<Stock> active = List.of(stockOf("005930"));
            when(stockRepository.findAllActive()).thenReturn(active);
            when(investorTrendService.collect(TODAY, active))
                    .thenThrow(new RuntimeException("투자자 수집 실패"));
            when(shortSaleService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(1, 1, 0));
            when(creditBalanceService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(1, 1, 0));

            // Act & Assert — 예외 격리, 나머지 종 계속
            assertThatCode(() -> service.collectAll(TODAY)).doesNotThrowAnyException();
            verify(shortSaleService).collect(TODAY, active);
            verify(creditBalanceService).collect(TODAY, active);
        }

        @Test
        @DisplayName("공매도 수집 예외 — 신용잔고는 계속 진행, 예외 미전파")
        void shortSaleThrows_creditStillRuns() {
            List<Stock> active = List.of(stockOf("005930"));
            when(stockRepository.findAllActive()).thenReturn(active);
            when(investorTrendService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(1, 1, 0));
            when(shortSaleService.collect(TODAY, active))
                    .thenThrow(new RuntimeException("공매도 수집 실패"));
            when(creditBalanceService.collect(TODAY, active))
                    .thenReturn(new SupplyDemandResult(1, 1, 0));

            assertThatCode(() -> service.collectAll(TODAY)).doesNotThrowAnyException();
            verify(creditBalanceService).collect(TODAY, active);
        }
    }
}
