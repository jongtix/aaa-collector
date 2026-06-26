package com.aaa.collector.dart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.disclosure.DisclosureInserter;
import com.aaa.collector.dart.disclosure.DisclosureRow;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.RowFailureHandler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureInserter 단위 테스트 (REQ-OBSV-023 AC-5 배치 경로)")
class DisclosureInserterTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BatchMetrics batchMetrics;

    private DisclosureRow row() {
        return new DisclosureRow(
                1L,
                "00126380",
                "005930",
                "Y",
                "사업보고서",
                "20260620001234",
                "삼성전자",
                LocalDate.of(2026, 6, 20),
                "",
                null);
    }

    @Nested
    @DisplayName("insertBatch — 빈 목록")
    class EmptyList {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);

            inserter.insertBatch(List.of());

            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(batchMetrics);
        }
    }

    @Nested
    @DisplayName("insertBatch — 드롭 기록")
    class RecordDrops {

        @Test
        @DisplayName("execute가 드롭 수를 반환하면 BatchMetrics에 그대로 기록")
        void drops_recorded() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(2L);

            inserter.insertBatch(List.of(row()));

            verify(batchMetrics).recordSilentDrops(2L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void nullFromExecute_recordsZero() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);

            inserter.insertBatch(List.of(row()));

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }

    @Nested
    @DisplayName("insertBatchIsolated — 독성 행 격리 (REQ-INSERT-007, REQ-INSERT-008)")
    class InsertBatchIsolated {

        @Test
        @DisplayName("빈 목록이면 JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);
            RowFailureHandler<DisclosureRow> onFailure = (row, ex) -> {};

            inserter.insertBatchIsolated(List.of(), onFailure);

            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(batchMetrics);
        }

        @Test
        @DisplayName("execute가 드롭 수를 반환하면 BatchMetrics에 그대로 기록")
        void isolated_drops_recorded() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(1L);
            RowFailureHandler<DisclosureRow> onFailure = (row, ex) -> {};

            inserter.insertBatchIsolated(List.of(row()), onFailure);

            verify(batchMetrics).recordSilentDrops(1L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void isolated_nullFromExecute_recordsZero() {
            DisclosureInserter inserter = new DisclosureInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);
            RowFailureHandler<DisclosureRow> onFailure = (row, ex) -> {};

            inserter.insertBatchIsolated(List.of(row()), onFailure);

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }
}
