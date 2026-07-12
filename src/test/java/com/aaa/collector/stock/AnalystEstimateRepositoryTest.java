package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("AnalystEstimateRepository 통합 테스트 (멱등 upsert, REQ-BATCH4-033)")
@Tag("integration")
class AnalystEstimateRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private AnalystEstimateRepository analystEstimateRepository;
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

    private void insert(
            Stock stock, LocalDate tradeDate, String institutionName, long targetPrice) {
        analystEstimateRepository.insertIgnoreDuplicate(
                AnalystEstimate.builder()
                        .stock(stock)
                        .tradeDate(tradeDate)
                        .institutionName(institutionName)
                        .opinion("매수")
                        .opinionCode("2")
                        .prevOpinion("중립")
                        .prevOpinionCode("3")
                        .targetPrice(targetPrice)
                        .prevClose(82_000L)
                        .gapNDay(new BigDecimal("13000"))
                        .gapRateNDay(new BigDecimal("15.85"))
                        .gapFutures(new BigDecimal("500"))
                        .gapRateFutures(new BigDecimal("0.61"))
                        .build());
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (AC-OPN-4)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("OPN_005930");

            insert(stock, LocalDate.of(2026, 6, 12), "OO증권", 95_000L);

            assertThat(analystEstimateRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date, institution_name) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("OPN_000660");
            LocalDate date = LocalDate.of(2026, 6, 12);
            insert(stock, date, "OO증권", 95_000L);

            // Act — 동일 키, 다른 값으로 재삽입
            insert(stock, date, "OO증권", 111_111L);

            // Assert — 행 수 1 유지 + 최초 값 보존
            assertThat(analystEstimateRepository.countByStockId(stock.getId())).isEqualTo(1L);
            AnalystEstimate saved = findByStock(stock.getId());
            assertThat(saved.getTargetPrice()).isEqualTo(95_000L);
        }

        @Test
        @DisplayName("빈 institution_name 행 — DEFAULT ''로 저장, uk 구성요소로 동작 (AC-OPN-3)")
        void emptyInstitutionName_storedAndUkComponent() {
            Stock stock = savedStock("OPN_035420");
            LocalDate date = LocalDate.of(2026, 6, 12);

            // 빈 회원사명 + 다른 회원사명은 서로 다른 uk → 독립 저장
            insert(stock, date, "", 95_000L);
            insert(stock, date, "OO증권", 96_000L);

            assertThat(analystEstimateRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }

        @Test
        @DisplayName("동일 키 빈 institution_name 재삽입 — 행 수 불변(멱등)")
        void emptyInstitutionName_idempotent() {
            Stock stock = savedStock("OPN_051910");
            LocalDate date = LocalDate.of(2026, 6, 12);

            insert(stock, date, "", 95_000L);
            insert(stock, date, "", 96_000L);

            assertThat(analystEstimateRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }
    }

    private AnalystEstimate findByStock(Long stockId) {
        List<AnalystEstimate> rows = analystEstimateRepository.findAll();
        return rows.stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .findFirst()
                .orElseThrow();
    }
}
