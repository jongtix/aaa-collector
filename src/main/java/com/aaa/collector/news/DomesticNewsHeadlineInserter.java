package com.aaa.collector.news;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.RowFailureHandler;
import com.aaa.collector.observability.SilentDropWarningCounter;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 국내 뉴스 제목 INSERT IGNORE를 단일 JDBC 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처한다 (REQ-OBSV-023, AC-5).
 *
 * <p>{@code domestic_news_headlines}는 Tier-1 테이블이라 멱등 삽입에 {@code INSERT IGNORE}를 사용한다(ADR-026).
 * INSERT IGNORE는 영향 행 수로 정상 중복과 오류 드롭을 구분할 수 없으므로(둘 다 0행), 행들을 {@link
 * SilentDropWarningCounter#countDropsPerRow}로 행별 실행하며 각 행의 {@link PreparedStatement#getWarnings()}
 * 경고 체인을 분석해 중복(1062) 외 침묵 드롭만 {@link BatchMetrics}에 기록한다.
 */
// @MX:NOTE: [AUTO] 국내 뉴스 제목 INSERT IGNORE 경고 캡처 경로 — 침묵 드롭 가시화의 진입점
// @MX:REASON: [AUTO] NewsTitleCollectionService가 호출(fan_in=1)
@Component
@RequiredArgsConstructor
public class DomesticNewsHeadlineInserter {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 domestic_news_headlines에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE
    // 사용 시 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    private static final String INSERT_IGNORE_SQL =
            """
            INSERT IGNORE INTO domestic_news_headlines
                (serial_no, published_at, provider_code, title,
                 category_code, source,
                 stock_code1, stock_code2, stock_code3, stock_code4, stock_code5,
                 created_at, updated_at)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BatchMetrics batchMetrics;
    private final WatermarkMetrics watermarkMetrics;

    /**
     * 국내 뉴스 제목 행들을 단일 커넥션 배치로 멱등 삽입하고 침묵 드롭 경고를 기록한다.
     *
     * <p>빈 목록이면 JDBC를 사용하지 않는다. 삽입 시도 행들의 최대 게시일로 {@code news-domestic} 워터마크를 forward-only
     * 갱신한다(SPEC-OBSV-WATERMARK-001 REQ-WM-001/002 — 일-grain 인코딩).
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     */
    public void insertBatch(List<DomesticNewsHeadline> rows) {
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
                                        "domestic_news_headlines",
                                        e ->
                                                "serialNo="
                                                        + e.getSerialNo()
                                                        + " publishedAt="
                                                        + e.getPublishedAt());
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
        watermarkMetrics.advance(WatermarkSeries.NEWS_DOMESTIC, maxPublishedDate(rows));
    }

    /**
     * 국내 뉴스 제목 행들을 격리 삽입한다 (REQ-INSERT-008).
     *
     * <p>독성 행(SQLException)은 {@code onFailure} 콜백으로 통지하고 skip한 뒤 잔여 행을 계속 처리한다. 빈 목록이면 JDBC를 사용하지
     * 않는다.
     *
     * @param rows 삽입할 엔티티(빈 목록이면 무동작)
     * @param onFailure 행별 실패 통지 콜백
     */
    public void insertBatchIsolated(
            List<DomesticNewsHeadline> rows, RowFailureHandler<DomesticNewsHeadline> onFailure) {
        if (rows.isEmpty()) {
            return;
        }
        Long drops =
                jdbcTemplate.execute(
                        (Connection conn) -> {
                            try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_SQL)) {
                                return SilentDropWarningCounter.countDropsPerRowIsolated(
                                        ps,
                                        rows,
                                        this::bindRow,
                                        onFailure,
                                        "domestic_news_headlines",
                                        e ->
                                                "serialNo="
                                                        + e.getSerialNo()
                                                        + " publishedAt="
                                                        + e.getPublishedAt());
                            }
                        });
        batchMetrics.recordSilentDrops(drops == null ? 0L : drops);
        watermarkMetrics.advance(WatermarkSeries.NEWS_DOMESTIC, maxPublishedDate(rows));
    }

    private static LocalDate maxPublishedDate(List<DomesticNewsHeadline> rows) {
        return rows.stream()
                .map(DomesticNewsHeadline::getPublishedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(LocalDateTime::toLocalDate)
                .orElse(null);
    }

    private void bindRow(PreparedStatement ps, DomesticNewsHeadline e) throws SQLException {
        ps.setString(1, e.getSerialNo());
        ps.setObject(2, e.getPublishedAt());
        ps.setString(3, e.getProviderCode());
        ps.setString(4, e.getTitle());
        if (e.getCategoryCode() == null) {
            ps.setNull(5, Types.VARCHAR);
        } else {
            ps.setString(5, e.getCategoryCode());
        }
        if (e.getSource() == null) {
            ps.setNull(6, Types.VARCHAR);
        } else {
            ps.setString(6, e.getSource());
        }
        setNullableString(ps, 7, e.getStockCode1());
        setNullableString(ps, 8, e.getStockCode2());
        setNullableString(ps, 9, e.getStockCode3());
        setNullableString(ps, 10, e.getStockCode4());
        setNullableString(ps, 11, e.getStockCode5());
    }

    private void setNullableString(PreparedStatement ps, int index, String value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
