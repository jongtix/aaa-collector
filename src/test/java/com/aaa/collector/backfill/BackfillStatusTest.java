package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link BackfillStatus} 도메인 메서드 단위 테스트 (SPEC-COLLECTOR-BACKFILL-010).
 *
 * <p>{@code markVerified}(검증 마커)·{@code resetForReprocess}(표적 재처리 리셋)는 KIS/Spring 비의존 순수 로직이라 DB 없이
 * 검증한다. verified_at 영속·기준선 조회는 {@link BackfillStatusRepositoryTest}(Testcontainers)에서 검증한다.
 */
@DisplayName("BackfillStatus 도메인 메서드 — verified_at 마커·표적 재처리 리셋 (SPEC-COLLECTOR-BACKFILL-010)")
class BackfillStatusTest {

    private static BackfillStatus.BackfillStatusBuilder daily(String symbol) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable("daily_ohlcv");
    }

    @Nested
    @DisplayName("markVerified — 검증 마커 설정 (REQ-147/-150/-146a)")
    class MarkVerified {

        @Test
        @DisplayName("markVerified 호출 시 verified_at이 설정된다")
        void setsVerifiedAt() {
            BackfillStatus status = daily("AAPL").status(BackfillStatusType.COMPLETED).build();
            LocalDateTime now = LocalDateTime.of(2026, 7, 9, 10, 0);

            status.markVerified(now);

            assertThat(status.getVerifiedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("초기 상태의 verified_at은 null(미검증)이다")
        void initiallyNull() {
            BackfillStatus status = daily("AAPL").status(BackfillStatusType.COMPLETED).build();

            assertThat(status.getVerifiedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("advanceCoveredUntil — 상단 전용 mutator (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-072)")
    class AdvanceCoveredUntil {

        @Test
        @DisplayName("호출 시 coveredUntilDate만 세팅되고 다른 필드는 불변이다")
        void setsOnlyCoveredUntilDate_leavesOtherFieldsUnchanged() {
            // Arrange — backward walk 진행 중인 상태를 구성(다른 필드 전부 non-default 값으로 채워 회귀 감지력 확보)
            BackfillStatus.BackfillStatusBuilder builderState =
                    daily("AAPL")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .lastCollectedDate(LocalDate.of(2020, 1, 2))
                            .staleCount(3)
                            .lastRowCount(42)
                            .attemptCount(7)
                            .lastError("prior error")
                            .verifiedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
            BackfillStatus status = builderState.build();
            BackfillStatus expectedOtherFields = builderState.build();
            LocalDate newCoveredUntil = LocalDate.of(2026, 7, 15);

            // Act
            status.advanceCoveredUntil(newCoveredUntil);

            // Assert — coveredUntilDate만 변경, 나머지 필드는 backward walk 로직과 회귀 없이 그대로 유지
            assertThat(status.getCoveredUntilDate()).isEqualTo(newCoveredUntil);
            assertThat(status)
                    .usingRecursiveComparison()
                    .ignoringFields("coveredUntilDate")
                    .isEqualTo(expectedOtherFields);
        }

        @Test
        @DisplayName("초기 상태의 coveredUntilDate는 null(미설정)이다")
        void initiallyNull() {
            BackfillStatus status = daily("AAPL").status(BackfillStatusType.PENDING).build();

            assertThat(status.getCoveredUntilDate()).isNull();
        }
    }

    @Nested
    @DisplayName("resetForReprocess — 표적 재처리 리셋 (REQ-160)")
    class ResetForReprocess {

        @Test
        @DisplayName("status=PENDING, 진행점·verified_at·stale_count·last_error 전부 초기화")
        void resetsAllReprocessFields() {
            // Arrange — 검증 완료된 진행 상태를 구성
            BackfillStatus status =
                    daily("PLTR")
                            .status(BackfillStatusType.COMPLETED)
                            .lastCollectedDate(LocalDate.of(2024, 11, 26))
                            .staleCount(2)
                            .lastRowCount(50)
                            .lastError("prior error")
                            .verifiedAt(LocalDateTime.of(2026, 7, 8, 12, 0))
                            .build();

            // Act
            status.resetForReprocess();

            // Assert
            assertThat(status.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(status.getLastCollectedDate()).isNull();
            assertThat(status.getVerifiedAt()).isNull();
            assertThat(status.getStaleCount()).isZero();
            assertThat(status.getLastError()).isNull();
        }

        @Test
        @DisplayName(
                "coveredUntilDate(상단 커버 경계)는 리셋 전후로 불변 — 하단(backward) 재처리가 상단(forward) 진행을"
                        + " 되돌리지 않는다 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-072)")
        void preservesCoveredUntilDate() {
            // Arrange — 하단 진행 중 + 상단 커버가 이미 앞서 나가 있는 상태를 재현
            BackfillStatus status =
                    daily("PLTR")
                            .status(BackfillStatusType.COMPLETED)
                            .lastCollectedDate(LocalDate.of(2024, 11, 26))
                            .build();
            LocalDate coveredUntil = LocalDate.of(2026, 7, 10);
            status.advanceCoveredUntil(coveredUntil);

            // Act
            status.resetForReprocess();

            // Assert — 하단 진행점은 초기화되지만 상단 커버 경계는 그대로 유지
            assertThat(status.getLastCollectedDate()).isNull();
            assertThat(status.getCoveredUntilDate()).isEqualTo(coveredUntil);
        }
    }
}
