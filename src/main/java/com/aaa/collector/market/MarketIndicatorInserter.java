package com.aaa.collector.market;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시장 지표 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code market_indicators}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT
 * IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 */
// @MX:ANCHOR: [AUTO] 시장 지표 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO] UsdkrwCollectionService·VixCollectionService 2개 서비스가 호출(fan_in=2+백필
// orchestrator)
@Component
@RequiredArgsConstructor
public class MarketIndicatorInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 market_indicators에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-MARKETIND-001)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO market_indicators
                (indicator_code, trade_date, open_value, high_value, low_value, close_value, source, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;

    /**
     * 시장 지표 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다.
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<MarketIndicator> rows) {
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
    }

    private void bindRow(PreparedStatement ps, MarketIndicator e) throws SQLException {
        ps.setString(1, e.getIndicatorCode().name());
        ps.setObject(2, e.getTradeDate());
        if (e.getOpenValue() == null) {
            ps.setNull(3, Types.DECIMAL);
        } else {
            ps.setBigDecimal(3, e.getOpenValue());
        }
        if (e.getHighValue() == null) {
            ps.setNull(4, Types.DECIMAL);
        } else {
            ps.setBigDecimal(4, e.getHighValue());
        }
        if (e.getLowValue() == null) {
            ps.setNull(5, Types.DECIMAL);
        } else {
            ps.setBigDecimal(5, e.getLowValue());
        }
        ps.setBigDecimal(6, e.getCloseValue());
        if (e.getSource() == null) {
            ps.setNull(7, Types.VARCHAR);
        } else {
            ps.setString(7, e.getSource());
        }
    }
}
