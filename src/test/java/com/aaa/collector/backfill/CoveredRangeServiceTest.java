package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * {@link CoveredRangeService} 증분 primitive·조건부 스탬프·kept 게이트·원자성 통합 테스트 (SPEC-COLLECTOR-BACKFILL-011
 * AC-6~AC-9).
 *
 * <p>실제 {@code TransactionTemplate}(Testcontainers MySQL)으로 검증한다 — Mockito mock으로는 트랜잭션 롤백(결정 1
 * 원자성)을 재현할 수 없어 통합 테스트로 작성했다({@link AtomicRollback}).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("CoveredRangeService — 증분 primitive·조건부 스탬프·kept 게이트 (SPEC-COLLECTOR-BACKFILL-011)")
@Tag("integration")
class CoveredRangeServiceTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    /** 실제 카운터 대신 verify()로 REQ-CVR-031 anomaly 신호 발생을 검증한다. */
    @MockitoBean private BackfillMetrics backfillMetrics;

    @Autowired private CoveredRangeService coveredRangeService;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    @BeforeEach
    void cleanUp() throws SQLException {
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
    }

    private BackfillStatus seed(String targetCode, LocalDate coveredUntilDate) {
        BackfillStatus saved =
                backfillStatusRepository.saveAndFlush(
                        BackfillStatus.builder()
                                .targetType("STOCK")
                                .targetCode(targetCode)
                                .dataTable("daily_ohlcv")
                                .status(BackfillStatusType.IN_PROGRESS)
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
    @DisplayName("executeStep — kept 기반 전진 (REQ-CVR-012, -030)")
    class ExecuteStepKeptGate {

        @Test
        @DisplayName("AC-8/kept>0 — covered_until_date가 filledUntil로 전진한다")
        void keptPositive_advancesToFilledUntil() {
            // Arrange
            BackfillStatus status = seed("KEPT1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, step); // kept>0, 성공적으로 채움

            // Act
            CoveredFillResult result = coveredRangeService.executeStep(status, filler, cursor);

            // Assert
            assertThat(result.kept()).isEqualTo(5);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(cursor);
            verify(backfillMetrics, never()).recordAnomalyFailed();
        }

        @Test
        @DisplayName("AC-8/raw==0&&kept==0 — 정상 빈 응답, 전진 없음 + anomaly 없음(REQ-CVR-030 kept 확인 필요조건)")
        void emptyResponse_noAdvanceNoAnomaly() {
            // Arrange — 휴장일 등 원본 응답 자체가 없는 정상 케이스
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("EMPTY1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(0, 0, step);

            // Act
            CoveredFillResult result = coveredRangeService.executeStep(status, filler, cursor);

            // Assert — REQ-CVR-030 원문("kept가 확인된 경우에만 전진")에 따라 kept==0이면 raw 값과 무관하게 미전진
            assertThat(result.kept()).isZero();
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
        }

        @Test
        @DisplayName("AC-9/raw>0&&kept==0 — 확장 중단 + anomaly 경보 발생, '커버됨' 승격 차단 (REQ-CVR-031)")
        void partialValidationFailure_blocksAdvanceAndRaisesAnomaly() {
            // Arrange — #77류: 원본 응답은 있으나 검증 전량 실패(kept=0)
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("ANOMALY1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller filler = step -> new CoveredFillResult(0, 12, step);

            // Act
            CoveredFillResult result = coveredRangeService.executeStep(status, filler, cursor);

            // Assert
            assertThat(result.kept()).isZero();
            assertThat(result.raw()).isEqualTo(12);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
            verify(backfillMetrics, times(1)).recordAnomalyFailed();
        }
    }

    @Nested
    @DisplayName("advanceIfContinuous — 라이브 배치 조건부 스탬프 (REQ-CVR-020, -021)")
    class AdvanceIfContinuous {

        @Test
        @DisplayName("AC-6 — 연속(covered_until_date == wStart-1) → today로 전진")
        void continuous_advancesToToday() {
            // Arrange
            LocalDate wStart = LocalDate.of(2026, 7, 10);
            LocalDate today = LocalDate.of(2026, 7, 15);
            BackfillStatus status = seed("CONT1", wStart.minusDays(1));

            // Act
            coveredRangeService.advanceIfContinuous(status, wStart, today);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName(
                "AC-6 — 단일 날짜형([wStart,today]==[today,today]) covered_until_date==today-1 → today로 전진")
        void singleDateType_wStartEqualsToday_advances() {
            // Arrange — 단일 날짜형 라이브는 wStart==today로 호출
            LocalDate today = LocalDate.of(2026, 7, 15);
            BackfillStatus status = seed("SINGLE1", today.minusDays(1));

            // Act
            coveredRangeService.advanceIfContinuous(status, today, today);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("AC-7 — 갭 존재(covered_until_date < wStart-1) → 미전진, 갭이 내부로 편입되지 않음")
        void gapExists_suppressesStamp() {
            // Arrange — 사고 시나리오: 07-04~06 갭 + 07-07 라이브 성공
            LocalDate wStart = LocalDate.of(2026, 7, 7);
            LocalDate today = LocalDate.of(2026, 7, 7);
            LocalDate staleCoveredUntil = LocalDate.of(2026, 7, 3); // wStart-1(07-06)보다 오래됨 → 갭
            BackfillStatus status = seed("GAP1", staleCoveredUntil);

            // Act
            coveredRangeService.advanceIfContinuous(status, wStart, today);

            // Assert — covered_until_date 미변경(갭이 내부로 편입되지 않아 후속 정방향 갭 walk가 여전히 인식 가능)
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(staleCoveredUntil);
        }

        @Test
        @DisplayName("covered_until_date가 NULL(미설정)이면 연속으로 간주하지 않고 미전진")
        void nullCoveredUntilDate_treatedAsGap() {
            BackfillStatus status = seed("NULLCOV", null);
            LocalDate wStart = LocalDate.of(2026, 7, 7);
            LocalDate today = LocalDate.of(2026, 7, 7);

            coveredRangeService.advanceIfContinuous(status, wStart, today);

            assertThat(reload(status.getId()).getCoveredUntilDate()).isNull();
        }
    }

    @Nested
    @DisplayName("executeStep — 앞단 도달 검증 anomaly (REQ-CVR-076, 심층 방어)")
    class FrontReachAnomaly {

        @Test
        @DisplayName(
                "AC-21 — oldest > cursor(앞단 미도달) → anomaly 발생 + covered_until_date 그래도 전진(라이브락 없음)")
        void oldestAfterCursor_raisesAnomalyButStillAdvances() {
            // Arrange — 스텝 폭 산정이 잘못됐거나 API 반환 특성이 변한 잔여 상황을 stub으로 모사한다
            BackfillStatus status = seed("FRONTGAP1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate filledUntil = LocalDate.of(2026, 7, 5);
            LocalDate oldest = LocalDate.of(2026, 7, 3); // cursor(07-02)보다 늦음 = 앞단 미도달
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, oldest);

            // Act
            CoveredFillResult result = coveredRangeService.executeStep(status, filler, cursor);

            // Assert — 앞단 hole이 anomaly로 관측 가능하게 남되, covered_until_date 전진은 억제되지 않는다(동일 anchor
            // 재호출 라이브락 방지)
            assertThat(result.oldest()).isEqualTo(oldest);
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, times(1)).recordAnomalyFailed();
        }

        @Test
        @DisplayName("AC-21 — oldest <= cursor(정상 도달) → anomaly 미발생")
        void oldestAtOrBeforeCursor_noAnomaly() {
            // Arrange — 정상 케이스(TASK-010 스텝 폭 35일 정정 후 절대 발화하지 않아야 하는 tripwire)
            BackfillStatus status = seed("FRONTOK1", LocalDate.of(2026, 7, 1));
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            LocalDate filledUntil = LocalDate.of(2026, 7, 5);
            CoveredGapFiller filler = step -> new CoveredFillResult(5, 5, filledUntil, cursor);

            // Act
            coveredRangeService.executeStep(status, filler, cursor);

            // Assert
            assertThat(reload(status.getId()).getCoveredUntilDate()).isEqualTo(filledUntil);
            verify(backfillMetrics, never()).recordAnomalyFailed();
        }
    }

    @Nested
    @DisplayName("executeStep 원자성 — 데이터 저장과 전진이 같은 트랜잭션에서 커밋/롤백된다 (결정 1)")
    class AtomicRollback {

        @Test
        @DisplayName("persistStep 내부 예외 발생 시 covered_until_date·필러가 쓴 마커 데이터 모두 롤백된다")
        void exceptionInPersistStep_rollsBackBothDataAndAdvance() {
            // Arrange — 필러가 같은 트랜잭션 내에서 관리 엔티티에 마커(lastRowCount=999)를 쓴 뒤 예외를 던진다.
            // 이 마커는 "데이터 저장"을 대표하는 관측 가능한 부수효과다 — 정상 커밋 시엔 함께 반영되고,
            // 롤백 시엔 covered_until_date 전진과 함께 사라져야 결정 1(원자성)이 증명된다.
            LocalDate initialCoveredUntil = LocalDate.of(2026, 7, 1);
            BackfillStatus status = seed("ROLLBACK1", initialCoveredUntil);
            LocalDate cursor = LocalDate.of(2026, 7, 2);
            CoveredGapFiller throwingFiller =
                    step -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(status.getId()).orElseThrow();
                        managed.advance(
                                managed.getStatus(),
                                managed.getLastCollectedDate(),
                                managed.getStaleCount(),
                                999);
                        throw new IllegalStateException("stub persistStep 실패");
                    };

            // Act & Assert — 예외가 executeStep 밖으로 전파된다
            assertThatThrownBy(
                            () -> coveredRangeService.executeStep(status, throwingFiller, cursor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("stub persistStep 실패");

            // Assert — 마커(데이터 저장 대리)와 covered_until_date 전진 둘 다 롤백되어 호출 전 상태 그대로(seed 시점엔
            // lastRowCount 미설정=null이었으므로, 롤백 성공 시 null로 남는다 — 999로 커밋되면 안 된다)
            BackfillStatus afterRollback = reload(status.getId());
            assertThat(afterRollback.getLastRowCount()).isNull();
            assertThat(afterRollback.getCoveredUntilDate()).isEqualTo(initialCoveredUntil);
        }
    }
}
