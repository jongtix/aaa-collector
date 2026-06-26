package com.aaa.collector.stock.daily;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
@DisplayName("WarningCountingOhlcvInserter 단위 테스트 (REQ-OBSV-023 AC-5 배치 경로)")
class WarningCountingOhlcvInserterTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BatchMetrics batchMetrics;

    private KisDailyOhlcvResponse.DailyOhlcvRow row() {
        return new KisDailyOhlcvResponse.DailyOhlcvRow(
                "20260605", "74000", "76000", "73000", "75000", "1000000", "75000000000", "N");
    }

    private ParsedOhlcvRow parsedRow() {
        return new ParsedOhlcvRow(
                LocalDate.of(2026, 6, 5),
                new BigDecimal("74000"),
                new BigDecimal("76000"),
                new BigDecimal("73000"),
                new BigDecimal("75000"),
                1_000_000L,
                75_000_000_000L);
    }

    @Nested
    @DisplayName("insertBatch(DailyOhlcvRow) — 빈 목록")
    class EmptyList {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);

            inserter.insertBatch(1L, List.of(), DATE_FMT);

            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(batchMetrics);
        }
    }

    @Nested
    @DisplayName("insertBatch(DailyOhlcvRow) — 드롭 기록")
    class RecordDrops {

        @Test
        @DisplayName("execute가 드롭 수를 반환하면 BatchMetrics에 그대로 기록")
        void drops_recorded() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(4L);

            inserter.insertBatch(1L, List.of(row()), DATE_FMT);

            verify(batchMetrics).recordSilentDrops(4L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void nullFromExecute_recordsZero() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);

            inserter.insertBatch(1L, List.of(row()), DATE_FMT);

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }

    @Nested
    @DisplayName("insertBatch(ParsedOhlcvRow) — W-1 파싱 1회 오버로드 (REQ-INSERT-004, AC-1)")
    class ParsedRowInsertBatch {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyParsedRows_noJdbcNoMetric() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);

            inserter.insertBatch(1L, List.<ParsedOhlcvRow>of());

            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(batchMetrics);
        }

        @Test
        @DisplayName("execute가 드롭 수를 반환하면 BatchMetrics에 그대로 기록")
        void parsedRow_drops_recorded() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(2L);

            inserter.insertBatch(1L, List.of(parsedRow()));

            verify(batchMetrics).recordSilentDrops(2L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void parsedRow_nullFromExecute_recordsZero() {
            WarningCountingOhlcvInserter inserter =
                    new WarningCountingOhlcvInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);

            inserter.insertBatch(1L, List.of(parsedRow()));

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }
}
