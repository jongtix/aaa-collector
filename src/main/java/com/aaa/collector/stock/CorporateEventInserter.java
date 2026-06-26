package com.aaa.collector.stock;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.RowFailureHandler;
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
 * 기업 이벤트 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code corporate_events}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT
 * IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 */
// @MX:ANCHOR: [AUTO] 기업 이벤트 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO]
// DividendScheduleCollectionService·RevSplitCollectionService·OverseasRightsCollectionService
// 3개 서비스가 호출(fan_in=3)
@Component
@RequiredArgsConstructor
public class CorporateEventInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 corporate_events에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO corporate_events
                (stock_id, event_type, event_date, ex_dividend_date, event_subtype,
                 pay_date, stock_pay_date, odd_pay_date,
                 cash_amount, cash_rate, stock_rate,
                 face_value, stock_kind, high_dividend_flag,
                 created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;

    /**
     * 기업 이벤트 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다.
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<CorporateEvent> rows) {
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

    /**
     * 기업 이벤트 행들을 격리 삽입한다 (REQ-INSERT-008).
     *
     * <p>독성 행은 {@code onFailure}로 통지하고 skip한 뒤 잔여 행을 계속 처리한다.
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     * @param onFailure 행별 실패 통지 콜백
     */
    public void insertBatchIsolated(
            List<CorporateEvent> rows, RowFailureHandler<CorporateEvent> onFailure) {
        if (rows.isEmpty()) {
            return;
        }
        Long drops =
                jdbcTemplate.execute(
                        (Connection conn) -> {
                            try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_SQL)) {
                                return SilentDropWarningCounter.countDropsPerRowIsolated(
                                        ps, rows, this::bindRow, onFailure);
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
    }

    private void bindRow(PreparedStatement ps, CorporateEvent e) throws SQLException {
        ps.setObject(1, e.getStock().getId());
        ps.setString(2, e.getEventType().name());
        ps.setObject(3, e.getEventDate());
        if (e.getExDividendDate() == null) {
            ps.setNull(4, Types.DATE);
        } else {
            ps.setObject(4, e.getExDividendDate());
        }
        setNullableString(ps, 5, e.getEventSubtype());
        if (e.getPayDate() == null) {
            ps.setNull(6, Types.DATE);
        } else {
            ps.setObject(6, e.getPayDate());
        }
        if (e.getStockPayDate() == null) {
            ps.setNull(7, Types.DATE);
        } else {
            ps.setObject(7, e.getStockPayDate());
        }
        if (e.getOddPayDate() == null) {
            ps.setNull(8, Types.DATE);
        } else {
            ps.setObject(8, e.getOddPayDate());
        }
        if (e.getCashAmount() == null) {
            ps.setNull(9, Types.BIGINT);
        } else {
            ps.setLong(9, e.getCashAmount());
        }
        setNullableDecimal(ps, 10, e.getCashRate());
        setNullableDecimal(ps, 11, e.getStockRate());
        if (e.getFaceValue() == null) {
            ps.setNull(12, Types.BIGINT);
        } else {
            ps.setLong(12, e.getFaceValue());
        }
        setNullableString(ps, 13, e.getStockKind());
        setNullableString(ps, 14, e.getHighDividendFlag());
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
