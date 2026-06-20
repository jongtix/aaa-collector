package com.aaa.collector.market.indicator;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Yahoo Finance v8 공용 시장 지표 수집 클라이언트 — VIX/USDKRW Fallback 2 (SPEC-COLLECTOR-MARKETIND-001,
 * REQ-016/024).
 *
 * <p>{@code ^VIX} → {@code %5EVIX} URL 인코딩, {@code USDKRW=X}. {@code User-Agent} 헤더 필수(REQ-016).
 * timestamp UTC epoch → {@code America/New_York} LocalDate 변환(REQ-016). close 소수점 4자리 반올림(REQ-016).
 * {@code source="YAHOO"}. OHLC 4컬럼(VIX) 또는 close만(구성에 따라).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YahooFinanceClient {

    private static final String SOURCE = "YAHOO";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    /** VIX 심볼: {@code ^VIX} → URI 인코딩 {@code %5EVIX} */
    private static final String VIX_SYMBOL = "%5EVIX";

    /** USDKRW 심볼 */
    private static final String USDKRW_SYMBOL = "USDKRW=X";

    private final RestClient yahooRestClient;

    /**
     * 전체 이력 수집.
     *
     * @param indicatorCode 수집 대상 지표 코드
     * @return 정규화된 행 목록
     */
    public List<MarketIndicatorRow> fetchHistory(IndicatorCode indicatorCode) {
        String symbol = toSymbol(indicatorCode);
        Map<String, Object> body =
                yahooRestClient
                        .get()
                        .uri("/v8/finance/chart/{symbol}?range=max&interval=1d", symbol)
                        .header("User-Agent", USER_AGENT)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
        return parseChart(body, indicatorCode);
    }

    /**
     * 일봉 수집 (지정 날짜 필터).
     *
     * @param indicatorCode 수집 대상 지표 코드
     * @param date 필터 날짜
     * @return 해당 날짜 행 목록
     */
    public List<MarketIndicatorRow> fetchDaily(IndicatorCode indicatorCode, LocalDate date) {
        return fetchHistory(indicatorCode).stream()
                .filter(r -> r.tradeDate().equals(date))
                .toList();
    }

    private String toSymbol(IndicatorCode code) {
        return switch (code) {
            case VIX -> VIX_SYMBOL;
            case USDKRW -> USDKRW_SYMBOL;
        };
    }

    @SuppressWarnings({
        "unchecked",
        "PMD.AvoidCatchingGenericException",
        "PMD.UnnecessaryCast"
    }) // Yahoo Finance v8 JSON → 제네릭 타입 안전 캐스트 불가
    private List<MarketIndicatorRow> parseChart(
            Map<String, Object> body, IndicatorCode indicatorCode) {
        if (body == null) {
            return List.of();
        }
        Map<String, Object> chart = (Map<String, Object>) body.get("chart");
        if (chart == null) {
            return List.of();
        }
        List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Map<String, Object> result = results.getFirst();
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        List<Map<String, List<Number>>> quoteList =
                (List<Map<String, List<Number>>>) indicators.get("quote");
        Map<String, List<Number>> quote = quoteList.getFirst();

        List<Number> opens = (List<Number>) quote.get("open");
        List<Number> highs = (List<Number>) quote.get("high");
        List<Number> lows = (List<Number>) quote.get("low");
        List<Number> closes = (List<Number>) quote.get("close");
        List<Number> timestamps = (List<Number>) result.get("timestamp");

        List<MarketIndicatorRow> rows = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            try {
                long epoch = timestamps.get(i).longValue();
                LocalDate date = Instant.ofEpochSecond(epoch).atZone(NEW_YORK).toLocalDate();

                Number closeNum = closes.get(i);
                if (closeNum == null) {
                    log.warn("[yahoo] close null — skip: idx={}", i);
                    continue;
                }
                BigDecimal close =
                        BigDecimal.valueOf(closeNum.doubleValue())
                                .setScale(4, RoundingMode.HALF_UP);
                if (close.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("[yahoo] close 0 이하 — skip: idx={}", i);
                    continue;
                }

                BigDecimal open = toScaled(opens.get(i));
                BigDecimal high = toScaled(highs.get(i));
                BigDecimal low = toScaled(lows.get(i));

                rows.add(
                        new MarketIndicatorRow(
                                indicatorCode, date, open, high, low, close, SOURCE));
            } catch (Exception e) {
                log.warn(
                        "[yahoo] 행 파싱 실패 — skip: idx={}, 오류: {}",
                        i,
                        SensitiveDataSanitizer.sanitize(e.getMessage()));
            }
        }
        return rows;
    }

    private BigDecimal toScaled(Number n) {
        if (n == null) {
            return null;
        }
        return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
    }
}
