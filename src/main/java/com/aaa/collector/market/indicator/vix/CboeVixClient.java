package com.aaa.collector.market.indicator.vix;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
import com.aaa.collector.market.indicator.SensitiveDataSanitizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CBOE VIX 일봉 CSV 수집 클라이언트 (SPEC-COLLECTOR-MARKETIND-001, REQ-020/021).
 *
 * <p>URL: {@code https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv}. 헤더 행
 * skip, {@code MM/dd/yyyy} DATE 파싱, OHLC 4컬럼, {@code source="CBOE"} (REQ-021). close 0 이하·파싱 실패 행
 * skip(REQ-034).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CboeVixClient implements MarketIndicatorSource {

    static final String CSV_PATH = "/api/global/us_indices/daily_prices/VIX_History.csv";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String SOURCE = "CBOE";
    private static final int EXPECTED_COLUMNS = 5;

    private final RestClient cboeRestClient;

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public List<MarketIndicatorRow> fetchHistory() {
        String csv = cboeRestClient.get().uri(CSV_PATH).retrieve().body(String.class);
        return parseCsv(csv);
    }

    /**
     * 일봉 수집 — 전체 CSV 다운로드 후 날짜 필터 (W-2: CBOE CSV API는 날짜 범위 파라미터 미지원).
     *
     * <p>CBOE VIX History CSV 엔드포인트는 전체 이력만 제공하며 날짜 필터링을 지원하지 않는다. 일봉 빈도(하루 1회, ~9200행 ~300KB)를
     * 고려하면 허용 가능한 수준이다.
     */
    @Override
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        List<MarketIndicatorRow> all = fetchHistory();
        return all.stream().filter(r -> r.tradeDate().equals(date)).toList();
    }

    /**
     * 범위 수집 — 전체 CSV 1회 다운로드 후 {@code [from, to]}(양끝 포함) 필터 (SPEC-COLLECTOR-MARKETIND-003,
     * REQ-011).
     *
     * <p>다운로드 비용은 현행 {@link #fetchDaily(LocalDate)}와 동일하다(증가 없음).
     */
    @Override
    public List<MarketIndicatorRow> fetchRange(LocalDate from, LocalDate to) {
        List<MarketIndicatorRow> all = fetchHistory();
        return all.stream()
                .filter(r -> !r.tradeDate().isBefore(from) && !r.tradeDate().isAfter(to))
                .toList();
    }

    private List<MarketIndicatorRow> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<MarketIndicatorRow> rows = new ArrayList<>();
        String[] lines = csv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            parseRow(line, rows);
        }
        return rows;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void parseRow(String line, List<MarketIndicatorRow> rows) {
        try {
            String[] cols = line.split(",");
            if (cols.length < EXPECTED_COLUMNS) {
                log.warn("[cboe-vix] 컬럼 수 부족 — skip: {}", line);
                return;
            }
            LocalDate date = LocalDate.parse(cols[0].trim(), DATE_FMT);
            BigDecimal open = new BigDecimal(cols[1].trim());
            BigDecimal high = new BigDecimal(cols[2].trim());
            BigDecimal low = new BigDecimal(cols[3].trim());
            BigDecimal close = new BigDecimal(cols[4].trim());

            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[cboe-vix] close 0 이하 — skip: {}", line);
                return;
            }
            rows.add(
                    new MarketIndicatorRow(
                            IndicatorCode.VIX, date, open, high, low, close, SOURCE));
        } catch (Exception e) {
            log.warn(
                    "[cboe-vix] 행 파싱 실패 — skip: {}, 오류: {}",
                    line,
                    SensitiveDataSanitizer.sanitize(e.getMessage()));
        }
    }
}
