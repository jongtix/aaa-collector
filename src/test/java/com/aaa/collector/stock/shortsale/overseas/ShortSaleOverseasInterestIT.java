package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.ShortSaleOverseas;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("Short Interest 범위 폴링+소스별 UPSERT 통합 테스트")
@Tag("integration")
class ShortSaleOverseasInterestIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private FinraShortSaleClient finraClient;

    @Autowired private ShortSaleOverseasInterestCollectionService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private ShortSaleOverseasRepository repository;

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 20);

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

    private boolean hasRowFor(Stock stock, LocalDate tradeDate) {
        return repository.findAll().stream()
                .anyMatch(
                        r ->
                                r.getStock().getId().equals(stock.getId())
                                        && r.getTradeDate().equals(tradeDate));
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
        LocalDate settlement = LocalDate.of(2026, 4, 15).minusDays(SYMBOL_SEQ.get());
        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stock.getSymbol(), settlement, 134_422_787L, null)));

        // Act
        service.collectShortInterest(TODAY);

        // Assert
        ShortSaleOverseas row = rowAt(stock, settlement);
        assertThat(row)
                .satisfies(
                        r -> {
                            assertThat(r.getShortInterest()).isEqualTo(134_422_787L);
                            assertThat(r.getShortInterestDate()).isEqualTo(settlement);
                            assertThat(r.getInterestCollectedAt()).isNotNull();
                            assertThat(r.getDailyCollectedAt()).isNull();
                            assertThat(r.getShortVolume()).isEqualByComparingTo("0");
                        })
                .satisfies(
                        r -> {
                            assertThat(r.getFloatShares()).isNull();
                            assertThat(r.getSiPctFloat()).isNull();
                        });
    }

    @Test
    @DisplayName("이미 적재된 settlementDate는 revisionFlag != R이면 갱신하지 않는다 (REQ-SSO-014c)")
    void skipsExistingNonRevision() {
        // Arrange: 같은 종목×날짜 쌍이 DB에 이미 존재
        Stock stock = savedUsStock();
        LocalDate settlement = LocalDate.of(2026, 4, 14).minusDays(SYMBOL_SEQ.get());
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
        LocalDate settlement = LocalDate.of(2026, 4, 13).minusDays(SYMBOL_SEQ.get());
        LocalDateTime dailyAt = LocalDateTime.of(2026, 4, 16, 10, 0);
        repository.upsertDaily(
                stock.getId(),
                settlement,
                new BigDecimal("7000"),
                new BigDecimal("12000"),
                dailyAt,
                null,
                null);
        repository.upsertInterest(stock.getId(), settlement, 126_771_284L, LocalDateTime.now());

        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stock.getSymbol(), settlement, 140_000_000L, "R")));

        // Act
        service.collectShortInterest(TODAY);

        // Assert: interest 갱신, Daily 보존
        ShortSaleOverseas row = rowAt(stock, settlement);
        assertThat(row.getShortInterest()).isEqualTo(140_000_000L);
        assertThat(row.getShortVolume()).isEqualByComparingTo("7000");
        assertThat(row.getTotalVolume()).isEqualByComparingTo("12000");
        assertThat(row.getDailyCollectedAt()).isEqualTo(dailyAt);
    }

    @Test
    @DisplayName("미국 활성 종목만 매칭, 미매칭 심볼 제외 (REQ-SSO-003)")
    void matchesUsStocksOnly() {
        // Arrange
        Stock stock = savedUsStock();
        LocalDate settlement = LocalDate.of(2026, 4, 12).minusDays(SYMBOL_SEQ.get());
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

    @Test
    @DisplayName(
            "종목 A의 settlementDate X가 이미 적재된 상태에서 종목 B의 동일 날짜 X는 신규 적재된다 (MA-01 — 교차 종목 침묵 드롭 금지)")
    void crossStockSameDateIsNotSilentlyDropped() {
        // Arrange: 종목 A × settlementDate X 를 DB에 선적재
        Stock stockA = savedUsStock();
        Stock stockB = savedUsStock();
        LocalDate settlement = LocalDate.of(2026, 4, 15);
        repository.upsertInterest(stockA.getId(), settlement, 100_000_000L, LocalDateTime.now());

        // 이번 수집에서 종목 B × 동일 날짜 X 를 받음 (revisionFlag=null)
        when(finraClient.fetchConsolidatedShortInterest(any(), any()))
                .thenReturn(List.of(siRow(stockB.getSymbol(), settlement, 200_000_000L, null)));

        // Act
        service.collectShortInterest(TODAY);

        // Assert: 종목 B의 행이 신규 적재되어야 한다
        // (전역 날짜 판정이면 종목 A가 적재한 X 때문에 종목 B가 skip되어 RED)
        assertThat(hasRowFor(stockB, settlement))
                .as("종목 B × settlementDate=%s 는 종목 A와 무관하게 신규 적재되어야 한다", settlement)
                .isTrue();
        assertThat(rowAt(stockB, settlement).getShortInterest()).isEqualTo(200_000_000L);
        // 종목 A 기존값은 훼손되지 않음
        assertThat(rowAt(stockA, settlement).getShortInterest()).isEqualTo(100_000_000L);
    }
}
