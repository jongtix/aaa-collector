package com.aaa.collector.stock.exthours;

import com.aaa.collector.stock.Stock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Yahoo Finance v8 API를 통한 미국 시간외(Pre/After-Hours) 가격 스냅샷 수집 클라이언트 (SPEC-COLLECTOR-EXTHOURS-001).
 *
 * <p>엔드포인트: {@code GET /v8/finance/chart/{symbol}?interval=1m&range=1d&includePrePost=true}
 *
 * <p>세션 판정: {@code meta.currentTradingPeriod.pre/post.[start,end)} 구간으로 각 분봉의 세션을 결정한다. 시간외 거래량은
 * 구조적으로 0이므로 volume 필드는 참조하지 않는다(api-specs/yahoo-finance/02-extended-hours.md 실측).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YahooExtendedHoursClient {

    private static final String SOURCE = "YAHOO";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestClient yahooExtendedHoursRestClient;

    // @MX:ANCHOR: [AUTO] 시간외 가격 추출 진입점 — 세션 판정·마지막 close·갭 기준 종가 반환
    // @MX:REASON: SPEC-COLLECTOR-EXTHOURS-001 REQ-EXTH-020~025,030,031,034,055
    /**
     * 종목의 지정 세션 시간외 가격 스냅샷을 수집한다 (REQ-EXTH-020~025, 030~035).
     *
     * <p>skip 조건:
     *
     * <ul>
     *   <li>{@code meta.hasPrePostMarketData == false} → 빈 Optional
     *   <li>timestamp 배열 없음 → 빈 Optional
     *   <li>대상 세션 non-null close 0건 → 빈 Optional
     *   <li>{@code ext_price} 또는 {@code referenceClose} ≤ 0 → 빈 Optional
     * </ul>
     *
     * @param stock 수집 대상 종목
     * @param session 수집 세션 (PRE 또는 AFTER)
     * @return 수집 결과 (skip 조건 충족 시 empty)
     */
    @SuppressWarnings({"unchecked", "PMD.AvoidCatchingGenericException"})
    // unchecked: Yahoo Finance v8 JSON → 제네릭 타입 안전 캐스트 불가
    // PMD.AvoidCatchingGenericException: HTTP/파싱 예외 격리 — 종목별 skip
    public Optional<ExtendedHoursRow> fetch(Stock stock, Session session) {
        try {
            Map<String, Object> body =
                    yahooExtendedHoursRestClient
                            .get()
                            .uri(
                                    "/v8/finance/chart/{symbol}?interval=1m&range=1d&includePrePost=true",
                                    stock.getSymbol())
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

            return parseBody(stock, session, body);
        } catch (Exception e) {
            log.warn(
                    "[extended-hours] {} {} 호출 실패 — skip: {}",
                    stock.getSymbol(),
                    session,
                    e.getMessage() != null ? e.getMessage() : "[no message]");
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked") // Yahoo Finance v8 JSON 구조 캐스팅
    private Optional<ExtendedHoursRow> parseBody(
            Stock stock, Session session, Map<String, Object> body) {
        Optional<Map<String, Object>> resultOpt = extractFirstResult(body);
        if (resultOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> result = resultOpt.get();
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        if (meta == null) {
            return Optional.empty();
        }
        return parseMetaAndIndicators(stock, session, result, meta);
    }

    @SuppressWarnings("unchecked") // Yahoo Finance v8 JSON 구조 캐스팅
    private Optional<ExtendedHoursRow> parseMetaAndIndicators(
            Stock stock, Session session, Map<String, Object> result, Map<String, Object> meta) {

        if (!Boolean.TRUE.equals(meta.get("hasPrePostMarketData"))) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[extended-hours] {} hasPrePostMarketData=false — skip", stock.getSymbol());
            }
            return Optional.empty();
        }

        List<Number> timestamps = (List<Number>) result.get("timestamp");
        if (timestamps == null || timestamps.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[extended-hours] {} timestamp 없음 — skip", stock.getSymbol());
            }
            return Optional.empty();
        }

        Optional<long[]> boundsOpt = extractPeriodBounds(meta, session);
        if (boundsOpt.isEmpty()) {
            return Optional.empty();
        }
        long[] bounds = boundsOpt.get();

        List<Number> closes = extractCloses(result);
        if (closes.isEmpty()) {
            return Optional.empty();
        }

        return buildRow(stock, session, timestamps, closes, bounds, meta);
    }

    private Optional<ExtendedHoursRow> buildRow(
            Stock stock,
            Session session,
            List<Number> timestamps,
            List<Number> closes,
            long[] bounds,
            Map<String, Object> meta) {

        Optional<ClosePoint> closeOpt = extractLastClose(timestamps, closes, bounds[0], bounds[1]);
        if (closeOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[extended-hours] {} {} close 없거나 0 이하 — skip", stock.getSymbol(), session);
            }
            return Optional.empty();
        }
        BigDecimal extPrice = closeOpt.get().price();
        if (extPrice.compareTo(BigDecimal.ZERO) <= 0) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[extended-hours] {} {} extPrice 0 이하 — skip", stock.getSymbol(), session);
            }
            return Optional.empty();
        }

        BigDecimal referenceClose = extractReferenceClose(meta, session);
        if (referenceClose == null || referenceClose.compareTo(BigDecimal.ZERO) <= 0) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[extended-hours] {} {} referenceClose 없거나 0 이하 — skip",
                        stock.getSymbol(),
                        session);
            }
            return Optional.empty();
        }

        // REQ-EXTH-034: 세션 분봉 timestamp의 ET LocalDate로 산정 (period start 아닌 실제 close timestamp)
        LocalDate tradeDate =
                Instant.ofEpochSecond(closeOpt.get().epochSeconds()).atZone(NEW_YORK).toLocalDate();
        return Optional.of(
                new ExtendedHoursRow(
                        stock.getId(), session, tradeDate, extPrice, referenceClose, SOURCE));
    }

    /** 마지막 non-null close와 해당 분봉 timestamp를 묶은 값 객체. */
    private record ClosePoint(BigDecimal price, long epochSeconds) {}

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> extractFirstResult(Map<String, Object> body) {
        if (body == null) {
            return Optional.empty();
        }
        Map<String, Object> chart = (Map<String, Object>) body.get("chart");
        if (chart == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(results.getFirst());
    }

    @SuppressWarnings("unchecked")
    private Optional<long[]> extractPeriodBounds(Map<String, Object> meta, Session session) {
        Map<String, Object> tradingPeriod = (Map<String, Object>) meta.get("currentTradingPeriod");
        if (tradingPeriod == null) {
            return Optional.empty();
        }
        String periodKey = session == Session.PRE ? "pre" : "post";
        Map<String, Object> period = (Map<String, Object>) tradingPeriod.get(periodKey);
        if (period == null) {
            return Optional.empty();
        }
        long start = ((Number) period.get("start")).longValue();
        long end = ((Number) period.get("end")).longValue();
        return Optional.of(new long[] {start, end});
    }

    @SuppressWarnings("unchecked")
    private List<Number> extractCloses(Map<String, Object> result) {
        Map<String, Object> indicators = (Map<String, Object>) result.get("indicators");
        if (indicators == null) {
            return Collections.emptyList();
        }
        List<Map<String, List<Number>>> quoteList =
                (List<Map<String, List<Number>>>) indicators.get("quote");
        if (quoteList == null || quoteList.isEmpty()) {
            return Collections.emptyList();
        }
        List<Number> closes = quoteList.getFirst().get("close");
        return closes != null ? closes : Collections.emptyList();
    }

    /**
     * 세션 구간 {@code [periodStart, periodEnd)} 내 분봉을 역방향 탐색하여 마지막 non-null close와 해당 분봉 timestamp를
     * 반환한다 (REQ-EXTH-034: 실제 close timestamp 기반 trade_date 산정).
     *
     * @return 마지막 non-null close (price + epochSeconds), 없으면 empty
     */
    private Optional<ClosePoint> extractLastClose(
            List<Number> timestamps, List<Number> closes, long periodStart, long periodEnd) {
        BigDecimal foundPrice = null;
        long foundTs = -1;
        for (int i = timestamps.size() - 1; i >= 0; i--) {
            long ts = timestamps.get(i).longValue();
            if (ts < periodStart || ts >= periodEnd) {
                continue;
            }
            if (i >= closes.size()) {
                continue;
            }
            Number closeNum = closes.get(i);
            if (closeNum != null) {
                foundPrice =
                        BigDecimal.valueOf(closeNum.doubleValue())
                                .setScale(4, RoundingMode.HALF_UP);
                foundTs = ts;
                break;
            }
        }
        if (foundPrice == null) {
            return Optional.empty();
        }
        return Optional.of(new ClosePoint(foundPrice, foundTs));
    }

    /**
     * 세션별 갭 기준 종가를 추출한다.
     *
     * <ul>
     *   <li>PRE: {@code meta.chartPreviousClose} (전일 정규장 종가)
     *   <li>AFTER: {@code meta.regularMarketPrice} (당일 정규장 종가/현재가)
     * </ul>
     */
    private BigDecimal extractReferenceClose(Map<String, Object> meta, Session session) {
        String key = session == Session.PRE ? "chartPreviousClose" : "regularMarketPrice";
        Object val = meta.get(key);
        if (val == null) {
            return null;
        }
        return BigDecimal.valueOf(((Number) val).doubleValue()).setScale(4, RoundingMode.HALF_UP);
    }
}
