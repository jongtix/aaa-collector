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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
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
@Tag("integration")
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
    @DisplayName("findSymbolsByGradeIn — symbol projection 조회")
    class FindSymbolsByGradeIn {

        @Test
        @DisplayName("A·B 등급 종목 symbol 반환 — grade ASC 정렬 (A 우선)")
        void findSymbolsByGradeIn_abGrades_returnsSymbolsOrderedByGrade() {
            // Arrange
            Stock stockA = savedStock("005930");
            Stock stockB = savedStock("000660");
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(stockA)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(stockB)
                            .grade("B")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act
            List<String> result =
                    stockGradeRepository.findSymbolsByGradeIn(
                            List.of(Market.KOSPI, Market.KOSDAQ), List.of("A", "B"));

            // Assert — A 등급이 먼저, B 등급이 나중
            assertThat(result).containsExactly("005930", "000660");
        }

        @Test
        @DisplayName("해당 등급 없음 — 빈 리스트 반환")
        void findSymbolsByGradeIn_noMatchingGrades_returnsEmpty() {
            savedStock("005930"); // 등급 미부여

            List<String> result =
                    stockGradeRepository.findSymbolsByGradeIn(
                            List.of(Market.KOSPI, Market.KOSDAQ), List.of("A", "B"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("C 등급 제외 — A·B만 반환")
        void findSymbolsByGradeIn_cGradeExcluded() {
            // Arrange
            Stock stockA = savedStock("005930");
            Stock stockC = savedStock("000660");
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(stockA)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(stockC)
                            .grade("C")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act
            List<String> result =
                    stockGradeRepository.findSymbolsByGradeIn(
                            List.of(Market.KOSPI, Market.KOSDAQ), List.of("A", "B"));

            // Assert — C 등급 제외
            assertThat(result).containsExactly("005930");
        }

        @Test
        @DisplayName("aaa-infra#69 회귀 방지: 해외(NYSE) A등급 종목은 국내 조회에서 제외")
        void findSymbolsByGradeIn_excludesOverseasMarket() {
            // Arrange
            Stock kospiStock = savedStock("005930");
            Stock nyseStock =
                    stockRepository.save(
                            Stock.builder()
                                    .symbol("XOM")
                                    .nameKo("테스트종목_XOM")
                                    .market(Market.NYSE)
                                    .assetType(AssetType.STOCK)
                                    .listedDate(LocalDate.of(2015, 1, 1))
                                    .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(kospiStock)
                            .grade("B")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nyseStock)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act — 국내 시장(KOSPI/KOSDAQ)만 지정
            List<String> result =
                    stockGradeRepository.findSymbolsByGradeIn(
                            List.of(Market.KOSPI, Market.KOSDAQ), List.of("A", "B"));

            // Assert — NYSE A등급(XOM)이 국내 결과에 혼입되지 않음
            assertThat(result).containsExactly("005930");
        }
    }

    @Nested
    @DisplayName("findUsSymbolsWithMarketByGradeIn — 미국 한정 symbol+market 조회")
    class FindUsSymbolsWithMarketByGradeIn {

        private Stock savedStockWithMarket(String symbol, Market market) {
            Stock stock =
                    Stock.builder()
                            .symbol(symbol)
                            .nameKo("테스트종목_" + symbol)
                            .market(market)
                            .assetType(AssetType.STOCK)
                            .listedDate(LocalDate.of(2020, 1, 1))
                            .build();
            return stockRepository.save(stock);
        }

        @Test
        @DisplayName("KOSPI/NASDAQ/NYSE 혼합 저장 시 US 종목(A·B 등급)만 반환")
        void returnsOnlyUsSymbolsForAbGrades() {
            // Arrange
            Stock kospi = savedStockWithMarket("005930", Market.KOSPI);
            Stock nasdaq = savedStockWithMarket("AAPL", Market.NASDAQ);
            Stock nyse = savedStockWithMarket("SPY", Market.NYSE);

            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(kospi)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nasdaq)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nyse)
                            .grade("B")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act
            List<StockGradeRepository.SymbolWithMarket> result =
                    stockGradeRepository.findUsSymbolsWithMarketByGradeIn(
                            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX), List.of("A", "B"));

            // Assert — KOSPI 제외, NASDAQ+NYSE만
            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(StockGradeRepository.SymbolWithMarket::symbol)
                    .containsExactlyInAnyOrder("AAPL", "SPY");
        }

        @Test
        @DisplayName("C 등급 US 종목은 제외")
        void excludesCGradeUsSymbols() {
            // Arrange
            Stock nasdaq = savedStockWithMarket("AAPL", Market.NASDAQ);
            Stock nasdaq2 = savedStockWithMarket("MSFT", Market.NASDAQ);

            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nasdaq)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nasdaq2)
                            .grade("C")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act
            List<StockGradeRepository.SymbolWithMarket> result =
                    stockGradeRepository.findUsSymbolsWithMarketByGradeIn(
                            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX), List.of("A", "B"));

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().symbol()).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("market 필드가 올바르게 반환된다")
        void returnsCorrectMarketField() {
            // Arrange
            Stock nasdaq = savedStockWithMarket("AAPL", Market.NASDAQ);
            stockGradeRepository.save(
                    StockGrade.builder()
                            .stock(nasdaq)
                            .grade("A")
                            .gradedAt(ZonedDateTime.now(KST))
                            .build());

            // Act
            List<StockGradeRepository.SymbolWithMarket> result =
                    stockGradeRepository.findUsSymbolsWithMarketByGradeIn(
                            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX), List.of("A", "B"));

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().market()).isEqualTo(Market.NASDAQ);
        }
    }

    @Nested
    @DisplayName("save — 등급 저장")
    class SaveGrade {

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
