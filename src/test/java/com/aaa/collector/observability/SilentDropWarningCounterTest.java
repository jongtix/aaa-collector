package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

@DisplayName("SilentDropWarningCounter — INSERT IGNORE 침묵 드롭 판정·행별 구조화 로그 (REQ-OBSV-023/024)")
class SilentDropWarningCounterTest {

    /** MySQL 중복 키 경고 코드 (ER_DUP_ENTRY) — 정상 멱등 중복이므로 침묵 드롭이 아님. */
    private static final int ER_DUP_ENTRY = 1062;

    private static final String TABLE = "test_table";

    private Logger counterLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachLogAppender() {
        counterLogger = (Logger) LoggerFactory.getLogger(SilentDropWarningCounter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        counterLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachLogAppender() {
        counterLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    /** 구조화 침묵 드롭 WARN 로그만 선별한다(describer 실패 진단 로그는 제외). */
    private List<ILoggingEvent> silentDropLogs() {
        return listAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().startsWith("silent_drop table="))
                .toList();
    }

    /** 행 식별자 describer — 공개 식별자만 조합(테스트용). */
    private static SilentDropWarningCounter.RowBinder<String> bindString() {
        return (s, row) -> s.setString(1, row);
    }

    private static RowDescriber<String> describeById() {
        return row -> "id=" + row;
    }

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
    @DisplayName("countDropsPerRow — 행별 executeUpdate 후 Statement 경고 누적 + 구조화 로그")
    class CountDropsPerRow {

        // 모의 PreparedStatement는 try-with-resources로 닫는다(mock close()는 무동작) — PMD CloseResource 준수.

        @Test
        @DisplayName("각 행 실행 직전 clearWarnings를 호출해 이전 경고를 비운다 (행별 정확 분류)")
        void clearsWarningsBeforeEachRow() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(null);

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("a", "b"), bindString(), TABLE, describeById());

                // 경고 없으면 드롭 0, 각 행마다 clearWarnings 1회씩 총 2회, 로그 없음
                assertThat(drops).isZero();
                verify(ps, times(2)).clearWarnings();
                assertThat(silentDropLogs()).isEmpty();
            }
        }

        @Test
        @DisplayName("행마다 clearWarnings→executeUpdate 순서로 실행한다 (실행 후 비우기 금지)")
        void executesUpdateAfterClearingWarnings() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(null);

                SilentDropWarningCounter.countDropsPerRow(
                        ps, List.of("a"), bindString(), TABLE, describeById());

                // 경고는 executeUpdate 직후 읽어야 하므로 clearWarnings가 executeUpdate보다 먼저 와야 한다
                InOrder ordered = inOrder(ps);
                ordered.verify(ps).clearWarnings();
                ordered.verify(ps).executeUpdate();
            }
        }

        @Test
        @DisplayName("행별 비-중복 경고를 누적한다 (1265 + 1452 = 2), 경고당 로그 1건씩")
        void accumulatesNonDuplicateWarningsAcrossRows() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 1행은 데이터 절단(1265), 2행은 FK 위반(1452)
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated", "01000", 1265))
                        .thenReturn(new SQLWarning("FK fails", "23000", 1452));

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1", "r2"), bindString(), TABLE, describeById());

                // Assert — 드롭 2건 = 로그 2건
                assertThat(drops).isEqualTo(2L);
                assertThat(silentDropLogs()).hasSize(2);
            }
        }

        @Test
        @DisplayName("중복 키(1062) 경고만 있는 행은 드롭으로 세지 않고 로그도 없다, describer 미호출")
        void duplicateRowsNotCounted() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 두 행 모두 1062만 발생
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Duplicate", "23000", ER_DUP_ENTRY))
                        .thenReturn(new SQLWarning("Duplicate", "23000", ER_DUP_ENTRY));
                AtomicInteger describeCalls = new AtomicInteger();
                RowDescriber<String> describer =
                        row -> {
                            describeCalls.incrementAndGet();
                            return "id=" + row;
                        };

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1", "r2"), bindString(), TABLE, describer);

                // Assert
                assertThat(drops).isZero();
                assertThat(silentDropLogs()).isEmpty();
                assertThat(describeCalls).hasValue(0);
            }
        }

        @Test
        @DisplayName("빈 목록이면 statement를 실행하지 않고 0을 반환한다")
        void emptyListDoesNotExecute() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.<String>of(), bindString(), TABLE, describeById());

                assertThat(drops).isZero();
                verify(ps, times(0)).executeUpdate();
            }
        }

        @Test
        @DisplayName("AC-1 — 비-1062 경고 1건 → WARN 로그 1건, table/errorCode/warning/row 단일 값")
        void singleNonDuplicateWarning_emitsOneStructuredLog() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(new SQLWarning("Data truncated", "01000", 1265));

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1"), bindString(), "daily_ohlcv", describeById());

                assertThat(drops).isEqualTo(1L);
                List<ILoggingEvent> logs = silentDropLogs();
                assertThat(logs).hasSize(1);
                assertThat(logs.getFirst().getFormattedMessage())
                        .contains("table=daily_ohlcv")
                        .contains("errorCode=1265")
                        .contains("warning=Data truncated")
                        .contains("row=id=r1");
            }
        }

        @Test
        @DisplayName("AC-1/Edge — 한 행 다중 비-1062(1265+1366) → 로그 2건 = 카운트 2, describer 1회만")
        void multipleNonDuplicateInOneRow_emitsLogPerWarning() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                // Arrange — 한 행 경고 체인에 1265 → 1366 두 건 공존
                SQLWarning head = new SQLWarning("Data truncated", "01000", 1265);
                head.setNextWarning(new SQLWarning("Incorrect integer value", "HY000", 1366));
                when(ps.getWarnings()).thenReturn(head);
                AtomicInteger describeCalls = new AtomicInteger();
                RowDescriber<String> describer =
                        row -> {
                            describeCalls.incrementAndGet();
                            return "id=" + row;
                        };

                // Act
                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1"), bindString(), TABLE, describer);

                // Assert — 경고당 1건 → 로그 2건, describer는 그 행에서 1회만 (지연·메모이즈)
                assertThat(drops).isEqualTo(2L);
                assertThat(silentDropLogs()).hasSize(2);
                assertThat(describeCalls).hasValue(1);
            }
        }

        @Test
        @DisplayName("AC-3 — 정상 경로(무경고)에서 describer 미호출 (지연 호출)")
        void describerNotCalledWhenNoWarning() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(null);
                AtomicInteger describeCalls = new AtomicInteger();
                RowDescriber<String> describer =
                        row -> {
                            describeCalls.incrementAndGet();
                            return "id=" + row;
                        };

                SilentDropWarningCounter.countDropsPerRow(
                        ps, List.of("a", "b"), bindString(), TABLE, describer);

                assertThat(describeCalls).hasValue(0);
                assertThat(silentDropLogs()).isEmpty();
            }
        }

        @Test
        @DisplayName("CR-01 — 경고 메시지에 개행이 있으면 이스케이프되어 단일 로그 라인으로 유지된다")
        void warningMessageWithNewline_isEscapedToSingleLine() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated\nInjected line", "01000", 1265));

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1"), bindString(), TABLE, describeById());

                assertThat(drops).isEqualTo(1L);
                List<ILoggingEvent> logs = silentDropLogs();
                assertThat(logs).hasSize(1);
                String formatted = logs.getFirst().getFormattedMessage();
                assertThat(formatted).doesNotContain("\n");
                assertThat(formatted).contains("warning=Data truncated\\nInjected line");
            }
        }

        @Test
        @DisplayName("CR-01 — 행 식별자(row)에 개행이 있으면 이스케이프되어 단일 로그 라인으로 유지된다")
        void rowDescriptionWithNewline_isEscapedToSingleLine() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(new SQLWarning("Data truncated", "01000", 1265));
                RowDescriber<String> describer =
                        row -> "id=r1\r\nsilent_drop table=fake errorCode=0";

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1"), bindString(), TABLE, describer);

                assertThat(drops).isEqualTo(1L);
                List<ILoggingEvent> logs = silentDropLogs();
                assertThat(logs).hasSize(1);
                String formatted = logs.getFirst().getFormattedMessage();
                assertThat(formatted).doesNotContain("\r").doesNotContain("\n");
                assertThat(formatted).contains("row=id=r1\\r\\nsilent_drop table=fake errorCode=0");
            }
        }

        @Test
        @DisplayName("AC-7 — describer가 RuntimeException → 루프 지속, 카운트 반영, 구조화 로그 생략")
        void describerThrows_countReflectedLoggingSkipped() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated", "01000", 1265))
                        .thenReturn(new SQLWarning("FK fails", "23000", 1452));
                RowDescriber<String> boom =
                        row -> {
                            throw new IllegalStateException("boom");
                        };

                long drops =
                        SilentDropWarningCounter.countDropsPerRow(
                                ps, List.of("r1", "r2"), bindString(), TABLE, boom);

                // 두 행 모두 카운트 반영, 구조화 로그는 생략, 루프는 중단되지 않음
                assertThat(drops).isEqualTo(2L);
                assertThat(silentDropLogs()).isEmpty();
                verify(ps, times(2)).executeUpdate();
            }
        }
    }

    @Nested
    @DisplayName("countDropsPerRowIsolated — 독성 행 격리 + 구조화 로그 (REQ-INSERT-007/OBSV-024)")
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
                                ps,
                                List.of(toxicKey, "good1", "good2"),
                                binder,
                                onFailure,
                                TABLE,
                                describeById());

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
                                bindString(),
                                onFailure,
                                TABLE,
                                describeById());

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
                                ps, List.of(toxicKey), binder, onFailure, TABLE, describeById());

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
                                ps,
                                List.<String>of(),
                                bindString(),
                                onFailure,
                                TABLE,
                                describeById());

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
                                ps, List.of("r1"), bindString(), onFailure, TABLE, describeById());

                assertThat(drops).isZero();
                assertThat(silentDropLogs()).isEmpty();
            }
        }

        @Test
        @DisplayName("AC-1(격리) — 성공 행 비-1062 경고 → 구조화 로그 1건 (table/errorCode/row)")
        void isolatedPath_nonDuplicateWarning_emitsLog() throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings()).thenReturn(new SQLWarning("Data truncated", "01000", 1265));
                RowFailureHandler<String> onFailure = (row, ex) -> {};

                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps,
                                List.of("r1"),
                                bindString(),
                                onFailure,
                                "disclosures",
                                describeById());

                assertThat(drops).isEqualTo(1L);
                List<ILoggingEvent> logs = silentDropLogs();
                assertThat(logs).hasSize(1);
                assertThat(logs.getFirst().getFormattedMessage())
                        .contains("table=disclosures")
                        .contains("errorCode=1265")
                        .contains("row=id=r1");
            }
        }

        @Test
        @DisplayName("AC-7(격리) — describer 예외 → 카운트 반영·구조화 로그 생략·onFailure 미호출·루프 지속")
        void isolatedPath_describerThrows_countReflectedLoggingSkippedCallbackNotInvoked()
                throws Exception {
            try (PreparedStatement ps = mock(PreparedStatement.class)) {
                when(ps.getWarnings())
                        .thenReturn(new SQLWarning("Data truncated", "01000", 1265))
                        .thenReturn(null);
                List<String> failed = new ArrayList<>();
                RowFailureHandler<String> onFailure = (row, ex) -> failed.add(row);
                RowDescriber<String> boom =
                        row -> {
                            throw new IllegalStateException("boom");
                        };

                long drops =
                        SilentDropWarningCounter.countDropsPerRowIsolated(
                                ps, List.of("r1", "r2"), bindString(), onFailure, TABLE, boom);

                // describer 예외는 RowFailureHandler와 별개 경로 — 카운트 반영, 구조화 로그 생략, onFailure 미호출
                assertThat(drops).isEqualTo(1L);
                assertThat(silentDropLogs()).isEmpty();
                assertThat(failed).isEmpty();
            }
        }
    }
}
