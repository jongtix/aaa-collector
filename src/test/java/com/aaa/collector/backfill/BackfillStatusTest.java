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
    }
}
