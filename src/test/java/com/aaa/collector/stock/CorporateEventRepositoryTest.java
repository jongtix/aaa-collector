package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("CorporateEventRepository 통합 테스트 (멱등 upsert)")
class CorporateEventRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private CorporateEventRepository corporateEventRepository;
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

    private CorporateEvent buildDividend(Stock stock, LocalDate eventDate, Long cashAmount) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .eventSubtype("결산배당")
                .payDate(LocalDate.of(2026, 8, 14))
                .stockPayDate(null)
                .oddPayDate(null)
                .cashAmount(cashAmount)
                .cashRate(new BigDecimal("0.5000"))
                .stockRate(new BigDecimal("0.0000"))
                .faceValue(100L)
                .stockKind("보통주")
                .highDividendFlag("N")
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH3-053)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("005930");

            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2026, 6, 12), 361L));

            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, event_type, event_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate eventDate = LocalDate.of(2026, 6, 12);
            long originalAmt = 500L;
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, eventDate, originalAmt));

            // Act
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, eventDate, 999_999L));

            // Assert
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CorporateEvent saved =
                    corporateEventRepository.findAll().stream()
                            .filter(e -> e.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getCashAmount()).isEqualTo(originalAmt);
        }

        @Test
        @DisplayName("서로 다른 event_date — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            Stock stock = savedStock("035420");

            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2025, 12, 28), 300L));
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2026, 6, 12), 361L));

            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }

        @Test
        @DisplayName("REQ-OVE-062: ex_dividend_date(배당락일)가 V25 컬럼에 멱등 적재된다")
        void exDividendDate_persistsViaNativeInsert() {
            // Arrange — 해외 현금배당: record_dt→event_date, div_lock_dt→ex_dividend_date
            Stock stock = savedStock("AAPL");
            LocalDate eventDate = LocalDate.of(2026, 5, 11);
            LocalDate exDividendDate = LocalDate.of(2026, 5, 11);
            CorporateEvent dividend =
                    CorporateEvent.builder()
                            .stock(stock)
                            .eventType(EventType.DIVIDEND)
                            .eventDate(eventDate)
                            .exDividendDate(exDividendDate)
                            .eventSubtype("현금배당")
                            .payDate(LocalDate.of(2026, 5, 14))
                            .build();

            // Act
            corporateEventRepository.insertIgnoreDuplicate(dividend);

            // Assert — 네이티브 INSERT가 ex_dividend_date 값을 round-trip 보존한다
            CorporateEvent saved =
                    corporateEventRepository.findAll().stream()
                            .filter(e -> e.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getExDividendDate()).isEqualTo(exDividendDate);
            assertThat(saved.getEventSubtype()).isEqualTo("현금배당");
        }

        @Test
        @DisplayName("ex_dividend_date 미지정(국내 배당) — NULL 허용, 적재 성공")
        void exDividendDate_nullableForDomestic() {
            Stock stock = savedStock("207940");

            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2026, 6, 12), 361L));

            CorporateEvent saved =
                    corporateEventRepository.findAll().stream()
                            .filter(e -> e.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getExDividendDate()).isNull();
        }

        @Test
        @DisplayName(
                "AC-MAP-4: 동일 (stock_id, event_date), event_type=DIVIDEND·SPLIT 공존 — uk 충돌 없음, 각각 독립 삽입")
        void sameStockSameDate_dividendAndSplitCoexist() {
            // Arrange
            Stock stock = savedStock("096960");
            LocalDate eventDate = LocalDate.of(2026, 6, 13);

            CorporateEvent dividend = buildDividend(stock, eventDate, 500L);
            CorporateEvent split =
                    CorporateEvent.builder()
                            .stock(stock)
                            .eventType(EventType.SPLIT)
                            .eventDate(eventDate)
                            .eventSubtype("분할")
                            .faceValue(100L) // inter_af_face_amt
                            .stockRate(new BigDecimal("5.0000")) // 분할비율 bf/af = 500/100
                            .build();

            // Act
            corporateEventRepository.insertIgnoreDuplicate(dividend);
            corporateEventRepository.insertIgnoreDuplicate(split);

            // Assert — event_type이 uk의 일부이므로 DIVIDEND·SPLIT은 충돌하지 않고 공존
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }
}
