package com.aaa.collector.stock.supply;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.SilentDropWarningCounter;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.CreditBalance;
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
 * 신용잔고 일별추이 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code credit_balance}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026). INSERT
 * IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 한 종목의 검증 통과 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 *
 * <p><b>경고 출처 — Statement.getWarnings() + 행별 executeUpdate</b>: MySQL Connector/J는 INSERT IGNORE가
 * 강등한 경고(FK 위반 1452, 데이터 절단 1265 등)를 {@link Connection#getWarnings()}로는 전파하지 않고 실행 {@link
 * PreparedStatement}에만 보고하며, {@code executeBatch()}는 그 경고를 보존하지 않는다(실측). 따라서 행을 배치가 아닌 {@code
 * executeUpdate()}로 하나씩 실행하고 각 행 직후 {@code ps.getWarnings()}를 읽어야 진짜 드롭이 집계된다 — 자세한 실측 근거는 {@link
 * SilentDropWarningCounter} 참조.
 *
 * <p>{@code prepareStatement}는 본 클래스의 {@code private static final} 상수 {@link #INSERT_IGNORE_SQL}을
 * 직접 참조한다 — SQL 문자열이 메서드 파라미터·반환으로 흐르지 않아 정적 분석이 상수임을 증명할 수 있다(SQL 인젝션 오탐 차단). 행→파라미터 바인딩을 본 클래스가
 * 소유하여 호출 서비스의 결합도를 낮춘다(REQ-OBSV-023).
 */
// @MX:ANCHOR: [AUTO] 신용잔고 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-023 — CreditBalanceCollectionService가 종목별로 호출
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
@Component
@RequiredArgsConstructor
public class CreditBalanceInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 credit_balance에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO credit_balance
                (stock_id, trade_date, loan_new_qty, loan_repay_qty, loan_balance_qty,
                 loan_new_amt, loan_repay_amt, loan_balance_amt, loan_balance_rate, loan_supply_rate,
                 lend_new_qty, lend_repay_qty, lend_balance_qty,
                 lend_new_amt, lend_repay_amt, lend_balance_amt, lend_balance_rate, lend_supply_rate,
                 created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;
    private final WatermarkMetrics watermarkMetrics;

    /**
     * 한 종목의 검증 통과 신용잔고 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다. 삽입 시도 행들의 최대 거래일로 {@code credit-balance} 워터마크를 forward-only
     * 갱신한다(SPEC-OBSV-WATERMARK-001 REQ-WM-001).
     *
     * @param rows 삽입할 검증 통과 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<CreditBalance> rows) {
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
                                        "credit_balance",
                                        e ->
                                                "stockId="
                                                        + e.getStock().getId()
                                                        + " tradeDate="
                                                        + e.getTradeDate());
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
        watermarkMetrics.advance(WatermarkSeries.CREDIT_BALANCE, maxTradeDate(rows));
    }

    private static LocalDate maxTradeDate(List<CreditBalance> rows) {
        return rows.stream()
                .map(CreditBalance::getTradeDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private void bindRow(PreparedStatement ps, CreditBalance e) throws SQLException {
        ps.setObject(1, e.getStock().getId());
        ps.setObject(2, e.getTradeDate());
        ps.setLong(3, e.getLoanNewQty());
        ps.setLong(4, e.getLoanRepayQty());
        ps.setLong(5, e.getLoanBalanceQty());
        ps.setLong(6, e.getLoanNewAmt());
        ps.setLong(7, e.getLoanRepayAmt());
        ps.setLong(8, e.getLoanBalanceAmt());
        ps.setBigDecimal(9, e.getLoanBalanceRate());
        ps.setBigDecimal(10, e.getLoanSupplyRate());
        ps.setLong(11, e.getLendNewQty());
        ps.setLong(12, e.getLendRepayQty());
        ps.setLong(13, e.getLendBalanceQty());
        ps.setLong(14, e.getLendNewAmt());
        ps.setLong(15, e.getLendRepayAmt());
        ps.setLong(16, e.getLendBalanceAmt());
        ps.setBigDecimal(17, e.getLendBalanceRate());
        ps.setBigDecimal(18, e.getLendSupplyRate());
    }
}
