package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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

    @Nested
    @DisplayName("countDropsPerRow — 행별 executeUpdate 후 Statement 경고 누적")
    class CountDropsPerRow {

        // 모의 PreparedStatement는 try-with-resources로 닫는다(mock close()는 무동작) — PMD CloseResource 준수.

        @Test
        @DisplayName("각 행 실행 직전 clearWarnings를 호출해 이전 경고를 비운다 (행별 정확 분류)")
        void clearsWarningsBeforeEachRow() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(null);

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("a", "b"), (s, row) -> s.setString(1, row));

                // 경고 없으면 드롭 0, 각 행마다 clearWarnings 1회씩 총 2회
                assertThat(drops).isZero();
                verify(ps, times(2)).clearWarnings();
            }
        }

        @Test
        @DisplayName("행마다 clearWarnings→executeUpdate 순서로 실행한다 (실행 후 비우기 금지)")
        void executesUpdateAfterClearingWarnings() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(null);

                SilentDropWarningCounter.countDropsPerRow(
                        ps, List.of("a"), (s, row) -> s.setString(1, row));

                // 경고는 executeUpdate 직후 읽어야 하므로 clearWarnings가 executeUpdate보다 먼저 와야 한다
                InOrder ordered = inOrder(ps);
                ordered.verify(ps).clearWarnings();
                ordered.verify(ps).executeUpdate();
            }
        }

        @Test
        @DisplayName("행별 비-중복 경고를 누적한다 (1265 + 1452 = 2)")
        void accumulatesNonDuplicateWarningsAcrossRows() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 1행은 데이터 절단(1265), 2행은 FK 위반(1452)
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated", "01000", 1265))
                        .thenReturn(new SQLWarning("FK fails", "23000", 1452));

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1", "r2"), (s, row) -> s.setString(1, row));

                // Assert
                assertThat(drops).isEqualTo(2L);
            }
        }

        @Test
        @DisplayName("중복 키(1062) 경고만 있는 행은 드롭으로 세지 않는다")
        void duplicateRowsNotCounted() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 두 행 모두 1062만 발생
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Duplicate", "23000", ER_DUP_ENTRY))
                        .thenReturn(new SQLWarning("Duplicate", "23000", ER_DUP_ENTRY));

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1", "r2"), (s, row) -> s.setString(1, row));

                // Assert
                assertThat(drops).isZero();
                verify(ps, times(2)).executeUpdate();
            }
        }

        @Test
        @DisplayName("빈 목록이면 statement를 실행하지 않고 0을 반환한다")
        void emptyListDoesNotExecute() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.<String>of(), (s, row) -> s.setString(1, row));

                assertThat(drops).isZero();
                verify(ps, times(0)).executeUpdate();
            }
        }
    }

    @Nested
    @DisplayName("countDropsPerRowIsolated — 독성 행 격리, 잔여 행 계속 (REQ-INSERT-007)")
    class CountDropsPerRowIsolated {

        @Test
        @DisplayName("독성 행(SQLException)이 있을 때 onFailure 콜백을 호출하고 잔여 행을 계속 처리한다")
        void toxicRowCallsCallbackAndContinues() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 1행은 SQLException, 2행은 성공
                when(ps.getWarnings()).thenReturn(null);

                final String toxicKey = "toxic";
                List<String> processed = new ArrayList<>();
                List<String> failed = new ArrayList<>();

                SilentDropWarningCounter.RowBinder<String> binder =
                        (s, row) -> {
                            if (toxicKey.equals(row)) {
                                throw new SQLException("Data too long", "22001", 1406);
                            }
                            processed.add(row);
                            s.setString(1, row);
                        };

                RowFailureHandler<String> onFailure = (row, ex) -> failed.add(row);

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps, List.of(toxicKey, "good1", "good2"), binder, onFailure);

                // Assert — toxic 행 skip, good1/good2 처리됨
                assertThat(failed).containsExactly(toxicKey);
                assertThat(processed).containsExactly("good1", "good2");
                assertThat(drops).isZero();
            }
        }

        @Test
        @DisplayName("비-1062 경고는 침묵 드롭으로 누적하고 1062는 제외한다")
        void nonDuplicateWarningsCountedInIsolatedPath() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 1행 성공+경고 1265, 2행 성공+경고 없음
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated", "01000", 1265))
                        .thenReturn(null);

                RowFailureHandler<String> onFailure = (row, ex) -> {};

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps,
                                List.of("r1", "r2"),
                                (s, row) -> s.setString(1, row),
                                onFailure);

                // Assert
                assertThat(drops).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("SQLException 발생 행에는 onFailure를 호출하고 드롭 카운트에 포함하지 않는다")
        void sqlExceptionRowNotCountedAsDropAndCallbackInvoked() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                final String toxicKey = "toxic";
                List<String> failed = new ArrayList<>();

                SilentDropWarningCounter.RowBinder<String> binder =
                        (s, row) -> {
                            if (toxicKey.equals(row)) {
                                throw new SQLException("FK violation", "23000", 1452);
                            }
                            s.setString(1, row);
                        };

                when(ps.getWarnings()).thenReturn(null);

                RowFailureHandler<String> onFailure = (row, ex) -> failed.add(row);

                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps, List.of(toxicKey), binder, onFailure);

                assertThat(failed).containsExactly(toxicKey);
                assertThat(drops).isZero();
                // executeUpdate should NOT be called for the failed bind row
                verify(ps, never()).executeUpdate();
            }
        }

        @Test
        @DisplayName("빈 목록이면 statement 미실행 + 콜백 미호출 + 0 반환")
        void emptyListNoExecutionNoCallback() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                List<String> failed = new ArrayList<>();
                RowFailureHandler<String> onFailure = (row, ex) -> failed.add(row);

                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps, List.<String>of(), (s, row) -> s.setString(1, row), onFailure);

                assertThat(drops).isZero();
                assertThat(failed).isEmpty();
                verify(ps, never()).executeUpdate();
            }
        }

        @Test
        @DisplayName("1062 경고만 있는 성공 행은 드롭으로 세지 않는다")
        void duplicateOnlyWarningNotCountedInIsolatedPath() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(new SQLWarning("Duplicate", "23000", 1062));

                RowFailureHandler<String> onFailure = (row, ex) -> {};

                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps, List.of("r1"), (s, row) -> s.setString(1, row), onFailure);

                assertThat(drops).isZero();
            }
        }
    }
}
