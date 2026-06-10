package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("DailyOhlcvRepository 통합 테스트")
class DailyOhlcvRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private DailyOhlcvRepository dailyOhlcvRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private DailyOhlcv ohlcv(Stock stock, LocalDate date) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(date)
                .openPrice(new BigDecimal("74000.0000"))
                .highPrice(new BigDecimal("76000.0000"))
                .lowPrice(new BigDecimal("73000.0000"))
                .closePrice(new BigDecimal("75000.0000"))
                .volume(1_000_000L)
                .tradingValue(75_000_000_000L)
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH-031, -032)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void insertIgnoreDuplicate_newRow_insertsOne() {
            // Arrange
            Stock stock = savedStock("005930");
            DailyOhlcv row = ohlcv(stock, LocalDate.of(2026, 6, 5));

            // Act
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 5),
                    row.getOpenPrice(),
                    row.getHighPrice(),
                    row.getLowPrice(),
                    row.getClosePrice(),
                    row.getVolume(),
                    row.getTradingValue());
            dailyOhlcvRepository.flush();

            // Assert
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 삽입 — 행 수 증가 없음")
        void insertIgnoreDuplicate_duplicate_rowCountUnchanged() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate date = LocalDate.of(2026, 6, 5);

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    new BigDecimal("75000.0000"),
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 동일 키로 다시 삽입
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("80000.0000"),
                    new BigDecimal("82000.0000"),
                    new BigDecimal("79000.0000"),
                    new BigDecimal("81000.0000"),
                    2_000_000L,
                    160_000_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 행 수는 1 유지, UPDATE 미발생이므로 최초 값 보존
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 — 기존 행 값이 UPDATE되지 않음")
        void insertIgnoreDuplicate_duplicate_originalValuePreserved() {
            // Arrange
            Stock stock = savedStock("035420");
            LocalDate date = LocalDate.of(2026, 6, 5);
            BigDecimal originalClose = new BigDecimal("75000.0000");

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    originalClose,
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 다른 값으로 재삽입 시도
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("90000.0000"),
                    new BigDecimal("92000.0000"),
                    new BigDecimal("89000.0000"),
                    new BigDecimal("91000.0000"),
                    5_000_000L,
                    455_000_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 최초 종가가 그대로 유지
            List<DailyOhlcv> rows = dailyOhlcvRepository.findAll();
            DailyOhlcv saved =
                    rows.stream()
                            .filter(r -> r.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getClosePrice()).isEqualByComparingTo(originalClose);
        }

        @Test
        @DisplayName("5거래일 증분 — 직전 누락분 회복: 기존 4일 + 신규 1일, 기존 값 불변 (AC-4 S4-3)")
        void insertIgnoreDuplicate_priorDayMissing_recoversOnNextRun() {
            // Arrange — 최초 수집: 4 거래일 삽입
            Stock stock = savedStock("096770");
            LocalDate day1 = LocalDate.of(2026, 5, 26);
            LocalDate day2 = LocalDate.of(2026, 5, 27);
            LocalDate day3 = LocalDate.of(2026, 5, 28);
            LocalDate day4 = LocalDate.of(2026, 5, 29);
            LocalDate day5 = LocalDate.of(2026, 6, 2); // 신규 거래일

            BigDecimal originalCloseDay1 = new BigDecimal("75000.0000");

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day1,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    originalCloseDay1,
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day2,
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    1_100_000L,
                    83_600_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day3,
                    new BigDecimal("76000.0000"),
                    new BigDecimal("78000.0000"),
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    1_200_000L,
                    92_400_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day4,
                    new BigDecimal("77000.0000"),
                    new BigDecimal("79000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("78000.0000"),
                    1_300_000L,
                    101_400_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 다음 수집 실행: 동일 4일 재삽입 + 신규 1일 추가 (5거래일 윈도우)
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day1,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day2,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day3,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day4,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day5,
                    new BigDecimal("78000.0000"),
                    new BigDecimal("80000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("79000.0000"),
                    1_400_000L,
                    110_600_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 총 5행(신규 1일만 추가), 기존 day1 종가 불변, day5 존재
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(5L);

            List<DailyOhlcv> rows = dailyOhlcvRepository.findAll();
            DailyOhlcv day1Row =
                    rows.stream()
                            .filter(
                                    r ->
                                            r.getStock().getId().equals(stock.getId())
                                                    && r.getTradeDate().equals(day1))
                            .findFirst()
                            .orElseThrow();
            assertThat(day1Row.getClosePrice()).isEqualByComparingTo(originalCloseDay1);

            boolean day5Present =
                    rows.stream()
                            .anyMatch(
                                    r ->
                                            r.getStock().getId().equals(stock.getId())
                                                    && r.getTradeDate().equals(day5));
            assertThat(day5Present).isTrue();
        }

        @Test
        @DisplayName("서로 다른 (stock_id, trade_date) — 각각 독립 삽입됨")
        void insertIgnoreDuplicate_differentDates_insertsDistinctRows() {
            // Arrange
            Stock stock = savedStock("028260");

            // Act
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 4),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    new BigDecimal("75000.0000"),
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 5),
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    1_200_000L,
                    91_200_000_000L);
            dailyOhlcvRepository.flush();

            // Assert
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(2L);
        }
    }
}
