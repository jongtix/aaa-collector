package com.aaa.collector.stock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.RowFailureHandler;
import com.aaa.collector.stock.enums.EventType;
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
@DisplayName("CorporateEventInserter 단위 테스트 (REQ-OBSV-023 AC-5 배치 경로)")
class CorporateEventInserterTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BatchMetrics batchMetrics;

    private CorporateEvent entity() {
        return CorporateEvent.builder()
                .stock(Stock.builder().symbol("005930").build())
                .eventType(EventType.DIVIDEND)
                .eventDate(LocalDate.of(2026, 6, 5))
                .build();
    }

    @Nested
    @DisplayName("insertBatch — 빈 목록")
    class EmptyList {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            CorporateEventInserter inserter =
                    new CorporateEventInserter(jdbcTemplate, batchMetrics);

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
            CorporateEventInserter inserter =
                    new CorporateEventInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(2L);

            inserter.insertBatch(List.of(entity()));

            verify(batchMetrics).recordSilentDrops(2L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void nullFromExecute_recordsZero() {
            CorporateEventInserter inserter =
                    new CorporateEventInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);

            inserter.insertBatch(List.of(entity()));

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }

    @Nested
    @DisplayName("insertBatchIsolated — 격리 삽입 (REQ-INSERT-008)")
    class InsertBatchIsolated {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            CorporateEventInserter inserter =
                    new CorporateEventInserter(jdbcTemplate, batchMetrics);
            RowFailureHandler<CorporateEvent> onFailure = (row, ex) -> {};

            inserter.insertBatchIsolated(List.of(), onFailure);

            verifyNoInteractions(jdbcTemplate);
            verifyNoInteractions(batchMetrics);
        }

        @Test
        @DisplayName("execute가 드롭 수를 반환하면 BatchMetrics에 그대로 기록")
        void drops_recorded() {
            CorporateEventInserter inserter =
                    new CorporateEventInserter(jdbcTemplate, batchMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(1L);
            RowFailureHandler<CorporateEvent> onFailure = (row, ex) -> {};

            inserter.insertBatchIsolated(List.of(entity()), onFailure);

            verify(batchMetrics).recordSilentDrops(1L);
        }
    }
}
