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
    private final int ioRetryLongMax;
    private final long ioRetryLongDelayMs;

    public KoreaeximExchangeRateClient(
            RestClient koreaeximRestClient,
            @Value("${aaa.market-indicator.koreaexim-api-key:}") String apiKey,
            @Value("${aaa.market-indicator.usdkrw.empty-retry-max:5}") int emptyRetryMax,
            @Value("${aaa.market-indicator.usdkrw.io-retry-max:3}") int ioRetryMax,
            @Value("${aaa.market-indicator.usdkrw.io-retry-backoff-base-ms:1000}")
                    long ioRetryBackoffBaseMs,
            @Value("${aaa.market-indicator.usdkrw.io-retry-long-max:2}") int ioRetryLongMax,
            @Value("${aaa.market-indicator.usdkrw.io-retry-long-delay-ms:300000}")
                    long ioRetryLongDelayMs) {
        this.koreaeximRestClient = koreaeximRestClient;
        this.apiKey = apiKey;
        this.emptyRetryMax = emptyRetryMax;
        this.ioRetryMax = ioRetryMax;
        this.ioRetryBackoffBaseMs = ioRetryBackoffBaseMs;
        this.ioRetryLongMax = ioRetryLongMax;
        this.ioRetryLongDelayMs = ioRetryLongDelayMs;
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

    /**
     * 백필 전용 단일 날짜 조회(SPEC-COLLECTOR-MARKETIND-004 REQ-012~014). 라이브 {@link #fetchDaily(LocalDate)}와
     * 달리 empty-retry(전 영업일 역산)를 사용하지 않고, 소스 체인({@code MarketIndicatorSourceChain})도 경유하지 않는다 — 백필
     * 오케스트레이터가 이 메서드를 직접 호출한다.
     *
     * <p>#105 I/O 재시도({@link #fetchWithIoRetry(String)})는 라이브 경로와 동일하게 보존된다. KOREAEXIM 쿼터
     * 소진(`result:4`) 응답을 받으면 {@link KoreaeximQuotaExhaustedException}을 던져 호출자에게 전파한다 — 정상 빈 리스트로
     * 삼키지 않는다.
     *
     * @param date 수집 대상 날짜
     * @return USD 행 목록 (성공 시 1건, 정상 빈 결과면 빈 리스트)
     * @throws KoreaeximQuotaExhaustedException KOREAEXIM 응답이 쿼터 소진(`result:4`)일 때
     */
    public List<MarketIndicatorRow> fetchDailyForBackfill(LocalDate date) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[koreaexim] KOREAEXIM_API_KEY 미설정 — 빈 결과 반환 (W-5, MA-03)");
            return List.of();
        }
        String dateStr = date.format(DATE_FMT);
        List<Map<String, String>> body = fetchWithIoRetry(dateStr);
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        if (isQuotaExhausted(body)) {
            throw new KoreaeximQuotaExhaustedException(
                    "KOREAEXIM 쿼터 소진(result:4) — date=" + dateStr);
        }
        return parseUsdRows(body, date);
    }

    private List<MarketIndicatorRow> fetchOnDate(LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        List<Map<String, String>> body = fetchWithIoRetry(dateStr);
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        if (isQuotaExhausted(body)) {
            // 라이브 경로는 result:4를 계속 빈 결과로 취급한다(empty-retry·체인 폴백 유지, REQ-010 무회귀).
            return List.of();
        }
        return parseUsdRows(body, date);
    }

    /**
     * KOREAEXIM 쿼터 소진(`result:4`) 판별(REQ-010). 전 필드 null인 1원소 배열이고 그 {@code result} 코드가 {@code 4}인
     * 경우를 정상 빈 결과(길이 0 배열)와 구분한다.
     */
    private static boolean isQuotaExhausted(List<Map<String, String>> body) {
        return body.size() == 1 && "4".equals(body.getFirst().get("result"));
    }

    private List<MarketIndicatorRow> parseUsdRows(List<Map<String, String>> body, LocalDate date) {
        return body.stream()
                .filter(m -> "USD".equals(m.get("cur_unit")))
                .map(m -> toRow(m, date))
                .filter(r -> r != null)
                .toList();
    }

    /**
     * KOREAEXIM 서버의 I/O 장애(TCP RST, 리다이렉트 루프, 간헐적 중간 CA 인증서 누락 등)에 대한 2단계 재시도(#105).
     *
     * <p>{@link ResourceAccessException}(하위 {@link java.io.IOException} 레벨 실패)에 한정해 재시도한다. HTTP
     * 4xx/5xx 등 다른 예외는 재시도 없이 즉시 전파해 상위 {@code MarketIndicatorSourceChain}의 폴백(Yahoo)이 정상 동작하도록 한다.
     *
     * <p>1단계(즉시 재시도): 지수 백오프로 최대 {@code ioRetryMax}회, 순간적인 TCP RST에 유효(최대 7초 내외).
     *
     * <p>2단계(장주기 재시도): 1단계 상한을 넘겨도 바로 예외를 던지지 않고, {@code ioRetryLongMax}회에 걸쳐 {@code
     * ioRetryLongDelayMs}의 배수(5분, 10분, ...)로 대기 후 재시도한다. NAS 24시간 프로브 실측 결과 서버측 장애 창이 수초~1분 이상 지속되는
     * 경우가 확인되어, 1단계만으로는 넘기지 못하는 장애를 흡수하기 위함이다. {@code market_indicators}는 Tier-1(INSERT IGNORE 전용,
     * ADR-026)이라 Yahoo로 한 번 폴백되면 이후 KOREAEXIM이 성공해도 정정이 불가능하므로, 같은 배치 실행 안에서 더 오래 재시도하는 편이 낫다. 장주기
     * 재시도까지 전부 실패해야 예외를 전파한다.
     */
    private List<Map<String, String>> fetchWithIoRetry(String dateStr) {
        int attempt = 0;
        while (true) {
            try {
                return callKoreaexim(dateStr);
            } catch (ResourceAccessException e) {
                if (attempt >= ioRetryMax) {
                    return fetchWithLongRetry(dateStr, e);
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

    /**
     * 즉시 재시도 상한 소진 후의 장주기 재시도(#105). 매 회 {@code ioRetryLongDelayMs}의 배수(1회차 5분, 2회차 10분, ...)로 대기 후
     * 재시도하며, {@code ioRetryLongMax}회 전부 실패하면 마지막 예외를 전파한다.
     */
    private List<Map<String, String>> fetchWithLongRetry(
            String dateStr, ResourceAccessException lastException) {
        ResourceAccessException last = lastException;
        for (int longAttempt = 0; longAttempt < ioRetryLongMax; longAttempt++) {
            long delayMs = ioRetryLongDelayMs * (longAttempt + 1);
            log.warn(
                    "[koreaexim] I/O 오류 — 장주기 재시도 대기 {}ms 후 ({}/{}, 장주기): {}",
                    delayMs,
                    longAttempt + 1,
                    ioRetryLongMax,
                    SensitiveDataSanitizer.sanitize(last.getMessage()));
            sleep(delayMs);
            try {
                return callKoreaexim(dateStr);
            } catch (ResourceAccessException e) {
                last = e;
            }
        }
        log.warn(
                "[koreaexim] I/O 오류 재시도 상한 초과(즉시 {}회+장주기 {}회 모두 소진) — 예외 전파: {}",
                ioRetryMax,
                ioRetryLongMax,
                SensitiveDataSanitizer.sanitize(last.getMessage()));
        throw last;
    }

    private List<Map<String, String>> callKoreaexim(String dateStr) {
        return koreaeximRestClient.get().uri(PATH, apiKey, dateStr).retrieve().body(RESPONSE_TYPE);
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
