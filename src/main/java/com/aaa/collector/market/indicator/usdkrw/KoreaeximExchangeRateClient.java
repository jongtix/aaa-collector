package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
import com.aaa.collector.market.indicator.SensitiveDataSanitizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 한국수출입은행 환율 API USDKRW Primary 클라이언트 (SPEC-COLLECTOR-MARKETIND-001, REQ-010~013).
 *
 * <p>URL: {@code
 * /site/program/financial/exchangeJSON?authkey={key}&searchdate={yyyyMMdd}&data=AP01}. {@code
 * cur_unit=="USD"} 필터, 쉼표 제거 후 BigDecimal 4자리, close만/source="KOREAEXIM"(REQ-013). 빈 배열 시 전날 영업일
 * 재시도(상한 {@code empty-retry-max}, REQ-012).
 *
 * <p>{@code fetchHistory()}는 단일 호출 미지원 소스이므로 빈 리스트를 반환한다(REQ-041).
 */
@Slf4j
@Component
public class KoreaeximExchangeRateClient implements MarketIndicatorSource {

    private static final String SOURCE = "KOREAEXIM";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PATH =
            "/site/program/financial/exchangeJSON?authkey={key}&searchdate={date}&data=AP01";
    private static final ParameterizedTypeReference<List<Map<String, String>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient koreaeximRestClient;
    private final String apiKey;
    private final int emptyRetryMax;
    private final int ioRetryMax;
    private final long ioRetryBackoffBaseMs;

    public KoreaeximExchangeRateClient(
            RestClient koreaeximRestClient,
            @Value("${aaa.market-indicator.koreaexim-api-key:}") String apiKey,
            @Value("${aaa.market-indicator.usdkrw.empty-retry-max:5}") int emptyRetryMax,
            @Value("${aaa.market-indicator.usdkrw.io-retry-max:3}") int ioRetryMax,
            @Value("${aaa.market-indicator.usdkrw.io-retry-backoff-base-ms:1000}")
                    long ioRetryBackoffBaseMs) {
        this.koreaeximRestClient = koreaeximRestClient;
        this.apiKey = apiKey;
        this.emptyRetryMax = emptyRetryMax;
        this.ioRetryMax = ioRetryMax;
        this.ioRetryBackoffBaseMs = ioRetryBackoffBaseMs;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    /**
     * 지정 날짜의 USDKRW 환율을 수집한다. 빈 배열이면 전날 영업일(주말 skip)로 재시도한다(REQ-012).
     *
     * @param date 수집 시작 날짜
     * @return USD 행 목록 (성공 시 1건, 상한 초과 시 빈 리스트)
     */
    @Override
    public List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[koreaexim] KOREAEXIM_API_KEY 미설정 — 빈 결과 반환 (W-5, MA-03)");
            return List.of();
        }
        LocalDate target = date;
        for (int attempt = 0; attempt <= emptyRetryMax; attempt++) {
            List<MarketIndicatorRow> rows = fetchOnDate(target);
            if (!rows.isEmpty()) {
                return rows;
            }
            log.warn(
                    "[koreaexim] {} 빈 결과 — 전날 영업일로 재시도 ({}/{})",
                    target,
                    attempt + 1,
                    emptyRetryMax);
            target = prevBusinessDay(target);
        }
        log.warn("[koreaexim] 재시도 상한({}) 초과 — 빈 결과 반환", emptyRetryMax);
        return List.of();
    }

    /** 전체 이력 단일 호출 미지원 — 빈 리스트 반환(REQ-041). 백필은 날짜 루프로 수행한다. */
    @Override
    public List<MarketIndicatorRow> fetchHistory() {
        return List.of();
    }

    private List<MarketIndicatorRow> fetchOnDate(LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        List<Map<String, String>> body = fetchWithIoRetry(dateStr);
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        return body.stream()
                .filter(m -> "USD".equals(m.get("cur_unit")))
                .map(m -> toRow(m, date))
                .filter(r -> r != null)
                .toList();
    }

    /**
     * KOREAEXIM 서버의 순간적 TCP RST({@code Connection reset})에 대한 transient I/O 재시도(#105).
     *
     * <p>{@link ResourceAccessException}(하위 {@link java.io.IOException} 레벨 실패)에 한정해 지수 백오프로 최대
     * {@code ioRetryMax}회 재시도한다. HTTP 4xx/5xx 등 다른 예외는 재시도 없이 즉시 전파해 상위 {@code
     * MarketIndicatorSourceChain}의 폴백(Yahoo)이 정상 동작하도록 한다.
     */
    private List<Map<String, String>> fetchWithIoRetry(String dateStr) {
        int attempt = 0;
        while (true) {
            try {
                return koreaeximRestClient
                        .get()
                        .uri(PATH, apiKey, dateStr)
                        .retrieve()
                        .body(RESPONSE_TYPE);
            } catch (ResourceAccessException e) {
                if (attempt >= ioRetryMax) {
                    log.warn(
                            "[koreaexim] I/O 오류 재시도 상한({}) 초과 — 예외 전파: {}",
                            ioRetryMax,
                            SensitiveDataSanitizer.sanitize(e.getMessage()));
                    throw e;
                }
                long backoffMs = ioRetryBackoffBaseMs << attempt;
                attempt++;
                log.warn(
                        "[koreaexim] I/O 오류 — {}ms 후 재시도 ({}/{}): {}",
                        backoffMs,
                        attempt,
                        ioRetryMax,
                        SensitiveDataSanitizer.sanitize(e.getMessage()));
                sleep(backoffMs);
            }
        }
    }

    // Virtual Thread 환경 — 호출 내부 백오프 sleep은 무해(CLAUDE.md ADR-008 fixedDelay 금지와는 무관, 스케줄러 트리거 아님)
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("koreaexim I/O 재시도 대기 중 인터럽트", ie);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 행별 파싱 실패 격리
    private MarketIndicatorRow toRow(Map<String, String> m, LocalDate date) {
        try {
            String raw = m.get("deal_bas_r");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String cleaned = raw.replace(",", "");
            BigDecimal close = new BigDecimal(cleaned).setScale(4, RoundingMode.HALF_UP);
            if (close.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[koreaexim] close 0 이하 — skip: {}", m);
                return null;
            }
            return new MarketIndicatorRow(
                    IndicatorCode.USDKRW, date, null, null, null, close, SOURCE);
        } catch (Exception e) {
            log.warn(
                    "[koreaexim] 행 파싱 실패 — skip: {}, 오류: {}",
                    m,
                    SensitiveDataSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    /** 이전 영업일: 토요일이면 -1(금), 일요일이면 -2(금), 그 외 -1일. */
    static LocalDate prevBusinessDay(LocalDate date) {
        LocalDate prev = date.minusDays(1);
        while (prev.getDayOfWeek() == DayOfWeek.SATURDAY
                || prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
            prev = prev.minusDays(1);
        }
        return prev;
    }
}
