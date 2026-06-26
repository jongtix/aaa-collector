package com.aaa.collector.stock.fundamental;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import com.aaa.collector.stock.Financial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 재무제표 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code financials}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT IGNORE는
 * 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link SilentDropWarningCounter#countDropsPerRow}로
 * 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()} 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link
 * BatchMetrics}에 기록한다.
 */
// @MX:NOTE: [AUTO] 재무제표 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO] FinancialRatioCollectionService가 호출(fan_in=1)
@Component
@RequiredArgsConstructor
public class FinancialInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 financials(Tier-1)에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-BATCH-004)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO financials
                (stock_id, period_type, period_date, revenue_growth, operating_profit_growth,
                 net_income_growth, roe, eps, sps, bps, retention_rate, debt_ratio,
                 created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;

    /**
     * 재무제표 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다.
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<Financial> rows) {
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

    private void bindRow(PreparedStatement ps, Financial e) throws SQLException {
        ps.setObject(1, e.getStock().getId());
        ps.setString(2, e.getPeriodType().name());
        ps.setObject(3, e.getPeriodDate());
        setNullableDecimal(ps, 4, e.getRevenueGrowth());
        setNullableDecimal(ps, 5, e.getOperatingProfitGrowth());
        setNullableDecimal(ps, 6, e.getNetIncomeGrowth());
        setNullableDecimal(ps, 7, e.getRoe());
        if (e.getEps() == null) {
            ps.setNull(8, Types.BIGINT);
        } else {
            ps.setLong(8, e.getEps());
        }
        if (e.getSps() == null) {
            ps.setNull(9, Types.BIGINT);
        } else {
            ps.setLong(9, e.getSps());
        }
        if (e.getBps() == null) {
            ps.setNull(10, Types.BIGINT);
        } else {
            ps.setLong(10, e.getBps());
        }
        setNullableDecimal(ps, 11, e.getRetentionRate());
        setNullableDecimal(ps, 12, e.getDebtRatio());
    }

    private void setNullableDecimal(PreparedStatement ps, int index, java.math.BigDecimal value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
    }
}
