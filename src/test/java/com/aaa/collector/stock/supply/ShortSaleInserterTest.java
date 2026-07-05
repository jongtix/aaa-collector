package com.aaa.collector.stock.supply;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import java.math.BigDecimal;
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
@DisplayName("ShortSaleInserter 단위 테스트 (REQ-OBSV-023 AC-5 배치 경로)")
class ShortSaleInserterTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BatchMetrics batchMetrics;
    @Mock private WatermarkMetrics watermarkMetrics;

    private ShortSaleDomestic entity() {
        return ShortSaleDomestic.builder()
                .stock(Stock.builder().symbol("005930").build())
                .tradeDate(LocalDate.of(2026, 6, 5))
                .shortSellQty(1L)
                .shortSellVolRate(BigDecimal.ONE)
                .shortSellAmt(1L)
                .shortSellAmtRate(BigDecimal.ONE)
                .shortSellAccQty(1L)
                .shortSellAccQtyRate(BigDecimal.ONE)
                .shortSellAccAmt(1L)
                .shortSellAccAmtRate(BigDecimal.ONE)
                .build();
    }

    @Nested
    @DisplayName("insertBatch — 빈 목록")
    class EmptyList {

        @Test
        @DisplayName("빈 목록 — JDBC·메트릭 미사용")
        void emptyRows_noJdbcNoMetric() {
            ShortSaleInserter inserter =
                    new ShortSaleInserter(jdbcTemplate, batchMetrics, watermarkMetrics);

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
            ShortSaleInserter inserter =
                    new ShortSaleInserter(jdbcTemplate, batchMetrics, watermarkMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(3L);

            inserter.insertBatch(List.of(entity()));

            verify(batchMetrics).recordSilentDrops(3L);
        }

        @Test
        @DisplayName("execute가 null 반환 시 0으로 대체하여 기록")
        void nullFromExecute_recordsZero() {
            ShortSaleInserter inserter =
                    new ShortSaleInserter(jdbcTemplate, batchMetrics, watermarkMetrics);
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);

            inserter.insertBatch(List.of(entity()));

            verify(batchMetrics).recordSilentDrops(0L);
        }
    }
}
