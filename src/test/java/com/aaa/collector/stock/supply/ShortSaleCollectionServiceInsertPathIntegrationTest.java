package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Track 2 전제조건(REQ-SSVC-039, v0.4.1 신설, acceptance.md AC-8c) 통합 테스트 — 신규 INSERT 행이 실제 T+0 경로({@link
 * ShortSaleCollectionService#collect(LocalDate, List)})와 백필 경로({@link
 * ShortSaleCollectionService#collectWindow}) 양쪽 모두에서 실제 {@link ShortSaleInserter#insertBatch}
 * INSERT 경로를 거쳐 {@code vol_rate_verified_at IS NULL}로 남음을 확인한다
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md §M9).
 *
 * <p>{@link com.aaa.collector.stock.ShortSaleDomesticVolRateCorrectionRepositoryIntegrationTest}는
 * {@link ShortSaleInserter#insertBatch}를 직접 호출하는 헬퍼로 이 전제조건의 일부만 검증했다 — 실제 수집 서비스의 두 공개 진입점(T+0
 * {@code collect}, 백필 {@code collectWindow})을 경유해도 동일 결과가 성립하는지는 검증되지 않았다. 이 클래스가 그 간극을 메운다 —
 * 마이그레이션 DDL 정적 확인만으로 대체하지 않는다(plan.md §M9).
 *
 * <p><b>전용 컨테이너(공유 제외)</b>: {@code collect()}는 {@code Executors.newVirtualThreadPerTaskExecutor()}로
 * 종목별 DB 기록을 별도 스레드에서 실제 커밋한다 — {@code OverseasDailyOhlcvCollectionServiceIntegrationTest}와 동일 근거로
 * {@code @Transactional} 롤백 격리가 불가능해 (가시성 문제) 전용 컨테이너를 사용하고, 모든 단언은 {@code stock.getId()}
 * 스코프({@code findTrack2PendingVerificationBatch} 결과 필터링)로 한정한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("ShortSaleCollectionService INSERT 경로 — Track 2 전제조건 통합 테스트 (REQ-SSVC-039/AC-8c)")
@Tag("integration")
class ShortSaleCollectionServiceInsertPathIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private GuardedKisExecutor guardedKisExecutor;
    @MockitoBean private HealthyKeySelector healthyKeySelector;

    @Autowired private ShortSaleCollectionService service;
    @Autowired private StockRepository stockRepository;
    @Autowired private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Autowired private KeyLeaseRegistry keyLeaseRegistry;

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

    private KisShortSaleResponse.ShortSaleRow row(String date) {
        return new KisShortSaleResponse.ShortSaleRow(
                date,
                "12000",
                "3.5",
                "900000000",
                "4.2",
                "50000",
                "5.1",
                "3750000000",
                "6.3",
                "21500067");
    }

    private void stubFetch(String date) throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        any(),
                        anyString(),
                        eq(KisShortSaleResponse.class)))
                .thenReturn(new KisShortSaleResponse("0", "MCA00000", "정상", List.of(row(date))));
    }

    /**
     * Track 2 대상 조회({@code acml_vol IS NOT NULL AND vol_rate_verified_at IS NULL})에서 대상 종목 행을 찾는다.
     */
    private ShortSaleDomestic findTrack2Candidate(Stock stock) {
        return shortSaleDomesticRepository
                .findTrack2PendingVerificationBatch(0L, PageRequest.of(0, 50))
                .stream()
                .filter(r -> r.getStock().getId().equals(stock.getId()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "stock_id="
                                                + stock.getId()
                                                + " 행이 Track 2 대상 조회에 나타나지 않음"));
    }

    @Nested
    @DisplayName("T+0 경로 — collect() (내부적으로 private collectStock을 경유)")
    class T0Path {

        @Test
        @DisplayName("실제 INSERT 후 acml_vol 채워짐 + vol_rate_verified_at IS NULL — Track 2 대상에 즉시 편입")
        void collect_insertedRow_isImmediateTrack2Candidate() throws Exception {
            // Arrange
            Stock stock = savedStock("311001");
            LocalDate today = LocalDate.of(2026, 6, 13);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            stubFetch("20260612");

            // Act — 실제 T+0 수집 진입점 (내부적으로 collectStock → saveValidRows →
            // ShortSaleInserter.insertBatch)
            service.collect(today, List.of(stock));

            // Assert
            ShortSaleDomestic saved = findTrack2Candidate(stock);
            assertThat(saved.getAcmlVol()).isEqualTo(21_500_067L);
            assertThat(saved.getVolRateVerifiedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("백필 경로 — collectWindow()")
    class BackfillPath {

        @Test
        @DisplayName("실제 INSERT 후 acml_vol 채워짐 + vol_rate_verified_at IS NULL — Track 2 대상에 즉시 편입")
        void collectWindow_insertedRow_isImmediateTrack2Candidate() throws Exception {
            // Arrange
            Stock stock = savedStock("311002");
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 5, 30);
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            stubFetch("20260102");
            LeaseSession session = keyLeaseRegistry.openSession();

            // Act — 실제 백필 수집 진입점 (내부적으로 saveValidRows → ShortSaleInserter.insertBatch, 당일 경로와 동일
            // 재사용)
            service.collectWindow(stock, session, from, to);

            // Assert
            ShortSaleDomestic saved = findTrack2Candidate(stock);
            assertThat(saved.getAcmlVol()).isEqualTo(21_500_067L);
            assertThat(saved.getVolRateVerifiedAt()).isNull();
        }
    }
}
