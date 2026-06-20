package com.aaa.collector.market.indicator.vix;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
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

    @Override
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        List<MarketIndicatorRow> all = fetchHistory();
        return all.stream().filter(r -> r.tradeDate().equals(date)).toList();
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
            try {
                String[] cols = line.split(",");
                if (cols.length < 5) {
                    log.warn("[cboe-vix] 컬럼 수 부족 — skip: {}", line);
                    continue;
                }
                LocalDate date = LocalDate.parse(cols[0].trim(), DATE_FMT);
                BigDecimal open = new BigDecimal(cols[1].trim());
                BigDecimal high = new BigDecimal(cols[2].trim());
                BigDecimal low = new BigDecimal(cols[3].trim());
                BigDecimal close = new BigDecimal(cols[4].trim());

                if (close.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("[cboe-vix] close 0 이하 — skip: {}", line);
                    continue;
                }
                rows.add(
                        new MarketIndicatorRow(
                                IndicatorCode.VIX, date, open, high, low, close, SOURCE));
            } catch (Exception e) {
                log.warn("[cboe-vix] 행 파싱 실패 — skip: {}, 오류: {}", line, e.getMessage());
            }
        }
        return rows;
    }
}
