package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SilentDropWarningCounter — INSERT IGNORE 침묵 드롭 판정 (REQ-OBSV-023)")
class SilentDropWarningCounterTest {

    /** MySQL 중복 키 경고 코드 (ER_DUP_ENTRY) — 정상 멱등 중복이므로 침묵 드롭이 아님. */
    private static final int ER_DUP_ENTRY = 1062;

    @Test
    @DisplayName("warning 체인이 null이면 0을 반환한다 (드롭 없음)")
    void nullChainReturnsZero() {
        assertThat(SilentDropWarningCounter.countGenuineDrops(null)).isZero();
    }

    @Test
    @DisplayName("중복 키 경고(1062)는 침묵 드롭으로 세지 않는다 (정상 멱등 중복)")
    void duplicateKeyWarningNotCounted() {
        SQLWarning dup = new SQLWarning("Duplicate entry", "23000", ER_DUP_ENTRY);

        assertThat(SilentDropWarningCounter.countGenuineDrops(dup)).isZero();
    }

    @Test
    @DisplayName("중복 외 경고(예: 데이터 절단 1265)는 침묵 드롭으로 센다 (진짜 데이터 유실)")
    void nonDuplicateWarningCounted() {
        SQLWarning truncation = new SQLWarning("Data truncated", "01000", 1265);

        assertThat(SilentDropWarningCounter.countGenuineDrops(truncation)).isEqualTo(1L);
    }

    @Test
    @DisplayName("혼합 체인 — 1062는 제외하고 비-1062만 센다")
    void mixedChainCountsOnlyNonDuplicate() {
        // Arrange — 1062(중복) → 1265(절단) → 1062(중복) → 1366(잘못된 정수값)
        SQLWarning head = new SQLWarning("Duplicate entry", "23000", ER_DUP_ENTRY);
        head.setNextWarning(new SQLWarning("Data truncated", "01000", 1265));
        head.getNextWarning()
                .setNextWarning(new SQLWarning("Duplicate entry", "23000", ER_DUP_ENTRY));
        head.getNextWarning()
                .getNextWarning()
                .setNextWarning(new SQLWarning("Incorrect integer value", "HY000", 1366));

        // Act & Assert — 비-중복 경고 2건(1265, 1366)
        assertThat(SilentDropWarningCounter.countGenuineDrops(head)).isEqualTo(2L);
    }
}
