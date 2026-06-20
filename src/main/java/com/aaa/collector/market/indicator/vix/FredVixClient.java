package com.aaa.collector.market.indicator.vix;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
import com.aaa.collector.market.indicator.SensitiveDataSanitizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FRED VIXCLS 시계열 수집 클라이언트 — VIX Fallback 1 (SPEC-COLLECTOR-MARKETIND-001, REQ-022/023).
 *
 * <p>URL: {@code
 * /fred/series/observations?series_id=VIXCLS&file_type=json&limit=100000&api_key=...}. {@code
 * value} == {@code "."} 행 skip, close만/open_high_low=NULL, {@code source="FRED"}(REQ-023).
 */
@Slf4j
@Component
public class FredVixClient implements MarketIndicatorSource {

    private static final String SOURCE = "FRED";
    private static final String PATH =
            "/fred/series/observations?series_id=VIXCLS&file_type=json&limit=100000&api_key={apiKey}";

    /**
     * 단일 날짜 스코프 쿼리 — 전체 이력 다운로드 없이 해당 날짜만 요청 (W-2, CR-02).
     *
     * <p>observation_start/end를 모두 같은 날짜로 설정하면 FRED는 해당 날짜 1건만 반환한다.
     */
    private static final String PATH_DAILY =
            "/fred/series/observations?series_id=VIXCLS&file_type=json"
                    + "&observation_start={startDate}&observation_end={endDate}&api_key={apiKey}";

    /** FRED 결측값 표시 (REQ-023). */
    private static final String MISSING_VALUE = ".";

    private final RestClient fredRestClient;
    private final String apiKey;

    public FredVixClient(
            RestClient fredRestClient,
            @Value("${aaa.market-indicator.fred-api-key:}") String apiKey) {
        this.fredRestClient = fredRestClient;
        this.apiKey = apiKey;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public List<MarketIndicatorRow> fetchHistory() {
        Map<String, Object> body =
                fredRestClient
                        .get()
                        .uri(PATH, apiKey)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
        return parseObservations(body);
    }

    @Override
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        String dateStr = date.toString(); // ISO-8601: yyyy-MM-dd
        Map<String, Object> body =
                fredRestClient
                        .get()
                        .uri(PATH_DAILY, dateStr, dateStr, apiKey)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
        return parseObservations(body);
    }

    @SuppressWarnings("unchecked")
    private List<MarketIndicatorRow> parseObservations(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }
        List<Map<String, String>> observations =
                (List<Map<String, String>>) body.get("observations");
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<MarketIndicatorRow> rows = new ArrayList<>();
        for (Map<String, String> obs : observations) {
            MarketIndicatorRow row = parseObservation(obs);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MarketIndicatorRow parseObservation(Map<String, String> obs) {
        try {
            String value = obs.get("value");
            if (MISSING_VALUE.equals(value) || value == null || value.isBlank()) {
                return null; // 결측 — REQ-023
            }
            LocalDate date = LocalDate.parse(obs.get("date"));
            BigDecimal close = new BigDecimal(value);
            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[fred-vix] close 0 이하 — skip: {}", obs);
                return null;
            }
            return new MarketIndicatorRow(IndicatorCode.VIX, date, null, null, null, close, SOURCE);
        } catch (Exception e) {
            log.warn(
                    "[fred-vix] 행 파싱 실패 — skip: {}, 오류: {}",
                    obs,
                    SensitiveDataSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }
}
