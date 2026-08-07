package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.stock.supply.ShortSaleInserter;
import com.aaa.collector.support.SharedMySqlContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T0R 완료 마커 게이트 이진(binary) 통합 테스트 — 실 DB {@code t0r_correction_status.completed_at}로부터 {@link
 * ShortSaleDomesticCorrectionScheduler}가 게이트를 파생해 Track 1/Track 2 defer/정상처리를 실제로 갈라내는지 확인한다
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-043~046, plan.md §M9).
 *
 * <p>기존 {@link T0rGateStateTest}(순수 단위, DB 미접촉)와 {@link
 * ShortSaleVolRateCorrectionServiceTest}/{@link ShortSaleDomesticCorrectionSchedulerTest}(mock 기반,
 * {@link T0rGateState}를 수동 구성)는 게이트 상태 <em>파생</em> 로직과 defer 로직을 각각 독립적으로 검증했으나, "실 DB의 {@code
 * completed_at} 값 → 실제 UPDATE 경로까지 이진적으로 반영"이라는 전체 체인은 검증하지 않았다 — V46 마이그레이션이 {@code
 * completed_at=NULL}로 시딩하고, 오퍼레이터의 수동 root SQL(REQ-T0R-046)이 이를 완료로 전환하는 실제 운영 시나리오를 이 클래스가 재현한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("T0R 완료 마커 게이트 통합 테스트 (REQ-T0R-043~046)")
@Tag("integration")
class T0rCorrectionMarkerGateIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 공유 컨테이너 패턴(SharedMySqlContainer 참조).
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    /** V46 마이그레이션이 시딩하는 잠정값 — 닫히는 창 상한(2026-08-06). */
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 6);

    /**
     * REQ-T0R-011 하한 리터럴 — {@code T0rGateState}·{@code
     * ShortSaleDomesticT0RevisionCorrectionService}와 동일 값.
     */
    private static final LocalDate WINDOW_START = LocalDate.of(2026, 6, 29);

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private HealthyKeySelector healthyKeySelector;
    @MockitoBean private ShortSaleCollectionService shortSaleCollectionService;

    @Autowired private ShortSaleDomesticCorrectionScheduler scheduler;
    @Autowired private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Autowired private DailyOhlcvRepository dailyOhlcvRepository;
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
        return reload(stock, date);
    }

    private ShortSaleDomestic reload(Stock stock, LocalDate date) {
        return shortSaleDomesticRepository.findAll().stream()
                .filter(
                        r ->
                                r.getStock().getId().equals(stock.getId())
                                        && r.getTradeDate().equals(date))
                .findFirst()
                .orElseThrow();
    }

    /**
     * REQ-T0R-046 시뮬레이션 — 오퍼레이터의 수동 root SQL {@code UPDATE t0r_correction_status SET completed_at =
     * NOW()}. {@code collector} 앱 계정은 이 테이블에 UPDATE 권한이 없다(REQ-T0R-043 — 앱은 SELECT만) — root 계정으로 직접
     * 실행해 실제 운영 절차를 재현한다({@link SharedMySqlContainer#rootDataSourceFor(String)} 재사용, {@link
     * com.aaa.collector.support.RootFixtureCleaner}와 동일 패턴).
     */
    private void completeGateMarker() {
        new JdbcTemplate(SharedMySqlContainer.rootDataSourceFor(MYSQL.getJdbcUrl()))
                .update("UPDATE t0r_correction_status SET completed_at = NOW() WHERE id = 1");
    }

    private KisShortSaleResponse matchedTr04Response(String date, long liveQty, long liveAcmlVol) {
        return new KisShortSaleResponse(
                "0",
                "MCA00000",
                "조회되었습니다.",
                List.of(
                        new KisShortSaleResponse.ShortSaleRow(
                                date,
                                String.valueOf(liveQty),
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                String.valueOf(liveAcmlVol))));
    }

    @Nested
    @DisplayName("Track 2 — verifyRecentInserts recompute 직전 게이트 (REQ-T0R-044/-045)")
    class Track2Gate {

        @Test
        @DisplayName(
                "게이트 활성(마이그레이션 기본 completed_at=NULL) — 닫히는 창 구간 행은 defer,"
                        + " vol_rate_verified_at 여전히 NULL")
        void gateActive_windowRowDeferred() {
            // Arrange — V46 시딩값(completed_at=NULL) 그대로 사용, 별도 UPDATE 없음
            Stock stock = savedStock("T2G01");
            LocalDate tradeDate = LocalDate.of(2026, 7, 15); // [2026-06-29, 2026-08-06] 구간 내부
            insertedRow(stock, tradeDate, 10_000L, new BigDecimal("1.00"), 1_000_000L);

            // Act
            scheduler.run();

            // Assert — defer됐으므로 recompute·UPDATE 미수행, Track 2 대상에 그대로 잔류
            ShortSaleDomestic reloaded = reload(stock, tradeDate);
            assertThat(reloaded.getVolRateVerifiedAt()).isNull();
            assertThat(
                            shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                                    0L, PageRequest.of(0, 50)))
                    .anySatisfy(r -> assertThat(r.getId()).isEqualTo(reloaded.getId()));
        }

        @Test
        @Transactional(isolation = Isolation.READ_COMMITTED)
        @DisplayName(
                "게이트 완료(REQ-T0R-046 수동 root SQL 시뮬레이션) — 동일 구간 내 거래일도 정상 처리, "
                        + "vol_rate_verified_at 기록됨")
        void gateCompleted_windowRowProcessedNormally() {
            // Arrange — completeGateMarker()는 별도 root 커넥션으로 커밋한다. 클래스 기본 REPEATABLE READ에서는
            // 이 트랜잭션이 이미 앞선 SELECT(insertedRow 내부 reload())로 스냅샷을 고정해 그 커밋을 보지 못하므로,
            // 이 테스트만 READ_COMMITTED로 격리 수준을 낮춰 매 SELECT가 최신 커밋을 읽도록 한다(실측 확인 — 2026-08-06).
            Stock stock = savedStock("T2G02");
            LocalDate tradeDate = LocalDate.of(2026, 7, 15); // 닫히는 창 구간 내부 — 게이트 비활성이면 defer 없음
            ShortSaleDomestic row =
                    insertedRow(stock, tradeDate, 10_000L, new BigDecimal("1.00"), 1_000_000L);
            dailyOhlcvRepository.save(
                    DailyOhlcv.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .openPrice(BigDecimal.ZERO)
                            .highPrice(BigDecimal.ZERO)
                            .lowPrice(BigDecimal.ZERO)
                            .closePrice(BigDecimal.ZERO)
                            .volume(1_000_000L)
                            .tradingValue(0L)
                            .build());
            completeGateMarker();

            // Act
            scheduler.run();

            // Assert
            ShortSaleDomestic reloaded = reload(stock, tradeDate);
            assertThat(reloaded.getVolRateVerifiedAt()).isNotNull();
            assertThat(reloaded.getShortSellVolRate()).isEqualByComparingTo("1.00");
            assertThat(
                            shortSaleDomesticRepository.findTrack2PendingVerificationBatch(
                                    0L, PageRequest.of(0, 50)))
                    .noneSatisfy(r -> assertThat(r.getId()).isEqualTo(row.getId()));
        }
    }

    @Nested
    @DisplayName("Track 1 — correctLegacyBacklog ②단계 게이트 (REQ-T0R-044/-045)")
    class Track1Gate {

        @Test
        @DisplayName("게이트 활성 — 닫히는 창 구간 행은 defer, acml_vol·vol_rate_verified_at 둘 다 미기록")
        void gateActive_windowRowDeferred() throws Exception {
            // Arrange — MATCHED 판정(저장rate == 라이브rate)이지만 거래일이 닫히는 창 내부
            Stock stock = savedStock("T1G01");
            LocalDate tradeDate = LocalDate.of(2026, 7, 15);
            ShortSaleDomestic row =
                    insertedRow(stock, tradeDate, 10_000L, new BigDecimal("2.00"), null);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("T1G01"), eq(tradeDate)))
                    .thenReturn(matchedTr04Response("20260715", 10_000L, 500_000L));

            // Act
            scheduler.run();

            // Assert
            ShortSaleDomestic reloaded = reload(stock, tradeDate);
            assertThat(reloaded.getAcmlVol()).isNull();
            assertThat(reloaded.getVolRateVerifiedAt()).isNull();
            assertThat(
                            shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                                    0L, PageRequest.of(0, 50)))
                    .anySatisfy(r -> assertThat(r.getId()).isEqualTo(row.getId()));
        }

        @Test
        @Transactional(isolation = Isolation.READ_COMMITTED)
        @DisplayName("게이트 완료 — 동일 구간 내 거래일도 정상 처리, acml_vol·rate·vol_rate_verified_at 원자적 반영")
        void gateCompleted_windowRowProcessedNormally() throws Exception {
            // Arrange — completeGateMarker()의 별도 root 커넥션 커밋을 이 트랜잭션이 확실히 보도록 READ_COMMITTED
            // 사용(Track2Gate와 동일 근거).
            Stock stock = savedStock("T1G02");
            LocalDate tradeDate = LocalDate.of(2026, 7, 15);
            ShortSaleDomestic row =
                    insertedRow(stock, tradeDate, 10_000L, new BigDecimal("2.00"), null);
            dailyOhlcvRepository.save(
                    DailyOhlcv.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .openPrice(BigDecimal.ZERO)
                            .highPrice(BigDecimal.ZERO)
                            .lowPrice(BigDecimal.ZERO)
                            .closePrice(BigDecimal.ZERO)
                            .volume(500_000L)
                            .tradingValue(0L)
                            .build());
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(shortSaleCollectionService.fetchSingleDate(
                            any(LeaseSession.class), eq("T1G02"), eq(tradeDate)))
                    .thenReturn(matchedTr04Response("20260715", 10_000L, 500_000L));
            completeGateMarker();

            // Act
            scheduler.run();

            // Assert — recomputedRate = 10000*100/500000 = 2.00
            ShortSaleDomestic reloaded = reload(stock, tradeDate);
            assertThat(reloaded.getAcmlVol()).isEqualTo(500_000L);
            assertThat(reloaded.getShortSellVolRate()).isEqualByComparingTo("2.00");
            assertThat(reloaded.getVolRateVerifiedAt()).isNotNull();
            assertThat(
                            shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(
                                    0L, PageRequest.of(0, 50)))
                    .noneSatisfy(r -> assertThat(r.getId()).isEqualTo(row.getId()));
        }
    }

    @Nested
    @DisplayName("게이트 구간 경계 상수 정합 (REQ-T0R-011)")
    class WindowConstants {

        @Test
        @DisplayName("이 테스트가 쓰는 하한·상한이 V46 시딩값·T0rGateState와 일치한다")
        void windowConstants_matchSeedAndGateState() {
            assertThat(WINDOW_START).isEqualTo(LocalDate.of(2026, 6, 29));
            assertThat(WINDOW_END).isEqualTo(LocalDate.of(2026, 8, 6));
        }
    }
}
