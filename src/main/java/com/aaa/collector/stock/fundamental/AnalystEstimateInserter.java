package com.aaa.collector.stock.fundamental;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.AnalystEstimate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 투자의견 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code analyst_estimates}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT
 * IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 */
// @MX:NOTE: [AUTO] 투자의견 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO] InvestOpinionCollectionService가 호출(fan_in=1)
@Component
@RequiredArgsConstructor
public class AnalystEstimateInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 analyst_estimates(Tier-1)에 UPDATE 권한이 없어 ON DUPLICATE KEY
    // UPDATE
    // 사용 시 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-BATCH-004)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO analyst_estimates
                (stock_id, trade_date, institution_name, opinion, opinion_code, prev_opinion,
                 prev_opinion_code, target_price, prev_close, gap_n_day, gap_rate_n_day,
                 gap_futures, gap_rate_futures, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;
    private final WatermarkMetrics watermarkMetrics;

    /**
     * 투자의견 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다. 삽입 시도 행들의 최대 거래일로 {@code analyst-estimates} 워터마크를 forward-only
     * 갱신한다(SPEC-OBSV-WATERMARK-001 REQ-WM-001).
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<AnalystEstimate> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Long drops =
                jdbcTemplate.execute(
                        (Connection conn) -> {
                            try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_SQL)) {
                                return SilentDropWarningCounter.countDropsPerRow(
                                        ps,
                                        rows,
                                        this::bindRow,
                                        "analyst_estimates",
                                        e ->
                                                "stockId="
                                                        + e.getStock().getId()
                                                        + " tradeDate="
                                                        + e.getTradeDate());
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
        watermarkMetrics.advance(WatermarkSeries.ANALYST_ESTIMATES, maxTradeDate(rows));
    }

    private static LocalDate maxTradeDate(List<AnalystEstimate> rows) {
        return rows.stream()
                .map(AnalystEstimate::getTradeDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private void bindRow(PreparedStatement ps, AnalystEstimate e) throws SQLException {
        ps.setObject(1, e.getStock().getId());
        ps.setObject(2, e.getTradeDate());
        ps.setString(3, e.getInstitutionName());
        setNullableString(ps, 4, e.getOpinion());
        setNullableString(ps, 5, e.getOpinionCode());
        setNullableString(ps, 6, e.getPrevOpinion());
        setNullableString(ps, 7, e.getPrevOpinionCode());
        if (e.getTargetPrice() == null) {
            ps.setNull(8, Types.BIGINT);
        } else {
            ps.setLong(8, e.getTargetPrice());
        }
        if (e.getPrevClose() == null) {
            ps.setNull(9, Types.BIGINT);
        } else {
            ps.setLong(9, e.getPrevClose());
        }
        setNullableDecimal(ps, 10, e.getGapNDay());
        setNullableDecimal(ps, 11, e.getGapRateNDay());
        setNullableDecimal(ps, 12, e.getGapFutures());
        setNullableDecimal(ps, 13, e.getGapRateFutures());
    }

    private void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
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
