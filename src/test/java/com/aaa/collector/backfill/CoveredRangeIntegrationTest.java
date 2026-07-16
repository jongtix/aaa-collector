package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.backfill.UsdkrwCoveredGapFiller;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.session.MarketSessionGate;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.RootFixtureCleaner;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * SPEC-COLLECTOR-BACKFILL-011 통합·비회귀 테스트 (TASK-009, REQ-CVR-003/-010/-040/-041/-042/-072).
 *
 * <p>TASK-001~008 개별 유닛/서비스 테스트({@link CoveredRangeServiceTest}, {@link
 * CoveredRangeServiceGapWalkTest}, {@code UsdkrwCoveredGapFillerTest} 등)를 대체하지 않는다 — 그 위에 얹어 컴포넌트
 * 경계를 넘나드는 실제 배선(실 {@link CoveredRangeService} + 실 {@link UsdkrwCoveredGapFiller} + 실 {@link
 * UsdkrwCollectionService} + 실 Testcontainers MySQL)이 함께 동작함을 고정한다. 외부 HTTP 경계({@link
 * MarketIndicatorSourceChain})만 mock으로 대체한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("커버-추적 통합 — 첫 회차 전수 복구 + backward walk 비회귀 (SPEC-COLLECTOR-BACKFILL-011)")
@Tag("integration")
class CoveredRangeIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private BackfillMetrics backfillMetrics;
    @MockitoBean private MarketSessionGate marketSessionGate;
    @MockitoBean private UsMarketSessionGate usMarketSessionGate;

    /** 외부 HTTP 경계만 mock — 실 체인 구현(KOREAEXIM 등)은 대체하되 그 하류(저장 경로)는 전부 실물이다. */
    @MockitoBean(name = "usdkrwChain")
    private MarketIndicatorSourceChain usdkrwChain;

    @Autowired private CoveredRangeService coveredRangeService;
    @Autowired private BackfillStatusRepository backfillStatusRepository;
    @Autowired private UsdkrwCollectionService usdkrwCollectionService;
    @Autowired private MarketIndicatorRepository marketIndicatorRepository;

    private static final IndicatorCode USDKRW = IndicatorCode.USDKRW;

    @BeforeEach
    void cleanUp() throws SQLException {
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
        // 클래스 내 모든 테스트가 static 컨테이너 하나를 공유하므로, 실 필러 체인 테스트가 남기는
        // market_indicators 잔여 행이 다른 테스트의 countByIndicatorCode 절대값 단언을 오염시킨다 — 매 테스트
        // 전 명시적으로 비운다(테스트 격리, 프로덕션 경로 무관).
        marketIndicatorRepository.deleteAll();
        when(marketSessionGate.isOpenDay(any())).thenReturn(true);
        when(usMarketSessionGate.isOpenDay(any())).thenReturn(true);
        clearInvocations(backfillMetrics);
    }

    private static MarketIndicatorRow validRow(LocalDate date) {
        return new MarketIndicatorRow(
                USDKRW, date, null, null, null, new BigDecimal("1380.0000"), "KOREAEXIM");
    }

    private BackfillStatus seedUsdkrw(LocalDate lastCollectedDate, LocalDate coveredUntilDate) {
        BackfillStatus saved =
                backfillStatusRepository.saveAndFlush(
                        BackfillStatus.builder()
                                .targetType("MARKET_INDICATOR")
                                .targetCode("USDKRW")
                                .dataTable("market_indicators")
                                .status(BackfillStatusType.IN_PROGRESS)
                                .lastCollectedDate(lastCollectedDate)
                                .staleCount(3)
                                .lastRowCount(7)
                                .attemptCount(9)
                                .build());
        if (coveredUntilDate != null) {
            saved.advanceCoveredUntil(coveredUntilDate);
            saved = backfillStatusRepository.saveAndFlush(saved);
        }
        return saved;
    }

    private BackfillStatus reload(Long id) {
        return backfillStatusRepository.findById(id).orElseThrow();
    }

    @Nested
    @DisplayName("AC-11 — 첫 회차(covered_until_date NULL) 전 구간 정방향 갭 walk 시작점")
    class FirstRunFullRecovery {

        @Test
        @DisplayName("① covered_until_date가 NULL이면 시작점 == last_collected_date+1 (오늘부터도, 0부터도 아님)")
        void nullCoveredUntilDate_startsFromLastCollectedDatePlusOne() {
            // Arrange — 배포 직후 시나리오: covered_until_date 미설정(advanceCoveredUntil 미호출)
            LocalDate lastCollected = LocalDate.of(2026, 6, 1);
            LocalDate today = LocalDate.of(2026, 6, 10);
            BackfillStatus status = seedUsdkrw(lastCollected, null);
            assertThat(status.getCoveredUntilDate()).isNull();

            List<LocalDate> cursorsCalled = new ArrayList<>();
            CoveredGapFiller filler =
                    cursor -> {
                        cursorsCalled.add(cursor);
                        return new CoveredFillResult(1, 1, cursor);
                    };

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert
            assertThat(cursorsCalled.getFirst()).isEqualTo(lastCollected.plusDays(1));
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("② 캡 내에서 여러 회차로 나뉘어도 회차마다 covered_until_date가 전진(단일 회차 폭주 없음, ③ 신규 캡 미도입)")
        void multiRunRecovery_advancesEachRunWithinExistingCap_noNewCapIntroduced() {
            // Arrange — 9일 구멍, 회차당 캡 3일을 흉내(기존 BACKFILL-004 테이블별 공정분배 캡의 대리)
            LocalDate lastCollected = LocalDate.of(2026, 6, 1);
            LocalDate today = lastCollected.plusDays(9);
            BackfillStatus status = seedUsdkrw(lastCollected, null);

            // Act — Run 1~3: 회차당 캡 3(신규 캡 아님 — 테스트 시뮬레이션 값일 뿐, 프로덕션 캡은 BackfillProperties 소유)
            coveredRangeService.walkGapForward(status, cappedFiller(3), today);
            LocalDate afterRun1 = reload(status.getId()).getCoveredUntilDate();
            coveredRangeService.walkGapForward(reload(status.getId()), cappedFiller(3), today);
            LocalDate afterRun2 = reload(status.getId()).getCoveredUntilDate();
            coveredRangeService.walkGapForward(reload(status.getId()), cappedFiller(3), today);
            LocalDate afterRun3 = reload(status.getId()).getCoveredUntilDate();

            // Assert — 매 회차 엄격히 전진, 최종 today 도달
            assertThat(afterRun1).isEqualTo(lastCollected.plusDays(3));
            assertThat(afterRun2).isAfter(afterRun1).isEqualTo(lastCollected.plusDays(6));
            assertThat(afterRun3).isAfter(afterRun2).isEqualTo(today);
        }

        private CoveredGapFiller cappedFiller(int maxCalls) {
            int[] calls = {0};
            return cursor -> {
                calls[0]++;
                if (calls[0] > maxCalls) {
                    return new CoveredFillResult(0, 0, cursor);
                }
                return new CoveredFillResult(1, 1, cursor);
            };
        }
    }

    @Nested
    @DisplayName(
            "실 컴포넌트 배선 — CoveredRangeService + UsdkrwCoveredGapFiller + UsdkrwCollectionService + 실 DB")
    class RealComponentWiring {

        @Test
        @DisplayName(
                "실 필러 체인으로 여러 스텝 진행 시 market_indicators에 실제 행이 적재되고 covered_until_date가 함께 커밋된다"
                        + " (AC-3, 결정 1 원자성)")
        void realFillerChain_persistsActualRowsAndAdvancesCoveredUntilTogether() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = LocalDate.of(2026, 6, 4);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);
            for (LocalDate d = coveredUntil.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
                when(usdkrwChain.fetchDaily(d)).thenReturn(List.of(validRow(d)));
            }
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert — 실 market_indicators 테이블에 3영업일치(06-02~06-04) 실 데이터가 적재됨
            assertThat(marketIndicatorRepository.countByIndicatorCode(USDKRW)).isEqualTo(3);
            assertThat(marketIndicatorRepository.findMaxTradeDateByIndicatorCode(USDKRW))
                    .contains(today);
            // covered_until_date도 동일 스텝에서 today까지 전진
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("AC-15 — 갭 walk 반복 실행 후에도 USDKRW backfill_status 행 수는 기존 1행 그대로(신규 행 미생성)")
        void usdkrwRowCountInvariant_noNewBackfillStatusRowCreated() {
            // Arrange
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = LocalDate.of(2026, 6, 3);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2020, 1, 1), coveredUntil);
            for (LocalDate d = coveredUntil.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
                when(usdkrwChain.fetchDaily(d)).thenReturn(List.of(validRow(d)));
            }
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert — MARKET_INDICATOR/USDKRW 행 수 불변(기존 1행)
            List<BackfillStatus> usdkrwRows =
                    backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "MARKET_INDICATOR", "market_indicators");
            assertThat(usdkrwRows).hasSize(1);
            assertThat(usdkrwRows.getFirst().getId()).isEqualTo(status.getId());
        }
    }

    @Nested
    @DisplayName("AC-17 — 기존 backward walk 필드 비회귀 (REQ-CVR-072, 실 DB 왕복 검증)")
    class BackwardWalkFieldsUnaffected {

        @Test
        @DisplayName(
                "정방향 갭 walk 실행 후 last_collected_date·status·stale_count·last_row_count·attempt_count는"
                        + " 전혀 변경되지 않는다 — 오직 covered_until_date만 갱신")
        void forwardWalk_onlyTouchesCoveredUntilDate_backwardFieldsUntouched() {
            // Arrange — backward walk 진행 중인 상태를 흉내(임의의 하단 경계·상태값)
            LocalDate coveredUntil = LocalDate.of(2026, 6, 1);
            LocalDate today = LocalDate.of(2026, 6, 5);
            BackfillStatus status = seedUsdkrw(LocalDate.of(2019, 3, 15), coveredUntil);
            BackfillStatus before = reload(status.getId());
            for (LocalDate d = coveredUntil.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
                when(usdkrwChain.fetchDaily(d)).thenReturn(List.of(validRow(d)));
            }
            UsdkrwCoveredGapFiller filler = new UsdkrwCoveredGapFiller(usdkrwCollectionService);

            // Act
            coveredRangeService.walkGapForward(status, filler, today);

            // Assert — 하단 경계·backward walk 상태 필드 전부 불변, 오직 상단 경계만 전진
            BackfillStatus after = reload(status.getId());
            assertThat(after)
                    .satisfies(
                            a -> {
                                assertThat(a.getLastCollectedDate())
                                        .isEqualTo(before.getLastCollectedDate());
                                assertThat(a.getStatus()).isEqualTo(before.getStatus());
                                assertThat(a.getStaleCount()).isEqualTo(before.getStaleCount());
                                assertThat(a.getLastRowCount()).isEqualTo(before.getLastRowCount());
                                assertThat(a.getAttemptCount()).isEqualTo(before.getAttemptCount());
                                assertThat(a.getVerifiedAt()).isEqualTo(before.getVerifiedAt());
                            });
            assertThat(after.getCoveredUntilDate()).isEqualTo(today);
        }
    }
}
