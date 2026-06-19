package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.ShortSaleOverseas;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("Short Interest 범위 폴링+소스별 UPSERT 통합 테스트")
class ShortSaleOverseasInterestIT {

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 20);

    /**
     * 메서드별 유니크 settlementDate. {@code findExistingSettlementDates}가 전역(stock 무관) 날짜 집합이라, 메서드 간 같은
     * settlementDate를 쓰면 한 메서드의 적재가 다른 메서드에서 "기존 존재"로 오판된다(@Transactional 미사용, DB 공유).
     */
    private LocalDate uniqueSettlement() {
        return LocalDate.of(2026, 4, 15).minusDays(SYMBOL_SEQ.get());
    }

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private FinraShortSaleClient finraClient;

    @Autowired private ShortSaleOverseasCollectionService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private ShortSaleOverseasRepository repository;

    private Stock savedUsStock() {
        String symbol = "US" + SYMBOL_SEQ.incrementAndGet();
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private ShortSaleOverseas rowAt(Stock stock, LocalDate tradeDate) {
        return repository.findAll().stream()
                .filter(
                        r ->
                                r.getStock().getId().equals(stock.getId())
                                        && r.getTradeDate().equals(tradeDate))
                .findFirst()
                .orElseThrow();
    }

    private static FinraConsolidatedShortInterestResponse siRow(
            String symbol, LocalDate settlementDate, long qty, String revisionFlag) {
        return new FinraConsolidatedShortInterestResponse(
                symbol, settlementDate, BigDecimal.valueOf(qty), revisionFlag);
    }

    @Test
    @DisplayName(
            "미적재 settlementDate를 trade_date=settlementDate 행으로 적재, Daily NULL (AC-INTEREST-1, AC-FLOAT-1, REQ-SSO-007)")
    void insertsNewSettlementRow() {
        // Arrange
        Stock stock = savedUsStock();
        LocalDate settlement = uniqueSettlement();
        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stock.getSymbol(), settlement, 134_422_787L, null)));

        // Act
        service.collectShortInterest(TODAY);

        // Assert
        ShortSaleOverseas row = rowAt(stock, settlement);
        assertThat(row.getShortInterest()).isEqualTo(134_422_787L);
        assertThat(row.getShortInterestDate()).isEqualTo(settlement);
        assertThat(row.getInterestCollectedAt()).isNotNull();
        assertThat(row.getDailyCollectedAt()).isNull();
        assertThat(row.getShortVolume()).isZero();
        assertThat(row.getFloatShares()).isNull();
        assertThat(row.getSiPctFloat()).isNull();
    }

    @Test
    @DisplayName("이미 적재된 settlementDate는 revisionFlag != R이면 갱신하지 않는다 (REQ-SSO-014c)")
    void skipsExistingNonRevision() {
        // Arrange: 같은 settlementDate를 기존 잔고로 선적재
        Stock stock = savedUsStock();
        LocalDate settlement = uniqueSettlement();
        repository.upsertInterest(stock.getId(), settlement, 100_000_000L, LocalDateTime.now());
        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stock.getSymbol(), settlement, 999_999_999L, null)));

        // Act
        service.collectShortInterest(TODAY);

        // Assert: 기존값 보존(갱신 안 함)
        ShortSaleOverseas row = rowAt(stock, settlement);
        assertThat(row.getShortInterest()).isEqualTo(100_000_000L);
    }

    @Test
    @DisplayName("이미 적재된 settlementDate라도 revisionFlag=R이면 interest 갱신, Daily 불변 (REQ-SSO-014b)")
    void updatesExistingOnRevision() {
        // Arrange: Daily+SI가 채워진 기존 행
        Stock stock = savedUsStock();
        LocalDate settlement = uniqueSettlement();
        LocalDateTime dailyAt = LocalDateTime.of(2026, 4, 16, 10, 0);
        repository.upsertDaily(stock.getId(), settlement, 7000L, 12000L, dailyAt, null, null);
        repository.upsertInterest(stock.getId(), settlement, 126_771_284L, LocalDateTime.now());

        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stock.getSymbol(), settlement, 140_000_000L, "R")));

        // Act
        service.collectShortInterest(TODAY);

        // Assert: interest 갱신, Daily 보존
        ShortSaleOverseas row = rowAt(stock, settlement);
        assertThat(row.getShortInterest()).isEqualTo(140_000_000L);
        assertThat(row.getShortVolume()).isEqualTo(7000L);
        assertThat(row.getTotalVolume()).isEqualTo(12000L);
        assertThat(row.getDailyCollectedAt()).isEqualTo(dailyAt);
    }

    @Test
    @DisplayName("미국 활성 종목만 매칭, 미매칭 심볼 제외 (REQ-SSO-003)")
    void matchesUsStocksOnly() {
        // Arrange
        Stock stock = savedUsStock();
        LocalDate settlement = uniqueSettlement();
        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(
                        List.of(
                                siRow(stock.getSymbol(), settlement, 134_422_787L, null),
                                siRow("ZZZZ", settlement, 999L, null)));

        // Act
        service.collectShortInterest(TODAY);

        // Assert: 매칭 종목만 적재, ZZZZ는 미적재
        assertThat(rowAt(stock, settlement).getShortInterest()).isEqualTo(134_422_787L);
        boolean hasZzzz =
                repository.findAll().stream()
                        .anyMatch(
                                r -> r.getShortInterest() != null && r.getShortInterest() == 999L);
        assertThat(hasZzzz).isFalse();
    }

    @Test
    @DisplayName("빈 응답이면 적재 0건·예외 없음 (AC-EMPTY-1, REQ-SSO-020)")
    void emptyResponseSkips() {
        Stock stock = savedUsStock();
        when(finraClient.fetchConsolidatedShortInterest(any(), any())).thenReturn(List.of());

        // Act & Assert: 예외 없이 0건
        service.collectShortInterest(TODAY);
        assertThat(
                        repository.findAll().stream()
                                .filter(r -> r.getStock().getId().equals(stock.getId())))
                .isEmpty();
    }
}
