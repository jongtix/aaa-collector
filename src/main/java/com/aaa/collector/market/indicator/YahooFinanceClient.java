package com.aaa.collector.market.indicator;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * <p>{@code ^VIX}·{@code USDKRW=X}. {@code User-Agent} 헤더 필수(REQ-016). timestamp UTC epoch → {@code
 * America/New_York} LocalDate 변환(REQ-016). close 소수점 4자리 반올림(REQ-016). {@code source="YAHOO"}. OHLC
 * 4컬럼(VIX) 또는 close만(구성에 따라).
 *
 * <p>심볼은 Spring URI 템플릿 변수({@code {symbol}})로 전달한다. Spring이 RFC 3986에 따라 인코딩하므로 {@code ^} → {@code
 * %5E}로 정상 변환된다. 사전 인코딩값({@code %5EVIX})을 변수로 전달하면 {@code %25}로 이중인코딩되어 Yahoo 404가 발생한다(REQ-016 수정
 * 이력).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YahooFinanceClient {

    private static final String SOURCE = "YAHOO";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    /** VIX 심볼: Spring URI 템플릿이 {@code ^} → {@code %5E}로 인코딩한다. */
    private static final String VIX_SYMBOL = "^VIX";

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
     * 일봉 수집 — period1/period2 date-range 쿼리로 지정 날짜만 요청 (W-2, CR-02).
     *
     * <p>period1 = 해당 날짜 00:00:00 UTC epoch(초), period2 = 다음 날 00:00:00 UTC epoch(초). Yahoo v8은
     * range=max 대신 period1/period2를 지원하며, 일봉 수집(하루 1회) 시 불필요한 전체 이력 다운로드를 방지한다.
     *
     * @param indicatorCode 수집 대상 지표 코드
     * @param date 수집 날짜
     * @return 해당 날짜 행 목록
     */
    public List<MarketIndicatorRow> fetchDaily(IndicatorCode indicatorCode, LocalDate date) {
        return fetchRange(indicatorCode, date, date);
    }

    /**
     * 범위 수집 — period1/period2 date-range 쿼리로 지정 범위(양끝 포함)만 요청 (SPEC-COLLECTOR-MARKETIND-003,
     * REQ-012).
     *
     * <p>period1 = {@code from} 00:00:00 UTC epoch(초), period2 = {@code to + 1일} 00:00:00 UTC
     * epoch(초). {@link #fetchDaily(IndicatorCode, LocalDate)}의 1일 범위 메커니즘을 일반화한 것이다.
     *
     * @param indicatorCode 수집 대상 지표 코드
     * @param from 시작 날짜 (포함)
     * @param to 종료 날짜 (포함)
     * @return 범위 내 행 목록
     */
    public List<MarketIndicatorRow> fetchRange(
            IndicatorCode indicatorCode, LocalDate from, LocalDate to) {
        String symbol = toSymbol(indicatorCode);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Map<String, Object> body =
                yahooRestClient
                        .get()
                        .uri(
                                "/v8/finance/chart/{symbol}?period1={period1}&period2={period2}&interval=1d",
                                symbol,
                                period1,
                                period2)
                        .header("User-Agent", USER_AGENT)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
        return parseChart(body, indicatorCode);
    }

    private String toSymbol(IndicatorCode code) {
        return switch (code) {
            case VIX -> VIX_SYMBOL;
            case USDKRW -> USDKRW_SYMBOL;
        };
    }

    @SuppressWarnings({
        "unchecked",
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
        if (timestamps == null) {
            return List.of();
        }

        List<MarketIndicatorRow> rows = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            MarketIndicatorRow row =
                    parseRow(indicatorCode, timestamps, opens, highs, lows, closes, i);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Yahoo Finance v8 행 파싱 — 개별 예외 격리
    private MarketIndicatorRow parseRow(
            IndicatorCode indicatorCode,
            List<Number> timestamps,
            List<Number> opens,
            List<Number> highs,
            List<Number> lows,
            List<Number> closes,
            int i) {
        try {
            long epoch = timestamps.get(i).longValue();
            LocalDate date = Instant.ofEpochSecond(epoch).atZone(NEW_YORK).toLocalDate();

            Number closeNum = closes.get(i);
            if (closeNum == null) {
                log.warn("[yahoo] close null — skip: idx={}", i);
                return null;
            }
            BigDecimal close =
                    BigDecimal.valueOf(closeNum.doubleValue()).setScale(4, RoundingMode.HALF_UP);
            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[yahoo] close 0 이하 — skip: idx={}", i);
                return null;
            }

            return new MarketIndicatorRow(
                    indicatorCode,
                    date,
                    toScaled(opens.get(i)),
                    toScaled(highs.get(i)),
                    toScaled(lows.get(i)),
                    close,
                    SOURCE);
        } catch (Exception e) {
            log.warn(
                    "[yahoo] 행 파싱 실패 — skip: idx={}, 오류: {}",
                    i,
                    SensitiveDataSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    private BigDecimal toScaled(Number n) {
        if (n == null) {
            return null;
        }
        return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
    }
}
