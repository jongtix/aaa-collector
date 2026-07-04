package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.ShortSaleOverseas;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
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

/**
 * 백필 UPSERT 계약 IT (SPEC-COLLECTOR-BACKFILL-008 T5, AC-BF-18/-19/-20/-21).
 *
 * <p>T4 오케스트레이터가 도입한 {@code upsertDaily(..., null, null)} 백필 적재 계약을 실 MySQL(Testcontainers {@code
 * mysql:8.4})로 검증한다. 재사용 자산({@link ShortSaleOverseasRepository#upsertDaily})의 백필 사용 형태에 대한 특성/계약
 * 검증이며, 본 IT는 신규 프로덕션 코드를 도입하지 않는다(H2 미재현 — COALESCE·SET 절 소스별 갱신은 Testcontainers 필수).
 *
 * <p><b>M2-T1 격리 분류 — 싱글턴 공유 제외(전용 컨테이너)</b>: 이 클래스는 {@link
 * com.aaa.collector.support.SharedMySqlContainer} 공유 대상에서 의도적으로 제외하고 전용 컨테이너를 쓴다. {@link
 * ShortSaleOverseasRepository#upsertDaily}/{@link ShortSaleOverseasRepository#upsertInterest}는
 * {@code clearAutomatically}가 없는 {@code @Modifying} 네이티브 쿼리라, {@code @Transactional}로 하나의 트랜잭션(=하나의
 * 영속성 컨텍스트)에 묶이면 이전 조회로 캐시된 엔티티가 이후 네이티브 UPDATE를 반영하지 못해 스테일 리드를 일으킨다(SPEC-COLLECTOR-DBGRANT-003
 * M2-T1 실측). {@link #SYMBOL_SEQ}로 테스트마다 고유 심볼을 발급해 다른 클래스와의 충돌은 없으나, 트랜잭션 롤백 격리 자체가 불가능하므로 공유 싱글턴
 * 대신 전용 컨테이너로 매 클래스 신선한 스키마를 보장한다.
 *
 * @see <a
 *     href="https://testcontainers.com/guides/testcontainers-container-lifecycle/">Testcontainers —
 *     테스트가 전역 상태를 변경하면 공유하지 말 것</a>
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("백필 UPSERT 계약 — SI 컬럼 보존·멱등성·float/si NULL")
@Tag("integration")
class FinraCdnShortSaleBackfillUpsertIT {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private ShortSaleOverseasRepository repository;
    @Autowired private StockRepository stockRepository;

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();

    private Stock savedUsStock() {
        String symbol = "BF" + SYMBOL_SEQ.incrementAndGet();
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2010, 1, 1))
                        .build());
    }

    private ShortSaleOverseas rowAt(Long stockId, LocalDate tradeDate) {
        return repository.findAll().stream()
                .filter(
                        r ->
                                r.getStock().getId().equals(stockId)
                                        && r.getTradeDate().equals(tradeDate))
                .findFirst()
                .orElseThrow();
    }

    private long rowCountFor(Long stockId) {
        return repository.findAll().stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .count();
    }

    @Test
    @DisplayName("Daily UPSERT 멱등성: 재적재 시 행 수 불변·short/total/daily_collected_at 갱신 (AC-BF-19)")
    void idempotentReloadUpdatesDailyColumnsWithoutNewRow() {
        // Arrange
        Stock stock = savedUsStock();
        LocalDate tradeDate = LocalDate.of(2013, 1, 2);
        LocalDateTime firstCollectedAt = LocalDateTime.of(2026, 7, 1, 5, 0);
        repository.upsertDaily(stock.getId(), tradeDate, 100L, 1000L, firstCollectedAt, null, null);

        // Act — 동일 (stock_id, trade_date) 재적재(값 변경)
        LocalDateTime secondCollectedAt = LocalDateTime.of(2026, 7, 2, 5, 0);
        repository.upsertDaily(
                stock.getId(), tradeDate, 150L, 1500L, secondCollectedAt, null, null);

        // Assert
        assertThat(rowCountFor(stock.getId())).isEqualTo(1);
        ShortSaleOverseas row = rowAt(stock.getId(), tradeDate);
        assertThat(row.getShortVolume()).isEqualTo(150L);
        assertThat(row.getTotalVolume()).isEqualTo(1500L);
        assertThat(row.getDailyCollectedAt()).isEqualTo(secondCollectedAt);
    }

    @Test
    @DisplayName(
            "SI 보존(aaa-infra#59): daily_collected_at NULL + SI 채워진 행에 백필 적재 시 Daily만 갱신·SI 보존·DELETE 없음"
                    + " (AC-BF-20)")
    void preservesExistingShortInterestColumns() {
        // Arrange — Short Interest만 채워진 기존 행(Daily 미수집, daily_collected_at NULL)
        Stock stock = savedUsStock();
        LocalDate tradeDate = LocalDate.of(2013, 1, 3);
        LocalDateTime interestCollectedAt = LocalDateTime.of(2026, 6, 1, 9, 0);
        repository.upsertInterest(stock.getId(), tradeDate, 500_000L, interestCollectedAt);
        ShortSaleOverseas before = rowAt(stock.getId(), tradeDate);
        assertThat(before.getDailyCollectedAt()).isNull();

        // Act — 백필이 Daily 3컬럼을 interest 파라미터 null로 덮어 채움
        LocalDateTime dailyCollectedAt = LocalDateTime.of(2026, 7, 3, 5, 0);
        repository.upsertDaily(
                stock.getId(), tradeDate, 7_000L, 12_000L, dailyCollectedAt, null, null);

        // Assert — Daily 갱신, 행 수 불변(DELETE 없음)
        assertThat(rowCountFor(stock.getId())).isEqualTo(1);
        ShortSaleOverseas after = rowAt(stock.getId(), tradeDate);
        assertThat(after)
                .satisfies(
                        r -> {
                            assertThat(r.getShortVolume()).isEqualTo(7_000L);
                            assertThat(r.getTotalVolume()).isEqualTo(12_000L);
                            assertThat(r.getDailyCollectedAt()).isEqualTo(dailyCollectedAt);
                        })
                .satisfies(
                        r -> {
                            // SI 보존
                            assertThat(r.getShortInterest()).isEqualTo(500_000L);
                            assertThat(r.getShortInterestDate()).isEqualTo(tradeDate);
                            assertThat(r.getInterestCollectedAt()).isEqualTo(interestCollectedAt);
                        });
    }

    @Test
    @DisplayName("float_shares/si_pct_float는 백필 적재 후에도 항상 NULL로 유지된다 (AC-BF-21)")
    void leavesFloatAndSiPctFloatNull() {
        // Arrange
        Stock stock = savedUsStock();
        LocalDate tradeDate = LocalDate.of(2013, 1, 4);

        // Act
        repository.upsertDaily(
                stock.getId(), tradeDate, 10L, 100L, LocalDateTime.now(), null, null);

        // Assert
        ShortSaleOverseas row = rowAt(stock.getId(), tradeDate);
        assertThat(row.getFloatShares()).isNull();
        assertThat(row.getSiPctFloat()).isNull();
    }

    @Test
    @DisplayName("앵커 재순회 멱등: 동일 값 재기록 시 기존 행 값이 변하지 않는다 (AC-BF-18 실 DB 확인)")
    void reprocessingSameValuesLeavesRowUnchanged() {
        // Arrange — 신규 종목 편입 리셋으로 인한 전역 재순회를 시뮬레이션: 이미 적재된 과거 행을 동일 값으로 재기록
        Stock stock = savedUsStock();
        LocalDate tradeDate = LocalDate.of(2012, 5, 10);
        LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 1, 5, 0);
        repository.upsertDaily(stock.getId(), tradeDate, 42L, 420L, collectedAt, null, null);

        // Act — 같은 값으로 재기록(재순회)
        repository.upsertDaily(stock.getId(), tradeDate, 42L, 420L, collectedAt, null, null);

        // Assert — 값 불변, 행 수 불변
        assertThat(rowCountFor(stock.getId())).isEqualTo(1);
        ShortSaleOverseas row = rowAt(stock.getId(), tradeDate);
        assertThat(row.getShortVolume()).isEqualTo(42L);
        assertThat(row.getTotalVolume()).isEqualTo(420L);
        assertThat(row.getDailyCollectedAt()).isEqualTo(collectedAt);
    }

    @Test
    @DisplayName("서로 다른 종목의 동일 거래일은 독립적으로 적재된다 (교차 종목 침묵 드롭 방지)")
    void crossStockSameDateBothPersisted() {
        // Arrange
        Stock stockA = savedUsStock();
        Stock stockB = savedUsStock();
        LocalDate tradeDate = LocalDate.of(2011, 3, 1);

        // Act
        repository.upsertDaily(stockA.getId(), tradeDate, 1L, 10L, LocalDateTime.now(), null, null);
        repository.upsertDaily(stockB.getId(), tradeDate, 2L, 20L, LocalDateTime.now(), null, null);

        // Assert
        assertThat(List.of(rowAt(stockA.getId(), tradeDate), rowAt(stockB.getId(), tradeDate)))
                .extracting(ShortSaleOverseas::getShortVolume)
                .containsExactlyInAnyOrder(1L, 2L);
    }
}
