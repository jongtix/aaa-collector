package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.ShortSaleInserter;
import com.aaa.collector.support.SharedMySqlContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ShortSaleDomesticRepository} Track 1/Track 2 정정 조회·UPDATE 통합 테스트
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-031~039, plan.md §M4).
 *
 * <p>M8(V45 {@code vol_rate_verified_at} 컬럼 마이그레이션)이 적용되어 이 클래스가 활성화됐다 — 이전에는 {@link
 * ShortSaleDomestic} 엔티티가 매핑하는 이 컬럼이 실제 스키마에 없어 Hibernate {@code ddl-auto=validate}가 컨텍스트 기동 시점에
 * {@code SchemaManagementException}을 던졌다(2026-08-06 실측 확인, plan.md §M8 참조).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName(
        "ShortSaleDomesticRepository Track1/Track2 정정 조회·UPDATE 통합 테스트"
                + " (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 M4)")
@Tag("integration")
class ShortSaleDomesticVolRateCorrectionRepositoryIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조).
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @Autowired private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private DataSource dataSource;

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

    /** {@code acml_vol}까지 실 INSERT 경로({@link ShortSaleInserter})로 삽입 — Track 판별 조건을 그대로 재현한다. */
    private ShortSaleDomestic insertedRow(
            Stock stock, LocalDate date, long qty, BigDecimal rate, Long acmlVol) {
        ShortSaleDomestic entity =
                ShortSaleDomestic.builder()
                        .stock(stock)
                        .tradeDate(date)
                        .shortSellQty(qty)
                        .shortSellVolRate(rate)
                        .shortSellAmt(0L)
                        .shortSellAmtRate(BigDecimal.ZERO)
                        .shortSellAccQty(0L)
                        .shortSellAccQtyRate(BigDecimal.ZERO)
                        .shortSellAccAmt(0L)
                        .shortSellAccAmtRate(BigDecimal.ZERO)
                        .acmlVol(acmlVol)
                        .build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchMetrics metrics =
                new BatchMetrics(
                        registry, Clock.systemDefaultZone(), mock(BatchLastLoadRepository.class));
        ShortSaleInserter inserter =
                new ShortSaleInserter(
                        new JdbcTemplate(dataSource), metrics, new WatermarkMetrics(registry));
        inserter.insertBatch(List.of(entity));
        return findByStockAndDate(stock, date);
    }

    private ShortSaleDomestic findByStockAndDate(Stock stock, LocalDate date) {
        return shortSaleDomesticRepository.findAll().stream()
                .filter(
                        r ->
                                r.getStock().getId().equals(stock.getId())
                                        && r.getTradeDate().equals(date))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("Track 1 대상 조회·원자적 UPDATE (REQ-SSVC-031, -002, -036)")
    class Track1 {

        @Test
        @DisplayName("acml_vol IS NULL 행만 대상 조회 — 이미 채워진 행은 제외")
        void findTrack1LegacyBacklogBatch_onlyReturnsNullAcmlVol() {
            Stock stock = savedStock("005930");
            insertedRow(stock, LocalDate.of(2026, 6, 5), 10_000L, new BigDecimal("1.00"), null);
            insertedRow(stock, LocalDate.of(2026, 6, 6), 10_000L, new BigDecimal("1.00"), 500_000L);

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                            0L, PageRequest.of(0, 10));

            assertThat(batch).hasSize(1);
            assertThat(batch.getFirst().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        }

        @Test
        @DisplayName("updateTrack1Correction — acml_vol·rate·verified_at 3컬럼 원자적 반영, 이후 대상 조회에서 제외")
        void updateTrack1Correction_persistsAllThreeColumns_thenExcludedFromQuery() {
            Stock stock = savedStock("007120");
            LocalDate date = LocalDate.of(2026, 7, 1);
            ShortSaleDomestic row = insertedRow(stock, date, 2_000L, new BigDecimal("0.20"), null);
            LocalDateTime verifiedAt = LocalDateTime.of(2026, 8, 6, 12, 0);

            int updated =
                    shortSaleDomesticRepository.updateTrack1Correction(
                            row.getId(), 1_000_000L, new BigDecimal("0.40"), verifiedAt);

            assertThat(updated).isEqualTo(1);
            ShortSaleDomestic reloaded = findByStockAndDate(stock, date);
            assertThat(reloaded.getAcmlVol()).isEqualTo(1_000_000L);
            assertThat(reloaded.getShortSellVolRate()).isEqualByComparingTo("0.40");
            assertThat(reloaded.getVolRateVerifiedAt()).isEqualTo(verifiedAt);
            assertThat(
                            shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                                    0L, PageRequest.of(0, 10)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Track 2 대상 조회·원자적 UPDATE (REQ-SSVC-034, -035)")
    class Track2 {

        @Test
        @DisplayName("acml_vol IS NOT NULL AND vol_rate_verified_at IS NULL 행만 대상 조회")
        void findTrack2PendingVerificationBatch_onlyReturnsUnverifiedFilledRows() {
            Stock stock = savedStock("097230");
            insertedRow(stock, LocalDate.of(2026, 6, 5), 10_000L, new BigDecimal("1.00"), null);
            insertedRow(stock, LocalDate.of(2026, 6, 6), 10_000L, new BigDecimal("1.00"), 500_000L);

            List<ShortSaleDomestic> batch =
                    shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                            0L, PageRequest.of(0, 10));

            assertThat(batch).hasSize(1);
            assertThat(batch.getFirst().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 6));
        }

        @Test
        @DisplayName("updateTrack2Verification — rate·verified_at 반영, acml_vol은 무변경, 이후 대상 조회에서 제외")
        void updateTrack2Verification_persistsRateAndVerifiedAt_leavesAcmlVolUntouched() {
            Stock stock = savedStock("042660");
            LocalDate date = LocalDate.of(2026, 6, 10);
            ShortSaleDomestic row =
                    insertedRow(stock, date, 10_000L, new BigDecimal("10.30"), 1_000_000L);
            LocalDateTime verifiedAt = LocalDateTime.of(2026, 8, 6, 13, 0);

            int updated =
                    shortSaleDomesticRepository.updateTrack2Verification(
                            row.getId(), new BigDecimal("1.00"), verifiedAt);

            assertThat(updated).isEqualTo(1);
            ShortSaleDomestic reloaded = findByStockAndDate(stock, date);
            assertThat(reloaded.getAcmlVol()).isEqualTo(1_000_000L);
            assertThat(reloaded.getShortSellVolRate()).isEqualByComparingTo("1.00");
            assertThat(reloaded.getVolRateVerifiedAt()).isEqualTo(verifiedAt);
            assertThat(
                            shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                                    0L, PageRequest.of(0, 10)))
                    .isEmpty();
        }
    }
}
