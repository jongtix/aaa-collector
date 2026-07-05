package com.aaa.collector.stock.shortsale.overseas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleOverseasInterestCollectionService — BatchMetrics Interest 집계 (REQ-SSO-040)")
class ShortSaleOverseasInterestMetricsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 20);

    @Mock private FinraShortSaleClient finraClient;
    @Mock private StockRepository stockRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private BatchMetrics batchMetrics;

    private ShortSaleOverseasInterestCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new ShortSaleOverseasInterestCollectionService(
                        finraClient, stockRepository, shortSaleOverseasRepository, batchMetrics);
    }

    private static Stock stock(long id, String symbol) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Nested
    @DisplayName("Interest 집계 — batch 라벨 overseas-shortsale-interest")
    class InterestMetrics {

        @Test
        @DisplayName("성공/skip을 recordCompletion으로 기록한다")
        void recordsInterestCompletion() {
            // Arrange: AAPL 신규 적재, MSFT 음수(skip)
            Stock aapl = stock(1L, "AAPL");
            Stock msft = stock(2L, "MSFT");
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl, msft));
            when(shortSaleOverseasRepository.findExistingInterestPairsByStockIds(
                            any(), any(), any()))
                    .thenReturn(Map.of());
            when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                    .thenReturn(
                            List.of(
                                    new FinraConsolidatedShortInterestResponse(
                                            "AAPL",
                                            LocalDate.of(2026, 4, 15),
                                            BigDecimal.valueOf(134_422_787L),
                                            null),
                                    new FinraConsolidatedShortInterestResponse(
                                            "MSFT",
                                            LocalDate.of(2026, 4, 15),
                                            BigDecimal.valueOf(-1),
                                            null)));

            // Act
            service.collectShortInterest(TODAY);

            // Assert: attempted=2, success=1, skip=1, fail=0
            verify(batchMetrics)
                    .recordCompletion(
                            eq("overseas-shortsale-interest"), eq(2L), eq(1L), eq(0L), eq(1L));
            // REQ-SSD-009/016: MSFT 음수 거부가 파싱 거부 카운터로 계측된다(last_load 독립)
            verify(batchMetrics).recordParseRejections("overseas-shortsale-interest", 1L);
        }

        @Test
        @DisplayName("SI 수량이 소수부를 가지면(희소 조건) 침묵 skip이 아니라 파싱 거부 카운터로 신호화한다 (REQ-SSD-016)")
        void fractionalShortInterestRoutedToRejectionCounter() {
            // Arrange: AAPL의 currentShortPositionQuantity가 소수부를 가짐(FINRA가 SI에 소수 도입한 희소 조건)
            Stock aapl = stock(1L, "AAPL");
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl));
            when(shortSaleOverseasRepository.findExistingInterestPairsByStockIds(
                            any(), any(), any()))
                    .thenReturn(Map.of());
            when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                    .thenReturn(
                            List.of(
                                    new FinraConsolidatedShortInterestResponse(
                                            "AAPL",
                                            LocalDate.of(2026, 4, 15),
                                            new BigDecimal("134422787.5"),
                                            null)));

            // Act
            service.collectShortInterest(TODAY);

            // Assert: 적재 없이 skip + 파싱 거부 카운터 증가(침묵 skip 방지)
            verify(shortSaleOverseasRepository, never()).upsertInterest(any(), any(), any(), any());
            verify(batchMetrics).recordParseRejections("overseas-shortsale-interest", 1L);
        }

        @Test
        @DisplayName("빈 응답이면 0건으로 기록한다")
        void recordsZeroOnEmpty() {
            when(finraClient.fetchConsolidatedShortInterest(any(), any())).thenReturn(List.of());

            // Act
            service.collectShortInterest(TODAY);

            // Assert
            verify(batchMetrics).recordCompletion("overseas-shortsale-interest", 0L, 0L, 0L, 0L);
        }
    }
}
