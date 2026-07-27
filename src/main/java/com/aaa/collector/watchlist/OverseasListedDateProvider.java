package com.aaa.collector.watchlist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 해외 종목의 Yahoo Finance v8 {@code meta.firstTradeDate}(최초 거래일)를 취득한다
 * (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 REQ-SSOG-001,003,004,006).
 *
 * <p>이 값은 해외 {@code listed_date}의 값 소스로만 쓰인다 — 법인 신원 판정 근거로는 쓰지 않는다(REQ-SSOG-006, 법인 신원 판정은 SEC
 * EDGAR CIK 대조 런북이 사람 주도로 수행한다). not-found·네트워크 오류는 예외를 전파하지 않고 {@code null}을 반환해(REQ-SSOG-004/005)
 * 워치리스트 동기화 본연의 기능을 중단시키지 않는다. 비공식 API에 대한 동시 버스트가 차단을 유발하지 않도록 동시 호출 수를 제한한다(plan.md R3).
 *
 * <p>{@code yahooRestClient} 빈({@link
 * com.aaa.collector.market.indicator.MarketIndicatorClientConfig})을 재사용한다 — {@code
 * market.indicator.YahooFinanceClient}는 {@code IndicatorCode}(VIX/USDKRW) 전용 시그니처라 재사용하지
 * 않는다(plan.md 결정 1).
 */
@Slf4j
@Component
public class OverseasListedDateProvider {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    /** 활성 해외 종목 수(감사 기준 76)만큼의 동시 버스트를 억제하는 상한(plan.md R3). */
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 8;

    private final RestClient yahooRestClient;
    private final Semaphore concurrencyLimiter;

    @Autowired
    public OverseasListedDateProvider(RestClient yahooRestClient) {
        this(yahooRestClient, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    /** 테스트 전용 — 동시 호출 상한을 임의로 지정한다. */
    OverseasListedDateProvider(RestClient yahooRestClient, int maxConcurrentRequests) {
        this.yahooRestClient = yahooRestClient;
        this.concurrencyLimiter = new Semaphore(maxConcurrentRequests);
    }

    /**
     * 심볼의 최초 거래일을 취득한다.
     *
     * @param symbol 해외 종목 심볼
     * @return 최초 거래일(America/New_York 달력일). 심볼 미존재·네트워크 오류·응답에 값이 없으면 {@code null}을 반환하며 예외를 전파하지
     *     않는다(REQ-SSOG-004).
     */
    public LocalDate fetch(String symbol) {
        concurrencyLimiter.acquireUninterruptibly();
        try {
            return fetchFirstTradeDate(symbol);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private LocalDate fetchFirstTradeDate(String symbol) {
        try {
            Map<String, Object> body =
                    yahooRestClient
                            .get()
                            .uri("/v8/finance/chart/{symbol}?interval=1d&range=1d", symbol)
                            .header("User-Agent", USER_AGENT)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            return parseFirstTradeDate(body);
        } catch (RestClientException e) {
            log.warn("해외 상장일(firstTradeDate) 취득 실패 — skip, symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private LocalDate parseFirstTradeDate(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> chart = (Map<String, Object>) body.get("chart");
        if (chart == null) {
            return null;
        }
        List<Map<String, Object>> results = (List<Map<String, Object>>) chart.get("result");
        if (results == null || results.isEmpty()) {
            return null;
        }
        Map<String, Object> meta = (Map<String, Object>) results.getFirst().get("meta");
        if (meta == null) {
            return null;
        }
        Object firstTradeDate = meta.get("firstTradeDate");
        if (firstTradeDate == null) {
            return null;
        }
        long epoch = ((Number) firstTradeDate).longValue();
        return Instant.ofEpochSecond(epoch).atZone(NEW_YORK).toLocalDate();
    }
}
