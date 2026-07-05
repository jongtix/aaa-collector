package com.aaa.collector.macro;

import com.aaa.collector.macro.enums.MacroSource;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 거시경제 지표 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code macro_indicators}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT
 * IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 */
// @MX:ANCHOR: [AUTO] 거시경제 지표 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO]
// EcosCollectionService·FredCollectionService·CompInterestCollectionService·MarketFundsCollectionService
// 4개 서비스가 호출(fan_in=4)
@Component
@RequiredArgsConstructor
public class MacroIndicatorInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 macro_indicators에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO macro_indicators
                (indicator_code, source, trade_date, value, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;
    private final WatermarkMetrics watermarkMetrics;

    /**
     * 거시경제 지표 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다. 소스별 최대 거래일로 {@code macro-ecos}/{@code macro-fred} 워터마크를
     * forward-only 갱신한다(SPEC-OBSV-WATERMARK-001 REQ-WM-001). {@code KIS} 소스는 워터마크 사전에 없으므로 갱신하지
     * 않는다(§3).
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<MacroIndicator> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Long drops =
                jdbcTemplate.execute(
                        (Connection conn) -> {
                            try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_SQL)) {
                                return SilentDropWarningCounter.countDropsPerRow(
                                        ps, rows, this::bindRow);
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
        watermarkMetrics.advance(WatermarkSeries.MACRO_ECOS, maxTradeDate(rows, MacroSource.ECOS));
        watermarkMetrics.advance(WatermarkSeries.MACRO_FRED, maxTradeDate(rows, MacroSource.FRED));
    }

    private static LocalDate maxTradeDate(List<MacroIndicator> rows, MacroSource source) {
        return rows.stream()
                .filter(e -> source == e.getSource())
                .map(MacroIndicator::getTradeDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private void bindRow(PreparedStatement ps, MacroIndicator e) throws SQLException {
        ps.setString(1, e.getIndicatorCode());
        ps.setString(2, e.getSource().name());
        ps.setObject(3, e.getTradeDate());
        ps.setBigDecimal(4, e.getValue());
    }
}
