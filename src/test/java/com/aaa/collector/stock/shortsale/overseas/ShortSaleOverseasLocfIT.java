package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Tag;
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
@DisplayName("Daily 경로 LOCF forward 병합 통합 테스트")
@Tag("integration")
class ShortSaleOverseasLocfIT {

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 1, 6);

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private FinraShortSaleClient finraClient;

    @Autowired private ShortSaleOverseasDailyCollectionService service;
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

    private ShortSaleOverseas dailyRow(Stock stock) {
        return repository.findAll().stream()
                .filter(
                        r ->
                                r.getStock().getId().equals(stock.getId())
                                        && r.getTradeDate().equals(TRADE_DATE))
                .findFirst()
                .orElseThrow();
    }

    private static FinraRegShoDailyResponse finraDaily(String symbol) {
        return new FinraRegShoDailyResponse(
                TRADE_DATE, symbol, BigDecimal.valueOf(100), BigDecimal.valueOf(200));
    }

    @Test
    @DisplayName("forward 매칭이 있으면 Daily 행에 최신 short_interest를 동반 적재한다 (AC-LOCF-1)")
    void copiesForwardInterest() {
        // Arrange: SI-origin 행(2026-01-02, 잔고 200) 선적재 — forward 출처 후보
        Stock stock = savedUsStock();
        repository.upsertInterest(
                stock.getId(), LocalDate.of(2026, 1, 2), 200L, LocalDateTime.now());
        when(finraClient.fetchRegShoDaily(TRADE_DATE))
                .thenReturn(List.of(finraDaily(stock.getSymbol())));

        // Act
        service.collectDaily(TRADE_DATE);

        // Assert: Daily 행(2026-01-06)에 forward 잔고(2026-01-02, 200) 복사
        ShortSaleOverseas row = dailyRow(stock);
        assertThat(row.getShortVolume()).isEqualTo(100L);
        assertThat(row.getShortInterest()).isEqualTo(200L);
        assertThat(row.getShortInterestDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(row.getDailyCollectedAt()).isNotNull();
    }

    @Test
    @DisplayName("forward 매칭이 없으면 short_interest를 적재하지 않는다 (AC-LOCF-2)")
    void noForwardMatchLeavesInterestNull() {
        // Arrange: 잔고 행 없음
        Stock stock = savedUsStock();
        when(finraClient.fetchRegShoDaily(TRADE_DATE))
                .thenReturn(List.of(finraDaily(stock.getSymbol())));

        // Act
        service.collectDaily(TRADE_DATE);

        // Assert
        ShortSaleOverseas row = dailyRow(stock);
        assertThat(row.getShortVolume()).isEqualTo(100L);
        assertThat(row.getShortInterest()).isNull();
        assertThat(row.getShortInterestDate()).isNull();
    }

    @Test
    @DisplayName("미래 settlementDate 잔고는 forward 출처가 되지 않는다 (short_interest_date > trade_date)")
    void excludesFutureSettlement() {
        // Arrange: trade_date(01-06)보다 미래(01-31) 잔고만 존재
        Stock stock = savedUsStock();
        repository.upsertInterest(
                stock.getId(), LocalDate.of(2026, 1, 31), 500L, LocalDateTime.now());
        when(finraClient.fetchRegShoDaily(TRADE_DATE))
                .thenReturn(List.of(finraDaily(stock.getSymbol())));

        // Act
        service.collectDaily(TRADE_DATE);

        // Assert: 01-31 행은 미래라 forward 제외 → Daily 행 interest NULL
        ShortSaleOverseas daily = dailyRow(stock);
        assertThat(daily.getShortInterest()).isNull();
    }
}
