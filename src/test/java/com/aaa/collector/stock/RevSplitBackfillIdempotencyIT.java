package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

/**
 * SPEC-COLLECTOR-BACKFILL-007 AC-7 — 종목지정 액면교체 백필 INSERT IGNORE 멱등성 통합 테스트.
 *
 * <p>Testcontainers {@code mysql:8.4}로 native INSERT IGNORE 의미를 검증한다(H2 미재현). 동일 유니크 키 {@code
 * (stock_id, event_type, event_date)} 충돌 시 행 수가 증가하지 않고, Tier-1 INSERT 권한만으로 SQL 1142(UPDATE 권한 거부)
 * 없이 멱등 적재된다(REQ-BACKFILL-098).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("RevSplit 백필 INSERT IGNORE 멱등성 통합 테스트 (AC-7)")
class RevSplitBackfillIdempotencyIT {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private RevSplitCollectionService revSplitService;
    @Autowired private StockRepository stockRepository;
    @Autowired private CorporateEventRepository corporateEventRepository;

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

    private RevSplitBackfillFetch splitFetch(Stock stock) {
        CorporateEvent split =
                CorporateEvent.builder()
                        .stock(stock)
                        .eventType(EventType.SPLIT)
                        .eventDate(LocalDate.of(2018, 5, 2))
                        .eventSubtype("분할")
                        .faceValue(100L)
                        .stockRate(new BigDecimal("50.0000"))
                        .build();
        return new RevSplitBackfillFetch(List.of(split), LocalDate.of(2018, 5, 2), 1);
    }

    @Test
    @DisplayName("AC-7: 동일 종목 백필 2회 실행 — 행 수 불변(INSERT IGNORE 멱등), SQL 1142 미발생")
    void backfillTwiceLeavesRowCountUnchanged() {
        // Arrange
        Stock stock = savedStock("005930");

        // Act — 1회차 적재
        BackfillWindowResult first = revSplitService.persistWindowForBackfill(splitFetch(stock));

        // Assert — 1행 적재, 종료 입력 rowCount=원본 행수 1
        assertThat(first.rowCount()).isEqualTo(1);
        assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);

        // Act — 2회차 재실행(동일 유니크 키)
        revSplitService.persistWindowForBackfill(splitFetch(stock));

        // Assert — INSERT IGNORE로 중복 무시, 행 수 불변
        assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
    }
}
