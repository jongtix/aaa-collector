package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
@DisplayName("StockGradeRepository 통합 테스트")
class StockGradeRepositoryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private StockGradeRepository stockGradeRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        return stockRepository.save(stock);
    }

    @Nested
    @DisplayName("findByStock — 종목으로 등급 조회")
    class FindByStock {

        @Test
        @DisplayName("등급이 없는 종목 — Optional.empty() 반환")
        void findByStock_noGradeExists_returnsEmpty() {
            Stock stock = savedStock("005930");
            Optional<StockGrade> result = stockGradeRepository.findByStock(stock);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("등급이 있는 종목 — Optional에 StockGrade 반환")
        void findByStock_gradeExists_returnsStockGrade() {
            // Arrange
            Stock stock = savedStock("000660");
            StockGrade grade =
                    StockGrade.builder()
                            .stock(stock)
                            .grade("A")
                            .gradedAt(ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST))
                            .build();
            stockGradeRepository.save(grade);

            // Act
            Optional<StockGrade> result = stockGradeRepository.findByStock(stock);

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getGrade()).isEqualTo("A");
        }
    }

    @Nested
    @DisplayName("save — 등급 저장")
    class Save {

        @Test
        @DisplayName("신규 등급 저장 — ID 할당됨")
        void save_newGrade_assignsId() {
            Stock stock = savedStock("035420");
            StockGrade grade =
                    StockGrade.builder()
                            .stock(stock)
                            .grade("B")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build();

            StockGrade saved = stockGradeRepository.save(grade);

            assertThat(saved.getId()).isNotNull();
        }

        @Test
        @DisplayName("같은 종목 두 번 저장 — unique constraint로 두 번째는 예외 발생")
        void save_duplicateStock_throwsException() {
            Stock stock = savedStock("AAPL");
            stockGradeRepository.saveAndFlush(
                    StockGrade.builder()
                            .stock(stock)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            assertThrows(
                    DataIntegrityViolationException.class,
                    () ->
                            stockGradeRepository.saveAndFlush(
                                    StockGrade.builder()
                                            .stock(stock)
                                            .grade("B")
                                            .gradedAt(ZonedDateTime.now(KST))
                                            .build()));
        }
    }
}
