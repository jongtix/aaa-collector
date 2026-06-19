package com.aaa.collector.stock.daily;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 일봉 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code daily_ohlcv}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT IGNORE는
 * 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 한 종목의 행들을 동일 {@link Connection}에서 배치 실행한 뒤 {@link
 * Connection#getWarnings()} 경고 체인을 {@link SilentDropWarningCounter}로 분석해 중복(1062) 외 침묵 드롭만 {@link
 * BatchMetrics}에 기록한다.
 *
 * <p>{@code prepareStatement}는 본 클래스의 {@code private static final} 상수 {@link #INSERT_IGNORE_SQL}을
 * 직접 참조한다 — SQL 문자열이 메서드 파라미터·반환으로 흐르지 않아 정적 분석이 상수임을 증명할 수 있다(SQL 인젝션 오탐 차단). Tier-1 INSERT
 * IGNORE의 권한·SQL 형태(JPA 리포지토리와 동일한 {@code INSERT IGNORE INTO daily_ohlcv ... VALUES})를 보존한다 —
 * UPDATE 권한을 요구하지 않아 SQL 1142를 유발하지 않는다.
 */
// @MX:ANCHOR: [AUTO] 일봉 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-023 — DomesticDailyOhlcvCollectionService가 종목별로 호출
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
@Component
@RequiredArgsConstructor
public class WarningCountingOhlcvInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 daily_ohlcv에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-025/ADR-026 Tier-1). DailyOhlcvRepository.insertIgnoreDuplicate와 동일 형태.
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO daily_ohlcv
                (stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;

    /**
     * 한 종목의 검증된 일봉 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>KIS 행→DB 파라미터 매핑(파싱)을 본 클래스가 소유하여 호출 서비스의 결합도를 낮춘다(REQ-OBSV-023). 빈 목록이면 JDBC를 사용하지 않는다.
     *
     * @param stockId stocks.id
     * @param rows 삽입할 검증된 KIS 일봉 행(빈 목록이면 무동작)
     * @param fmt 거래일 파싱 포맷
     */
    public void insertBatch(
            Long stockId, List<KisDailyOhlcvResponse.DailyOhlcvRow> rows, DateTimeFormatter fmt) {
        if (rows.isEmpty()) {
            return;
        }
        Long drops =
                jdbcTemplate.execute(
                        (Connection conn) -> {
                            conn.clearWarnings();
                            try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_SQL)) {
                                for (KisDailyOhlcvResponse.DailyOhlcvRow row : rows) {
                                    bindRow(ps, stockId, row, fmt);
                                    ps.addBatch();
                                }
                                ps.executeBatch();
                            }
                            return SilentDropWarningCounter.countGenuineDrops(conn.getWarnings());
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
    }

    /** 단일 행을 PreparedStatement에 바인딩한다(파싱·BigDecimal 생성을 루프 밖 메서드로 분리). */
    private void bindRow(
            PreparedStatement ps,
            Long stockId,
            KisDailyOhlcvResponse.DailyOhlcvRow row,
            DateTimeFormatter fmt)
            throws SQLException {
        ps.setObject(1, stockId);
        ps.setObject(2, LocalDate.parse(row.stckBsopDate(), fmt));
        ps.setBigDecimal(3, new BigDecimal(row.stckOprc()));
        ps.setBigDecimal(4, new BigDecimal(row.stckHgpr()));
        ps.setBigDecimal(5, new BigDecimal(row.stckLwpr()));
        ps.setBigDecimal(6, new BigDecimal(row.stckClpr()));
        ps.setLong(7, Long.parseLong(row.acmlVol()));
        ps.setLong(8, Long.parseLong(row.acmlTrPbmn()));
    }
}
