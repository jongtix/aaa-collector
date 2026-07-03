package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * DailyOhlcvRepository.findRecent20DayAdtvByStockIds 통합 테스트 (REQ-GRADE4-010, 011, 012).
 *
 * <p>MySQL 8.4 window 함수(ROW_NUMBER OVER PARTITION) 사용 — H2 미지원. Testcontainers MySQL 8.4 필수.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("DailyOhlcvRepository — 최근 20거래일 ADTV 배치 조회 통합 테스트")
@Tag("integration")
class DailyOhlcvRepositoryAdtvIT {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private StockRepository stockRepository;
    @Autowired private DailyOhlcvRepository dailyOhlcvRepository;

    private Stock stock1;
    private Stock stock2;

    @BeforeEach
    void setUp() {
        dailyOhlcvRepository.deleteAll();
        stockRepository.deleteAll();

        stock1 =
                stockRepository.save(
                        Stock.builder()
                                .symbol("STOCK1")
                                .nameKo("테스트종목1")
                                .market(Market.KOSPI)
                                .assetType(AssetType.STOCK)
                                .build());
        stock2 =
                stockRepository.save(
                        Stock.builder()
                                .symbol("STOCK2")
                                .nameKo("테스트종목2")
                                .market(Market.NYSE)
                                .assetType(AssetType.STOCK)
                                .build());
    }

    private void insertOhlcv(Stock stock, LocalDate tradeDate, long tradingValue) {
        dailyOhlcvRepository.insertIgnoreDuplicate(
                stock.getId(),
                tradeDate,
                BigDecimal.valueOf(1000),
                BigDecimal.valueOf(1100),
                BigDecimal.valueOf(900),
                BigDecimal.valueOf(1050),
                100L,
                tradingValue);
    }

    private Map<Long, Double> fetchAdtv(List<Long> stockIds) {
        List<Object[]> rows = dailyOhlcvRepository.findRecent20DayAdtvByStockIds(stockIds);
        Map<Long, Double> result = new ConcurrentHashMap<>();
        for (Object[] row : rows) {
            Long stockId = ((Number) row[0]).longValue();
            Double adtv = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            result.put(stockId, adtv);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // AC-8: 최근 20거래일만 평균 (100행 보유 시 최근 20행만 평균)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-8 — 최근 20거래일만 평균 산정")
    class Recent20DaysOnly {

        @Test
        @DisplayName("100행 보유 시 최근 20행(거래대금 10000)만 평균 — 이전 80행(거래대금 1000) 제외")
        void averagesOnly20MostRecentRows() {
            // Arrange — 오래된 80행: tradingValue=1000, 최근 20행: tradingValue=10000
            LocalDate base = LocalDate.of(2024, 1, 1);
            for (int i = 0; i < 80; i++) {
                insertOhlcv(stock1, base.plusDays(i), 1_000L);
            }
            for (int i = 80; i < 100; i++) {
                insertOhlcv(stock1, base.plusDays(i), 10_000L);
            }

            // Act
            Map<Long, Double> result = fetchAdtv(List.of(stock1.getId()));

            // Assert — 최근 20행 평균 = 10000
            assertThat(result).containsKey(stock1.getId());
            assertThat(result.get(stock1.getId())).isCloseTo(10_000.0, Offset.offset(1.0));
        }
    }

    // -----------------------------------------------------------------------
    // REQ-GRADE4-011: 20일 미만 보유 시 보유 행 평균
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("REQ-GRADE4-011 — 20일 미만 보유 시 보유 행 평균")
    class LessThan20Days {

        @Test
        @DisplayName("5행 보유 시 5행 전체 평균 반환")
        void averagesAllRowsWhenLessThan20() {
            // Arrange — 5행: tradingValue=5000
            LocalDate base = LocalDate.of(2024, 6, 1);
            for (int i = 0; i < 5; i++) {
                insertOhlcv(stock1, base.plusDays(i), 5_000L);
            }

            // Act
            Map<Long, Double> result = fetchAdtv(List.of(stock1.getId()));

            // Assert — 5행 평균 = 5000
            assertThat(result).containsKey(stock1.getId());
            assertThat(result.get(stock1.getId())).isCloseTo(5_000.0, Offset.offset(1.0));
        }

        @Test
        @DisplayName("1행 보유 시 해당 행 tradingValue 반환")
        void singleRowReturnsItsValue() {
            // Arrange
            insertOhlcv(stock1, LocalDate.of(2024, 6, 1), 9_999L);

            // Act
            Map<Long, Double> result = fetchAdtv(List.of(stock1.getId()));

            // Assert
            assertThat(result).containsKey(stock1.getId());
            assertThat(result.get(stock1.getId())).isCloseTo(9_999.0, Offset.offset(1.0));
        }
    }

    // -----------------------------------------------------------------------
    // REQ-GRADE4-012: 0행 시 빈 결과
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("REQ-GRADE4-012 — 0행 시 빈 결과 (A/B 배정 안됨)")
    class ZeroRows {

        @Test
        @DisplayName("daily_ohlcv 0건 종목 → 결과 Map에 미포함")
        void stockWithNoRowsNotInResult() {
            // Arrange — stock1에 행 없음, stock2에만 행 존재
            insertOhlcv(stock2, LocalDate.of(2024, 6, 1), 1_000L);

            // Act
            Map<Long, Double> result = fetchAdtv(List.of(stock1.getId(), stock2.getId()));

            // Assert — stock1은 결과에 없음
            assertThat(result).doesNotContainKey(stock1.getId());
            assertThat(result).containsKey(stock2.getId());
        }
    }

    // -----------------------------------------------------------------------
    // 배치 조회 검증
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("배치 조회 — 복수 종목 단건 쿼리")
    class BatchQuery {

        @Test
        @DisplayName("두 종목 배치 조회 — 각 종목 독립 ADTV 반환")
        void twoStocksReturnIndependentAdtv() {
            // Arrange
            LocalDate base = LocalDate.of(2024, 6, 1);
            for (int i = 0; i < 20; i++) {
                insertOhlcv(stock1, base.plusDays(i), 1_000L); // stock1 ADTV=1000
                insertOhlcv(stock2, base.plusDays(i), 5_000L); // stock2 ADTV=5000
            }

            // Act
            Map<Long, Double> result = fetchAdtv(List.of(stock1.getId(), stock2.getId()));

            // Assert
            assertThat(result).containsKey(stock1.getId()).containsKey(stock2.getId());
            assertThat(result.get(stock1.getId())).isCloseTo(1_000.0, Offset.offset(1.0));
            assertThat(result.get(stock2.getId())).isCloseTo(5_000.0, Offset.offset(1.0));
        }

        @Test
        @DisplayName("빈 목록 조회 — 빈 결과 반환 (예외 없음)")
        void emptyStockIdsReturnsEmpty() {
            Map<Long, Double> result = fetchAdtv(List.of());
            assertThat(result).isEmpty();
        }
    }
}
